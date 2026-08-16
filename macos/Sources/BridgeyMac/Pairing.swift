import AppKit
import Combine
import CryptoKit
import Foundation
import Network
import Security
import Carbon
import UserNotifications

enum PairingState: Equatable {
    case idle
    case connecting(String)
    case verification(peerName: String, code: String)
    case connected(deviceID: String, peerName: String)
    case failed(String)
}

struct RemoteBatteryStatus: Equatable {
    let level: Int
    let isCharging: Bool
}

struct FileTransferRow: Identifiable, Equatable {
    let id: String
    let name: String
    let status: String
    let active: Bool
}

struct TrustedDeviceInfo: Identifiable, Equatable {
    let id: String
    let name: String
}

private struct BatteryPayload: Codable {
    let level: Int
    let isCharging: Bool
}

private struct RemoteNotificationPayload: Codable {
    let packageName: String
    let applicationName: String
    let notificationId: String
    let title: String
    let text: String
    let timestamp: Int64
}

private struct FileOfferPayload: Codable {
    let transferId: String
    let name: String
    let mimeType: String
    let size: Int64
    let sha256: String
}

private struct FileCompletePayload: Codable {
    let transferId: String
    let sha256: String
}

private struct FindDevicePayload: Codable {
    let alertId: String
}

private struct FeatureStatePayload: Codable {
    let version: Int
    let features: [String: Bool]
}

private func defaultRemoteFeatureState() -> [BridgeyFeature: Bool] {
    Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, true) })
}

private final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }
}

@MainActor
final class PairingCoordinator: ObservableObject {
    @Published private(set) var state: PairingState = .idle
    @Published private(set) var trustedDeviceIDs: Set<String>
    @Published private(set) var clipboardStatus: String? = nil
    @Published private(set) var remoteBattery: RemoteBatteryStatus? = nil
    @Published private(set) var notificationsAuthorized = false
    @Published private(set) var notificationPermissionDetermined = false
    @Published private(set) var fileTransferStatus: String? = nil
    @Published private(set) var fileTransferActive = false
    @Published private(set) var fileTransfers: [String: FileTransferRow] = [:]
    @Published private(set) var macRinging = false
    @Published private(set) var androidRinging = false
    @Published private(set) var remoteFeatures = defaultRemoteFeatureState()

    var trustedDevices: [TrustedDeviceInfo] {
        trustedDeviceIDs.map { id in
            TrustedDeviceInfo(id: id, name: UserDefaults.standard.string(forKey: "trusted.\(id).name") ?? "Unknown device")
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private let deviceID: String
    private var deviceName: String
    private let settings: BridgeySettings
    private let identity: MacIdentity
    private var listener: NWListener?
    private var session: Session?
    private var discoveryCancellable: AnyCancellable?
    private var settingsCancellable: AnyCancellable?
    private var reconnectWorkItem: DispatchWorkItem?
    private var connectionTimeoutWorkItem: DispatchWorkItem?
    private var lastTrustedEndpoint: (host: String, port: Int, name: String)?
    private var reconnectAttempt = 0
    private var clipboardHotKey: GlobalHotKey?
    private var clipboardSendID: String?
    private var clipboardTimeoutWorkItem: DispatchWorkItem?
    private let notificationPresenter = NotificationPresenter()
    private var incomingFiles: [String: IncomingFileTransfer] = [:]
    private var outgoingFiles: [String: OutgoingFileTransfer] = [:]
    private var fileOperationID: UUID?
    private var filePreparationCancellation: FileCancellationToken?
    private var fileTransferWindow: FileTransferWindowController?
    private var cancelledTransferIDs = Set<String>()
    private var findDeviceSound: NSSound?

    init(deviceID: String, deviceName: String, settings: BridgeySettings) {
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.settings = settings
        identity = MacIdentity()
        trustedDeviceIDs = Set(UserDefaults.standard.stringArray(forKey: "trustedDeviceIDs") ?? [])
        UNUserNotificationCenter.current().delegate = notificationPresenter
        refreshNotificationAuthorization()
        startListener()
        clipboardHotKey = GlobalHotKey(
            keyCode: UInt32(kVK_ANSI_C),
            modifiers: UInt32(controlKey | optionKey)
        ) { [weak self] in
            self?.sendClipboard()
        }
        settingsCancellable = settings.$globalFeatures
            .combineLatest(settings.$deviceFeatures)
            .dropFirst()
            .sink { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if !self.featureEnabled(.battery) { self.remoteBattery = nil }
                    if !self.featureEnabled(.clipboard) { self.clearClipboardSendStatus() }
                    self.sendFeatureState()
                }
            }
    }

    func refreshNotificationAuthorization() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { [weak self] settings in
            let authorized = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            DispatchQueue.main.async {
                self?.notificationsAuthorized = authorized
                self?.notificationPermissionDetermined = settings.authorizationStatus != .notDetermined
            }
        }
    }

    func enableNotifications() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { [weak self] settings in
            guard let self else { return }
            if settings.authorizationStatus != .notDetermined {
                DispatchQueue.main.async { self.openNotificationSettings() }
                return
            }
            Task { @MainActor in self.requestNotificationAuthorization() }
        }
    }

    private func requestNotificationAuthorization() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.requestAuthorization(options: [.alert, .sound]) { granted, error in
            DispatchQueue.main.async { [weak self] in
                self?.notificationsAuthorized = granted
                self?.notificationPermissionDetermined = true
            }
            if let error {
                NSLog("PLUGIN notifications authorization failed error=%@", String(describing: error))
            } else {
                NSLog("PLUGIN notifications authorization granted=%@", String(granted))
            }
        }
    }

    func openNotificationSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") else { return }
        NSWorkspace.shared.open(url)
    }

    func observe(_ discovery: BonjourDiscovery) {
        discoveryCancellable = discovery.$peers.sink { [weak self] peers in
            self?.connectTrustedPeerIfNeeded(peers)
        }
    }

    func pair(host: String, port: Int, peerName: String) {
        guard let endpointPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            state = .failed("Invalid peer port")
            return
        }
        state = .connecting(peerName)
        lastTrustedEndpoint = (host, port, peerName)
        NSLog("PAIRING started peer=%@", peerName)
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        let current = Session(connection: connection, peerName: peerName)
        current.initiatedLocally = true
        current.id = UUID().uuidString.lowercased()
        current.privateKey = P256.KeyAgreement.PrivateKey()
        current.localEphemeralKey = current.privateKey!.publicKey.x963Representation.base64EncodedString()
        session?.close()
        session = current
        remoteFeatures = defaultRemoteFeatureState()
        scheduleConnectionTimeout(for: current)
        configure(current) { [weak self, weak current] in
            guard let self, let current, current.privateKey != nil else { return }
            current.send(PairingMessage(
                kind: "pairing.offer",
                sessionId: current.id,
                deviceId: self.deviceID,
                deviceName: self.deviceName,
                publicKey: current.localEphemeralKey
            ))
        }
    }

    func confirm() {
        guard let current = session else { return }
        confirm(current)
    }

    private func confirm(_ current: Session) {
        current.localConfirmed = true
        let identityKey = identity.publicKey
        current.send(PairingMessage(
            kind: "pairing.confirm",
            sessionId: current.id,
            deviceId: deviceID,
            identityKey: identityKey,
            proof: confirmationProof(
                key: current.pairingKey!,
                sessionID: current.id,
                deviceID: deviceID,
                identityKey: identityKey
            ),
            signature: identity.sign(authTranscript(current))
        ))
        completeIfConfirmed(current)
    }

    func cancel() {
        connectionTimeoutWorkItem?.cancel()
        let current = session
        session = nil
        current?.send(PairingMessage(kind: "pairing.cancel", sessionId: current?.id ?? ""))
        current?.close()
        stopMacSound()
        androidRinging = false
        clearClipboardSendStatus()
        remoteFeatures = defaultRemoteFeatureState()
        state = .idle
    }

    func dismiss() {
        connectionTimeoutWorkItem?.cancel()
        let current = session
        session = nil
        current?.close()
        stopMacSound()
        androidRinging = false
        remoteBattery = nil
        clearClipboardSendStatus()
        remoteFeatures = defaultRemoteFeatureState()
        state = .idle
    }

    func forget(deviceID: String) {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: "trusted.\(deviceID).identityKey")
        defaults.removeObject(forKey: "trusted.\(deviceID).name")
        trustedDeviceIDs.remove(deviceID)
        defaults.set(Array(trustedDeviceIDs), forKey: "trustedDeviceIDs")
        settings.removeDevice(deviceID)
        if case let .connected(connectedID, _) = state, connectedID == deviceID { dismiss() }
        NSLog("PAIRING revoked peerId=%@", String(deviceID.prefix(8)))
    }

    func updateDeviceName(_ value: String) {
        deviceName = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
    }

    private func featureEnabled(_ feature: BridgeyFeature, current: Session? = nil) -> Bool {
        let id = (current ?? session)?.remoteDeviceID
        return settings.isEnabled(feature, for: id?.isEmpty == false ? id : nil)
    }

    func isFeatureAvailable(_ feature: BridgeyFeature) -> Bool {
        effectiveFeatureAvailable(
            localEnabled: featureEnabled(feature),
            remoteEnabled: remoteFeatures[feature] != false
        )
    }

    func sendClipboard() {
        guard let current = session, case .connected = state else { return }
        guard isFeatureAvailable(.clipboard) else {
            clipboardStatus = "Clipboard is turned off on one of your devices"
            return
        }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            clipboardStatus = "Clipboard is empty or unavailable"
            return
        }
        guard let encrypted = try? encrypt(Data(text.utf8), key: current.pairingKey!) else {
            clipboardStatus = "Encryption failed"
            return
        }
        let messageID = UUID().uuidString.lowercased()
        current.send(PairingMessage(
            kind: "clipboard.update",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        clipboardTimeoutWorkItem?.cancel()
        clipboardSendID = messageID
        clipboardStatus = "Sending…"
        let timeout = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.session === current,
                  self.clipboardSendID == messageID else { return }
            self.clipboardSendID = nil
            self.clipboardTimeoutWorkItem = nil
            self.clipboardStatus = "No delivery acknowledgement"
            self.sendFeatureState()
        }
        clipboardTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: timeout)
        NSLog("PLUGIN clipboard sent")
    }

    private func clearClipboardSendStatus() {
        clipboardTimeoutWorkItem?.cancel()
        clipboardTimeoutWorkItem = nil
        clipboardSendID = nil
        clipboardStatus = nil
    }

    func findAndroid() {
        _ = sendFindCommand(kind: "find.start")
    }

    func stopFinding() {
        stopMacSound()
        _ = sendFindCommand(kind: "find.stop")
    }

    private func sendFindCommand(kind: String) -> Bool {
        guard let current = session, case .connected = state,
              (kind != "find.start" || isFeatureAvailable(.findDevice)),
              let payload = try? JSONEncoder().encode(FindDevicePayload(alertId: "active")),
              let encrypted = try? encrypt(payload, key: current.pairingKey!) else { return false }
        current.send(PairingMessage(
            kind: kind,
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        return true
    }

    private func receiveFindCommand(_ message: PairingMessage, in current: Session, start: Bool) throws {
        if start && !featureEnabled(.findDevice, current: current) { return }
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(FindDevicePayload.self, from: plaintext),
              payload.alertId == "active" else { throw PairingError.invalidMessage }
        if start {
            startMacSound()
            _ = sendFindCommand(kind: macRinging ? "find.started" : "find.stopped")
        } else {
            stopMacSound()
            androidRinging = false
            _ = sendFindCommand(kind: "find.stopped")
        }
        NSLog("PLUGIN find-device %@", start ? "started" : "stopped")
    }

    private func receiveFindAcknowledgement(
        _ message: PairingMessage,
        in current: Session,
        started: Bool
    ) throws {
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(FindDevicePayload.self, from: plaintext),
              payload.alertId == "active" else { throw PairingError.invalidMessage }
        androidRinging = started
    }

    private func startMacSound() {
        guard !macRinging else { return }
        guard let sound = NSSound(contentsOfFile: "/System/Library/Sounds/Funk.aiff", byReference: true) else { return }
        sound.loops = true
        findDeviceSound = sound
        macRinging = true
        sound.play()
    }

    private func stopMacSound() {
        findDeviceSound?.stop()
        findDeviceSound = nil
        macRinging = false
    }

    func chooseAndSendFile() {
        guard session != nil, case .connected = state else {
            fileTransferStatus = "Not connected — file was not sent"
            return
        }
        guard isFeatureAvailable(.files) else {
            fileTransferStatus = "File transfer is turned off on one of your devices"
            return
        }
        // MenuBarExtra closes its transient window after invoking the action.
        // Present the picker on the next run-loop turn as an app-modal panel so
        // an LSUIElement app can reliably bring it in front of other windows.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            NSApp.activate(ignoringOtherApps: true)
            let panel = NSOpenPanel()
            panel.title = "Send File to Android"
            panel.prompt = "Send"
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = false
            panel.level = .floating
            guard panel.runModal() == .OK, let url = panel.url else { return }
            self.prepareFile(url)
        }
    }

    private func prepareFile(_ url: URL) {
        guard let current = session, case .connected = state else { return }
        let expectedSessionID = current.id
        let operationID = UUID()
        let preparationCancellation = FileCancellationToken()
        fileOperationID = operationID
        filePreparationCancellation = preparationCancellation
        beginFileTransferUI()
        fileTransferStatus = "Preparing \(url.lastPathComponent)…"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let transfer = try OutgoingFileTransfer(url: url, cancellation: preparationCancellation)
                DispatchQueue.main.async {
                    guard let self, self.fileOperationID == operationID,
                          let current = self.session, current.id == expectedSessionID,
                          case .connected = self.state else { return }
                    self.filePreparationCancellation = nil
                    do {
                        let payload = try JSONEncoder().encode(transfer.offer)
                        let encrypted = try self.encrypt(payload, key: current.pairingKey!)
                        self.outgoingFiles[transfer.transferID] = transfer
                        self.updateFileTransfer(
                            id: transfer.transferID,
                            name: transfer.displayName,
                            status: "Waiting for Android…",
                            active: true
                        )
                        current.send(PairingMessage(
                            kind: "files.offer",
                            sessionId: current.id,
                            messageId: UUID().uuidString.lowercased(),
                            transferId: transfer.transferID,
                            nonce: encrypted.nonce,
                            ciphertext: encrypted.ciphertext
                        ))
                        self.fileTransferStatus = "Waiting for Android…"
                    } catch {
                        self.fileTransferStatus = "Could not prepare the selected file"
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    guard self?.fileOperationID == operationID else { return }
                    self?.fileTransferStatus = "Could not read the selected file"
                    self?.fileTransferActive = false
                    self?.filePreparationCancellation = nil
                }
            }
        }
    }

    func cancelFileTransfer() {
        filePreparationCancellation?.cancel()
        filePreparationCancellation = nil
        let transferIDs = Set(incomingFiles.keys).union(outgoingFiles.keys)
        transferIDs.forEach { transferID in
            markTransferCancelled(transferID)
            session?.send(PairingMessage(kind: "files.cancel", sessionId: session?.id ?? "", transferId: transferID))
        }
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        outgoingFiles.values.forEach { $0.cancel() }
        outgoingFiles.removeAll()
        transferIDs.forEach { fileTransfers.removeValue(forKey: $0) }
        fileOperationID = nil
        fileTransferActive = false
        fileTransferStatus = "Transfer cancelled"
    }

    func cancelFileTransfer(id transferID: String) {
        markTransferCancelled(transferID)
        session?.send(PairingMessage(kind: "files.cancel", sessionId: session?.id ?? "", transferId: transferID))
        incomingFiles.removeValue(forKey: transferID)?.cancel()
        outgoingFiles.removeValue(forKey: transferID)?.cancel()
        fileTransfers.removeValue(forKey: transferID)
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
        fileTransferStatus = "Transfer cancelled"
    }

    func showFileTransferWindow() {
        if fileTransferWindow == nil {
            fileTransferWindow = FileTransferWindowController(pairing: self)
        }
        fileTransferWindow?.show()
    }

    private func beginFileTransferUI() {
        fileTransferActive = true
        showFileTransferWindow()
    }

    private func startListener() {
        do {
            let listener = try NWListener(using: .tcp, on: 42_458)
            listener.newConnectionHandler = { [weak self] connection in
                DispatchQueue.main.async { self?.accept(connection) }
            }
            listener.stateUpdateHandler = { newState in
                if case let .failed(error) = newState {
                    NSLog("PAIRING listener failed error=%@", String(describing: error))
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            state = .failed("Could not start pairing listener")
        }
    }

    private func accept(_ connection: NWConnection) {
        session?.close()
        let current = Session(connection: connection, peerName: "Android device")
        current.initiatedLocally = false
        session = current
        remoteFeatures = defaultRemoteFeatureState()
        configure(current, onReady: {})
    }

    private func configure(_ current: Session, onReady: @escaping () -> Void) {
        current.onMessage = { [weak self, weak current] message in
            guard let self, let current else { return }
            self.receive(message, in: current)
        }
        current.onFailure = { [weak self, weak current] in
            guard let self, self.session === current else { return }
            self.connectionTimeoutWorkItem?.cancel()
            self.cancelIncomingFiles()
            self.clearClipboardSendStatus()
            if !self.outgoingFiles.isEmpty {
                self.outgoingFiles.values.forEach { $0.cancel() }
                self.outgoingFiles.removeAll()
                self.fileTransferStatus = "File transfer interrupted"
            }
            if case .connected = self.state {
                self.session = nil
                self.remoteBattery = nil
                self.remoteFeatures = defaultRemoteFeatureState()
                self.state = .idle
                NSLog("TRANSPORT disconnected")
                self.scheduleReconnect()
            } else {
                self.state = .failed("Pairing connection lost")
            }
        }
        current.connection.stateUpdateHandler = { [weak current] newState in
            DispatchQueue.main.async {
                guard let current else { return }
                switch newState {
                case .ready:
                    NSLog("TRANSPORT connected")
                    current.receive()
                    onReady()
                case .failed, .cancelled:
                    current.onFailure?()
                default:
                    break
                }
            }
        }
        current.connection.start(queue: .main)
    }

    private func receive(_ message: PairingMessage, in current: Session) {
        do {
            switch message.kind {
            case "pairing.offer":
                guard let publicKey = message.publicKey else { throw PairingError.invalidMessage }
                current.id = message.sessionId
                current.peerName = message.deviceName ?? "Android device"
                guard let remoteDeviceID = message.deviceId else { throw PairingError.invalidMessage }
                current.remoteDeviceID = remoteDeviceID
                current.remoteEphemeralKey = publicKey
                let key = P256.KeyAgreement.PrivateKey()
                current.privateKey = key
                current.localEphemeralKey = key.publicKey.x963Representation.base64EncodedString()
                let material = try pairingMaterial(privateKey: key, remoteKey: publicKey, sessionID: current.id)
                current.code = material.code
                current.pairingKey = material.key
                current.send(PairingMessage(
                    kind: "pairing.answer",
                    sessionId: current.id,
                    deviceId: deviceID,
                    deviceName: deviceName,
                    publicKey: current.localEphemeralKey
                ))
                authenticateOrPrompt(current)
            case "pairing.answer":
                guard message.sessionId == current.id,
                      let key = current.privateKey,
                      let publicKey = message.publicKey,
                      let remoteDeviceID = message.deviceId else { throw PairingError.invalidMessage }
                current.peerName = message.deviceName ?? current.peerName
                current.remoteDeviceID = remoteDeviceID
                current.remoteEphemeralKey = publicKey
                let material = try pairingMaterial(privateKey: key, remoteKey: publicKey, sessionID: current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            case "pairing.confirm":
                guard message.sessionId == current.id,
                      let remoteID = message.deviceId,
                      remoteID == current.remoteDeviceID,
                      let identityKey = message.identityKey,
                      let proof = message.proof,
                      let signature = message.signature,
                      trustedIdentityKey(remoteID).map({ $0 == identityKey }) ?? true,
                      verifyConfirmationProof(
                        proof,
                        key: current.pairingKey!,
                        sessionID: current.id,
                        deviceID: remoteID,
                        identityKey: identityKey
                      ),
                      verifySignature(signature, identityKey: identityKey, data: authTranscript(current))
                else { throw PairingError.invalidMessage }
                current.remoteIdentityKey = identityKey
                current.remoteConfirmed = true
                completeIfConfirmed(current)
            case "pairing.cancel":
                cancel()
            case "features.update":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(FeatureStatePayload.self, from: plaintext),
                      payload.version == 1,
                      BridgeyFeature.allCases.allSatisfy({ payload.features[$0.rawValue] != nil }) else {
                    throw PairingError.invalidMessage
                }
                remoteFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
                    ($0, payload.features[$0.rawValue]!)
                })
                if remoteFeatures[.battery] == false { remoteBattery = nil }
                if remoteFeatures[.clipboard] == false { clearClipboardSendStatus() }
                if remoteFeatures[.files] == false && fileTransferActive {
                    cancelFileTransfer()
                    fileTransferStatus = nil
                }
                if remoteFeatures[.findDevice] == false {
                    stopMacSound()
                    androidRinging = false
                }
            case "clipboard.update":
                guard featureEnabled(.clipboard, current: current) else {
                    current.send(PairingMessage(
                        kind: "clipboard.rejected",
                        sessionId: current.id,
                        messageId: message.messageId
                    ))
                    sendFeatureState()
                    return
                }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let text = String(data: plaintext, encoding: .utf8) else {
                    throw PairingError.invalidMessage
                }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                NSLog("PLUGIN clipboard received")
                current.send(PairingMessage(kind: "clipboard.ack", sessionId: current.id, messageId: messageID))
            case "clipboard.ack":
                guard message.messageId == clipboardSendID else { return }
                clipboardTimeoutWorkItem?.cancel()
                clipboardTimeoutWorkItem = nil
                clipboardSendID = nil
                clipboardStatus = "Delivered"
                NSLog("PLUGIN clipboard acknowledged")
            case "clipboard.rejected":
                guard message.messageId == clipboardSendID else { return }
                clipboardTimeoutWorkItem?.cancel()
                clipboardTimeoutWorkItem = nil
                clipboardSendID = nil
                clipboardStatus = "Clipboard is turned off on Android"
            case "find.start":
                try receiveFindCommand(message, in: current, start: true)
            case "find.stop":
                try receiveFindCommand(message, in: current, start: false)
            case "find.started":
                try receiveFindAcknowledgement(message, in: current, started: true)
            case "find.stopped":
                try receiveFindAcknowledgement(message, in: current, started: false)
            case "battery.update":
                guard featureEnabled(.battery, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(BatteryPayload.self, from: plaintext),
                      (0...100).contains(payload.level) else {
                    throw PairingError.invalidMessage
                }
                remoteBattery = RemoteBatteryStatus(level: payload.level, isCharging: payload.isCharging)
                NSLog("PLUGIN battery received level=%d charging=%@", payload.level, String(payload.isCharging))
            case "notifications.post":
                guard featureEnabled(.notifications, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(RemoteNotificationPayload.self, from: plaintext),
                      !payload.packageName.isEmpty,
                      !payload.applicationName.isEmpty,
                      (!payload.title.isEmpty || !payload.text.isEmpty) else {
                    throw PairingError.invalidMessage
                }
                postNotification(payload)
            case "files.offer":
                guard featureEnabled(.files, current: current) else {
                    current.send(PairingMessage(
                        kind: "files.rejected",
                        sessionId: current.id,
                        transferId: message.transferId
                    ))
                    sendFeatureState()
                    return
                }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let offer = try? JSONDecoder().decode(FileOfferPayload.self, from: plaintext),
                      UUID(uuidString: offer.transferId) != nil,
                      !offer.name.isEmpty,
                      offer.size >= 0,
                      offer.size <= 10 * 1024 * 1024 * 1024,
                      Data(base64Encoded: offer.sha256)?.count == 32 else {
                    throw PairingError.invalidMessage
                }
                guard incomingFiles[offer.transferId] == nil else { throw PairingError.invalidMessage }
                let transfer = try IncomingFileTransfer(offer: offer, directoryAccess: settings.receiveDirectoryAccess())
                incomingFiles[offer.transferId] = transfer
                fileOperationID = UUID()
                beginFileTransferUI()
                fileTransferStatus = "Receiving \(transfer.displayName): \(transfer.progressStatus(force: true)!)"
                updateFileTransfer(id: offer.transferId, name: transfer.displayName, status: fileTransferStatus!, active: true)
                current.send(PairingMessage(
                    kind: "files.accept",
                    sessionId: current.id,
                    transferId: offer.transferId
                ))
                NSLog("PLUGIN file accepted name=%@ size=%lld", transfer.displayName, offer.size)
            case "files.chunk":
                if let transferID = message.transferId,
                   incomingFiles[transferID] == nil,
                   cancelledTransferIDs.contains(transferID) { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let transferID = message.transferId,
                      let sequence = message.sequence,
                      let transfer = incomingFiles[transferID],
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let chunk = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!) else {
                    throw PairingError.invalidMessage
                }
                try transfer.append(chunk, sequence: sequence)
                if let progress = transfer.progressStatus() {
                    fileTransferStatus = "Receiving \(transfer.displayName): \(progress)"
                    updateFileTransfer(id: transferID, name: transfer.displayName, status: fileTransferStatus!, active: true)
                }
            case "files.complete":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let completion = try? JSONDecoder().decode(FileCompletePayload.self, from: plaintext) else {
                    throw PairingError.invalidMessage
                }
                guard let transfer = incomingFiles.removeValue(forKey: completion.transferId) else {
                    if cancelledTransferIDs.contains(completion.transferId) { return }
                    throw PairingError.invalidMessage
                }
                do {
                    let destination = try transfer.finish(expectedHash: completion.sha256)
                    let folder = destination.deletingLastPathComponent().path
                    fileTransferStatus = "Saved \(transfer.displayName) to \(folder)"
                    updateFileTransfer(id: completion.transferId, name: transfer.displayName, status: fileTransferStatus!, active: false)
                    fileTransferActive = false
                    fileOperationID = nil
                    current.send(PairingMessage(
                        kind: "files.complete.ack",
                        sessionId: current.id,
                        transferId: completion.transferId
                    ))
                    NSWorkspace.shared.activateFileViewerSelecting([destination])
                    NSLog("PLUGIN file received name=%@", transfer.displayName)
                } catch {
                    transfer.cancel()
                    fileTransferStatus = "File verification failed"
                    throw error
                }
            case "files.accept":
                if let transferID = message.transferId,
                   outgoingFiles[transferID] == nil,
                   cancelledTransferIDs.contains(transferID) { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let transferID = message.transferId,
                      let transfer = outgoingFiles[transferID] else { throw PairingError.invalidMessage }
                updateFileTransfer(id: transferID, name: transfer.displayName, status: "Sending \(transfer.displayName)…", active: true)
                transfer.send(
                    through: current,
                    key: current.pairingKey!,
                    status: { [weak self] value in
                        guard let self, self.outgoingFiles[transferID] === transfer,
                              !transfer.isCancelled,
                              !self.cancelledTransferIDs.contains(transferID) else { return }
                        self.fileTransferStatus = value
                        self.updateFileTransfer(id: transferID, name: transfer.displayName, status: value, active: true)
                    },
                    completion: { [weak self, weak current] result in
                        guard let self, let current, self.session === current else { return }
                        switch result {
                        case let .success(completion):
                            guard !transfer.isCancelled,
                                  self.outgoingFiles[transferID] === transfer,
                                  !self.cancelledTransferIDs.contains(transferID) else { return }
                            current.send(completion)
                        case .failure:
                            self.outgoingFiles.removeValue(forKey: transferID)
                            if transfer.isCancelled {
                                self.fileTransferStatus = "Transfer cancelled"
                                self.fileTransfers.removeValue(forKey: transferID)
                                self.fileTransferActive = self.fileTransfers.values.contains(where: { $0.active })
                                return
                            } else {
                                self.fileTransferStatus = "File transfer failed"
                            }
                            self.fileTransferActive = self.fileTransfers.values.contains(where: { $0.active })
                            self.fileOperationID = nil
                            self.updateFileTransfer(id: transferID, name: transfer.displayName, status: self.fileTransferStatus!, active: false)
                        }
                    }
                )
            case "files.rejected":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                transfer.cancel()
                markTransferCancelled(transferID)
                fileTransfers.removeValue(forKey: transferID)
                fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                fileOperationID = nil
                filePreparationCancellation = nil
                fileTransferStatus = "File transfer is turned off on Android"
            case "files.complete.ack":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                fileTransferStatus = "\(transfer.displayName) saved on Android"
                updateFileTransfer(id: transferID, name: transfer.displayName, status: fileTransferStatus!, active: false)
                fileTransferActive = false
                fileOperationID = nil
                filePreparationCancellation = nil
                NSLog("PLUGIN file sent name=%@", transfer.displayName)
            case "files.cancel":
                guard let transferID = message.transferId else { return }
                markTransferCancelled(transferID)
                incomingFiles.removeValue(forKey: transferID)?.cancel()
                outgoingFiles.removeValue(forKey: transferID)?.cancel()
                fileTransfers.removeValue(forKey: transferID)
                fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                fileOperationID = nil
                fileTransferStatus = "Transfer cancelled by Android"
                current.send(PairingMessage(kind: "files.cancel.ack", sessionId: current.id, transferId: transferID))
                NSLog("PLUGIN file cancellation received transfer=%@", String(transferID.prefix(8)))
            case "files.cancel.ack":
                if let transferID = message.transferId { fileTransfers.removeValue(forKey: transferID) }
            case "files.chunk.ack":
                if let transferID = message.transferId, let sequence = message.sequence {
                    outgoingFiles[transferID]?.acknowledge(sequence: sequence)
                }
            default:
                break
            }
        } catch {
            current.close()
            state = .failed("Invalid pairing message")
        }
    }

    private func completeIfConfirmed(_ current: Session) {
        if current.localConfirmed && current.remoteConfirmed {
            connectionTimeoutWorkItem?.cancel()
            saveTrust(current)
            state = .connected(deviceID: current.remoteDeviceID, peerName: current.peerName)
            reconnectAttempt = 0
            reconnectWorkItem?.cancel()
            sendFeatureState()
            NSLog("PAIRING verified peer=%@", current.peerName)
        }
    }

    private func sendFeatureState() {
        guard let current = session, case .connected = state, let key = current.pairingKey else { return }
        let features = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
            ($0.rawValue, settings.isEnabled($0, for: current.remoteDeviceID))
        })
        guard let payload = try? JSONEncoder().encode(FeatureStatePayload(version: 1, features: features)),
              let encrypted = try? encrypt(payload, key: key) else { return }
        current.send(PairingMessage(
            kind: "features.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
    }

    private func cancelIncomingFiles() {
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        fileTransferActive = false
        fileOperationID = nil
        filePreparationCancellation = nil
        if fileTransferStatus?.hasPrefix("Receiving ") == true {
            fileTransferStatus = "File transfer interrupted"
        }
    }

    private func markTransferCancelled(_ transferID: String) {
        cancelledTransferIDs.insert(transferID)
        if cancelledTransferIDs.count > 64, let first = cancelledTransferIDs.first {
            cancelledTransferIDs.remove(first)
        }
    }

    private func updateFileTransfer(id: String, name: String, status: String, active: Bool) {
        fileTransfers[id] = FileTransferRow(id: id, name: name, status: status, active: active)
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
        if !active {
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard self?.fileTransfers[id]?.active == false else { return }
                self?.fileTransfers.removeValue(forKey: id)
            }
        }
    }

    private func authenticateOrPrompt(_ current: Session) {
        if trustedIdentityKey(current.remoteDeviceID) != nil {
            confirm(current)
        } else {
            state = .verification(peerName: current.peerName, code: current.code!)
        }
    }

    private func authTranscript(_ current: Session) -> Data {
        let fields = current.initiatedLocally
            ? [deviceID, current.remoteDeviceID, current.localEphemeralKey, current.remoteEphemeralKey]
            : [current.remoteDeviceID, deviceID, current.remoteEphemeralKey, current.localEphemeralKey]
        return Data((["bridgey-auth-v1", current.id] + fields).joined(separator: "\0").utf8)
    }

    private func trustedIdentityKey(_ deviceID: String) -> String? {
        UserDefaults.standard.string(forKey: "trusted.\(deviceID).identityKey")
    }

    private func verifySignature(_ value: String, identityKey: String, data: Data) -> Bool {
        guard let publicData = Data(base64Encoded: identityKey),
              let signatureData = Data(base64Encoded: value),
              let publicKey = try? P256.Signing.PublicKey(x963Representation: publicData),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) else { return false }
        return publicKey.isValidSignature(signature, for: data)
    }

    func connectTrustedPeerIfNeeded(_ peers: [DiscoveredPeer]) {
        guard state == .idle else { return }
        guard let peer = peers.first(where: {
            guard let id = $0.deviceIDHint else { return false }
            return trustedDeviceIDs.contains(id) && deviceID < id
        }), let host = peer.host, let port = peer.port else { return }
        pair(host: host, port: port, peerName: peer.deviceNameHint)
    }

    private func scheduleReconnect() {
        guard let endpoint = lastTrustedEndpoint else { return }
        reconnectWorkItem?.cancel()
        let delay = min(pow(2.0, Double(reconnectAttempt)), 30.0)
        reconnectAttempt += 1
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.state == .idle else { return }
            self.pair(host: endpoint.host, port: endpoint.port, peerName: endpoint.name)
        }
        reconnectWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func scheduleConnectionTimeout(for current: Session) {
        connectionTimeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.session === current else { return }
            guard case .connecting = self.state else { return }
            self.session = nil
            current.close()
            self.state = .idle
            NSLog("TRANSPORT connection timed out")
            self.scheduleReconnect()
        }
        connectionTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: timeout)
    }

    private func postNotification(_ payload: RemoteNotificationPayload) {
        let content = UNMutableNotificationContent()
        content.title = payload.applicationName
        content.subtitle = payload.title
        content.body = payload.text
        content.sound = .default
        content.userInfo = ["androidPackage": payload.packageName]
        let digest = SHA256.hash(data: Data(payload.notificationId.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        let request = UNNotificationRequest(
            identifier: "bridgey.android.\(digest)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("PLUGIN notification delivery failed package=%@ error=%@", payload.packageName, String(describing: error))
            } else {
                NSLog("PLUGIN notification received package=%@", payload.packageName)
            }
        }
    }

    private func pairingMaterial(
        privateKey: P256.KeyAgreement.PrivateKey,
        remoteKey: String,
        sessionID: String
    ) throws -> (code: String, key: Data) {
        guard let data = Data(base64Encoded: remoteKey) else { throw PairingError.invalidMessage }
        let publicKey = try P256.KeyAgreement.PublicKey(x963Representation: data)
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
        let salt = Data(SHA256.hash(data: Data(sessionID.utf8)))
        let key = secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: Data("bridgey-pairing-v1".utf8),
            outputByteCount: 32
        )
        let bytes = key.withUnsafeBytes { Array($0.prefix(4)) }
        let value = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) |
            (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
        return (String(format: "%06d", value % 1_000_000), key.withUnsafeBytes { Data($0) })
    }

    private func confirmationProof(key: Data, sessionID: String, deviceID: String, identityKey: String) -> String {
        let data = Data("bridgey-confirm-v1\0\(sessionID)\0\(deviceID)\0\(identityKey)".utf8)
        let code = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))
        return Data(code).base64EncodedString()
    }

    private func verifyConfirmationProof(
        _ proof: String,
        key: Data,
        sessionID: String,
        deviceID: String,
        identityKey: String
    ) -> Bool {
        guard let received = Data(base64Encoded: proof) else { return false }
        let data = Data("bridgey-confirm-v1\0\(sessionID)\0\(deviceID)\0\(identityKey)".utf8)
        return HMAC<SHA256>.isValidAuthenticationCode(received, authenticating: data, using: SymmetricKey(data: key))
    }

    private func saveTrust(_ current: Session) {
        guard let identityKey = current.remoteIdentityKey else { return }
        let defaults = UserDefaults.standard
        defaults.set(identityKey, forKey: "trusted.\(current.remoteDeviceID).identityKey")
        defaults.set(current.peerName, forKey: "trusted.\(current.remoteDeviceID).name")
        trustedDeviceIDs.insert(current.remoteDeviceID)
        defaults.set(Array(trustedDeviceIDs), forKey: "trustedDeviceIDs")
    }

    private func encrypt(_ plaintext: Data, key: Data) throws -> (nonce: String, ciphertext: String) {
        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key))
        return (
            Data(sealed.nonce).base64EncodedString(),
            (sealed.ciphertext + sealed.tag).base64EncodedString()
        )
    }

    private func decrypt(nonce: String, ciphertext: String, key: Data) throws -> Data {
        guard let nonceData = Data(base64Encoded: nonce),
              let combined = Data(base64Encoded: ciphertext),
              combined.count >= 16 else { throw PairingError.invalidMessage }
        let sealed = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonceData),
            ciphertext: combined.dropLast(16),
            tag: combined.suffix(16)
        )
        return try AES.GCM.open(sealed, using: SymmetricKey(data: key))
    }
}

private final class Session {
    let connection: NWConnection
    var peerName: String
    var remoteDeviceID = ""
    var remoteIdentityKey: String?
    var initiatedLocally = false
    var localEphemeralKey = ""
    var remoteEphemeralKey = ""
    var id = ""
    var privateKey: P256.KeyAgreement.PrivateKey?
    var code: String?
    var pairingKey: Data?
    var localConfirmed = false
    var remoteConfirmed = false
    var onMessage: ((PairingMessage) -> Void)?
    var onFailure: (() -> Void)?
    private var buffer = Data()
    private var seenMessageIDs = Set<String>()

    init(connection: NWConnection, peerName: String) {
        self.connection = connection
        self.peerName = peerName
    }

    func send(_ message: PairingMessage) {
        guard let encoded = try? JSONEncoder().encode(message) else { return }
        connection.send(content: encoded + Data([0x0A]), completion: .contentProcessed { error in
            if let error { NSLog("TRANSPORT send failed kind=%@ error=%@", message.kind, String(describing: error)) }
        })
    }

    func sendAndWait(_ message: PairingMessage) -> Bool {
        guard let encoded = try? JSONEncoder().encode(message) else { return false }
        let semaphore = DispatchSemaphore(value: 0)
        var succeeded = false
        connection.send(content: encoded + Data([0x0A]), completion: .contentProcessed { error in
            succeeded = error == nil
            if let error { NSLog("TRANSPORT send failed kind=%@ error=%@", message.kind, String(describing: error)) }
            semaphore.signal()
        })
        return semaphore.wait(timeout: .now() + 15) == .success && succeeded
    }

    func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let data { self.buffer.append(data) }
                while let newline = self.buffer.firstIndex(of: 0x0A) {
                    let line = self.buffer[..<newline]
                    self.buffer.removeSubrange(...newline)
                    do {
                        let message = try JSONDecoder().decode(PairingMessage.self, from: Data(line))
                        self.onMessage?(message)
                    } catch {
                        NSLog("TRANSPORT invalid message error=%@", String(describing: error))
                    }
                }
                if complete || error != nil { self.onFailure?() } else { self.receive() }
            }
        }
    }

    func close() { connection.cancel() }

    func acceptMessageID(_ id: String) -> Bool {
        guard seenMessageIDs.insert(id).inserted else { return false }
        if seenMessageIDs.count > 256 { seenMessageIDs.remove(seenMessageIDs.first!) }
        return true
    }
}

private struct PairingMessage: Codable {
    let kind: String
    let sessionId: String
    var deviceId: String? = nil
    var deviceName: String? = nil
    var publicKey: String? = nil
    var identityKey: String? = nil
    var proof: String? = nil
    var signature: String? = nil
    var messageId: String? = nil
    var nonce: String? = nil
    var ciphertext: String? = nil
    var transferId: String? = nil
    var sequence: Int64? = nil
}

private enum PairingError: Error { case invalidMessage, cancelled }

private final class FileCancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool { lock.withLock { value } }
    func cancel() { lock.withLock { value = true } }
}

private final class OutgoingFileTransfer {
    private static let chunkSize = 24 * 1024
    private static let acknowledgementWindow: Int64 = 64
    let transferID = UUID().uuidString.lowercased()
    let displayName: String
    let offer: FileOfferPayload
    private let url: URL
    private let lock = NSLock()
    private var cancelled = false
    private let acknowledgement = NSCondition()
    private var acknowledgedSequence: Int64 = -1
    var isCancelled: Bool { lock.withLock { cancelled } }

    func cancel() {
        lock.withLock { cancelled = true }
        acknowledgement.lock()
        acknowledgement.broadcast()
        acknowledgement.unlock()
    }

    func acknowledge(sequence: Int64) {
        acknowledgement.lock()
        acknowledgedSequence = max(acknowledgedSequence, sequence)
        acknowledgement.broadcast()
        acknowledgement.unlock()
    }

    private func waitForAcknowledgement(sequence: Int64) throws {
        acknowledgement.lock()
        defer { acknowledgement.unlock() }
        let deadline = Date().addingTimeInterval(10)
        while acknowledgedSequence < sequence && !isCancelled {
            if !acknowledgement.wait(until: deadline) { throw PairingError.invalidMessage }
        }
        if isCancelled { throw PairingError.cancelled }
    }

    init(url: URL, cancellation: FileCancellationToken) throws {
        self.url = url
        displayName = url.lastPathComponent
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let size = Int64(values.fileSize ?? -1)
        guard size >= 0, size <= 10 * 1024 * 1024 * 1024 else { throw PairingError.invalidMessage }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var digest = SHA256()
        while let data = try handle.read(upToCount: 256 * 1024), !data.isEmpty {
            guard !cancellation.isCancelled else { throw PairingError.cancelled }
            digest.update(data: data)
        }
        offer = FileOfferPayload(
            transferId: transferID,
            name: displayName,
            mimeType: values.contentType?.preferredMIMEType ?? "application/octet-stream",
            size: size,
            sha256: Data(digest.finalize()).base64EncodedString()
        )
    }

    func send(
        through session: Session,
        key: Data,
        status: @escaping @MainActor (String) -> Void,
        completion: @escaping @MainActor (Result<PairingMessage, Error>) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let handle = try FileHandle(forReadingFrom: self.url)
                defer { try? handle.close() }
                var sent: Int64 = 0
                var sequence: Int64 = 0
                let progress = MacTransferProgress(totalBytes: self.offer.size)
                while let chunk = try handle.read(upToCount: Self.chunkSize), !chunk.isEmpty {
                    guard !self.isCancelled else { throw PairingError.cancelled }
                    let sealed = try Self.encrypt(chunk, key: key)
                    guard session.sendAndWait(PairingMessage(
                        kind: "files.chunk",
                        sessionId: session.id,
                        messageId: UUID().uuidString.lowercased(),
                        nonce: sealed.nonce,
                        ciphertext: sealed.ciphertext,
                        transferId: self.transferID,
                        sequence: sequence
                    )) else { throw PairingError.invalidMessage }
                    if sequence >= Self.acknowledgementWindow - 1 {
                        try self.waitForAcknowledgement(sequence: sequence - (Self.acknowledgementWindow - 1))
                    }
                    sequence += 1
                    sent += Int64(chunk.count)
                    if let detail = progress.status(transferred: sent) {
                        DispatchQueue.main.async { status("Sending \(self.displayName): \(detail)") }
                    }
                }
                if sequence > 0 { try self.waitForAcknowledgement(sequence: sequence - 1) }
                guard !self.isCancelled else { throw PairingError.cancelled }
                let completionData = try JSONEncoder().encode(FileCompletePayload(
                    transferId: self.transferID,
                    sha256: self.offer.sha256
                ))
                let sealed = try Self.encrypt(completionData, key: key)
                let message = PairingMessage(
                    kind: "files.complete",
                    sessionId: session.id,
                    messageId: UUID().uuidString.lowercased(),
                    nonce: sealed.nonce,
                    ciphertext: sealed.ciphertext
                )
                guard !self.isCancelled else { throw PairingError.cancelled }
                DispatchQueue.main.async { status("Verifying \(self.displayName) on Android…") }
                DispatchQueue.main.async { completion(.success(message)) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }
    }

    private static func encrypt(_ plaintext: Data, key: Data) throws -> (nonce: String, ciphertext: String) {
        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key))
        return (Data(sealed.nonce).base64EncodedString(), (sealed.ciphertext + sealed.tag).base64EncodedString())
    }
}

private final class MacTransferProgress {
    private let totalBytes: Int64
    private var lastAt = ProcessInfo.processInfo.systemUptime
    private var lastBytes: Int64 = 0
    private var smoothed = 0.0

    init(totalBytes: Int64) { self.totalBytes = totalBytes }

    func status(transferred: Int64, force: Bool = false) -> String? {
        let now = ProcessInfo.processInfo.systemUptime
        let elapsed = now - lastAt
        if !force && elapsed < 0.25 && transferred < totalBytes { return nil }
        if elapsed > 0 {
            let sample = Double(max(0, transferred - lastBytes)) / elapsed
            smoothed = smoothed == 0 ? sample : smoothed * 0.7 + sample * 0.3
        }
        lastAt = now
        lastBytes = transferred
        let percent = totalBytes == 0 ? 100 : min(100, Int(transferred * 100 / totalBytes))
        let remaining = max(0, totalBytes - transferred)
        let eta: String
        if smoothed >= 1 && remaining > 0 {
            let seconds = Int64(Double(remaining) / smoothed)
            eta = seconds < 60 ? "\(max(1, seconds)) sec" : "\(seconds / 60) min \(seconds % 60) sec"
        } else { eta = "calculating…" }
        return "\(percent)% · \(ByteCountFormatter.string(fromByteCount: transferred, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)) · \(ByteCountFormatter.string(fromByteCount: Int64(smoothed), countStyle: .file))/s · \(eta) left"
    }
}

private final class IncomingFileTransfer {
    let displayName: String
    let expectedSize: Int64
    private let expectedHash: String
    private let partialURL: URL
    private let finalURL: URL
    private let handle: FileHandle
    private let directoryAccess: ReceiveDirectoryAccess
    private var hasher = SHA256()
    private(set) var receivedSize: Int64 = 0
    private var nextSequence: Int64 = 0
    private var lastProgressAt = ProcessInfo.processInfo.systemUptime
    private var lastProgressBytes: Int64 = 0
    private var smoothedBytesPerSecond = 0.0

    init(offer: FileOfferPayload, directoryAccess: ReceiveDirectoryAccess) throws {
        expectedSize = offer.size
        expectedHash = offer.sha256
        self.directoryAccess = directoryAccess
        let sanitized = Self.sanitize(offer.name)
        displayName = sanitized
        let directory = directoryAccess.url
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        finalURL = Self.uniqueURL(directory.appendingPathComponent(sanitized))
        partialURL = finalURL.appendingPathExtension("bridgey-part")
        FileManager.default.createFile(atPath: partialURL.path, contents: nil)
        handle = try FileHandle(forWritingTo: partialURL)
    }

    func append(_ data: Data, sequence: Int64) throws {
        guard sequence == nextSequence,
              data.count <= 24 * 1024,
              receivedSize + Int64(data.count) <= expectedSize else { throw PairingError.invalidMessage }
        try handle.write(contentsOf: data)
        hasher.update(data: data)
        receivedSize += Int64(data.count)
        nextSequence += 1
    }

    func finish(expectedHash completionHash: String) throws -> URL {
        try handle.close()
        let actual = Data(hasher.finalize()).base64EncodedString()
        guard receivedSize == expectedSize,
              completionHash == expectedHash,
              actual == expectedHash else {
            try? FileManager.default.removeItem(at: partialURL)
            throw PairingError.invalidMessage
        }
        try FileManager.default.moveItem(at: partialURL, to: finalURL)
        return finalURL
    }

    func progressStatus(force: Bool = false) -> String? {
        let now = ProcessInfo.processInfo.systemUptime
        let elapsed = now - lastProgressAt
        if !force && elapsed < 0.25 && receivedSize < expectedSize { return nil }
        if elapsed > 0 {
            let sample = Double(max(0, receivedSize - lastProgressBytes)) / elapsed
            smoothedBytesPerSecond = smoothedBytesPerSecond == 0 ? sample : smoothedBytesPerSecond * 0.7 + sample * 0.3
        }
        lastProgressAt = now
        lastProgressBytes = receivedSize
        let percent = expectedSize == 0 ? 100 : min(100, Int(receivedSize * 100 / expectedSize))
        let remaining = max(0, expectedSize - receivedSize)
        let eta = smoothedBytesPerSecond >= 1 && remaining > 0
            ? Self.formatDuration(Int64(Double(remaining) / smoothedBytesPerSecond))
            : "calculating…"
        return "\(percent)% · \(Self.formatBytes(receivedSize)) / \(Self.formatBytes(expectedSize)) · \(Self.formatBytes(Int64(smoothedBytesPerSecond)))/s · \(eta) left"
    }

    func cancel() {
        try? handle.close()
        try? FileManager.default.removeItem(at: partialURL)
    }

    private static func sanitize(_ name: String) -> String {
        let leaf = (name as NSString).lastPathComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return String(leaf.prefix(255)).isEmpty ? "file" : String(leaf.prefix(255))
    }

    private static func uniqueURL(_ requested: URL) -> URL {
        guard FileManager.default.fileExists(atPath: requested.path) else { return requested }
        let extensionPart = requested.pathExtension
        let stem = requested.deletingPathExtension().lastPathComponent
        for number in 2...10_000 {
            let name = extensionPart.isEmpty ? "\(stem) \(number)" : "\(stem) \(number).\(extensionPart)"
            let candidate = requested.deletingLastPathComponent().appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return requested.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
    }

    private static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private static func formatDuration(_ seconds: Int64) -> String {
        if seconds < 1 { return "<1 sec" }
        if seconds < 60 { return "\(seconds) sec" }
        if seconds < 3_600 { return "\(seconds / 60) min \(seconds % 60) sec" }
        return "\(seconds / 3_600) h \((seconds % 3_600) / 60) min"
    }
}

private final class MacIdentity {
    private let service = "dev.bridgey.identity"
    private let account = "p256-signing-v1"
    private let key: P256.Signing.PrivateKey

    init() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let stored = try? P256.Signing.PrivateKey(rawRepresentation: data) {
            key = stored
        } else {
            let generated = P256.Signing.PrivateKey()
            key = generated
            let add: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                kSecValueData as String: generated.rawRepresentation,
            ]
            SecItemDelete(query as CFDictionary)
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    var publicKey: String { key.publicKey.x963Representation.base64EncodedString() }

    func sign(_ data: Data) -> String {
        (try? key.signature(for: data).derRepresentation.base64EncodedString()) ?? ""
    }
}
