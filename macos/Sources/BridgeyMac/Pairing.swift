import AppKit
import Combine
import CryptoKit
import Foundation
import Network
import Security
import Carbon
import UserNotifications
import UniformTypeIdentifiers

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

struct RemoteCallAction: Identifiable, Equatable {
    let id: String
    let title: String
}

struct RemoteCallStatus: Equatable {
    let notificationID: String
    let deviceID: String
    let applicationName: String
    let caller: String
    let detail: String
    let type: String
    let actions: [RemoteCallAction]
}

struct FileTransferRow: Identifiable, Equatable {
    let id: String
    let name: String
    let status: String
    let active: Bool
    let startedAt: Date
    let retryable: Bool
    let queued: Bool
    /// The saved file of a finished incoming transfer, for Show in Finder and Quick Look.
    let localURL: URL?

    init(
        id: String,
        name: String,
        status: String,
        active: Bool,
        startedAt: Date = Date(),
        retryable: Bool = false,
        queued: Bool = false,
        localURL: URL? = nil
    ) {
        self.id = id
        self.name = name
        self.status = status
        self.active = active
        self.startedAt = startedAt
        self.retryable = retryable
        self.queued = queued
        self.localURL = localURL
    }
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
    let actions: [RemoteNotificationActionPayload]?
    let applicationIcon: String?
    let callType: String?
}

private struct RemoteNotificationActionPayload: Codable {
    let actionToken: String
    let title: String
    let allowsReply: Bool
}

private struct NotificationReferencePayload: Codable {
    let notificationId: String
}

private struct NotificationActionCommandPayload: Codable {
    let notificationId: String
    let actionToken: String
    let replyText: String?
}

private struct FileOfferPayload: Codable {
    let transferId: String
    let name: String
    let mimeType: String
    let size: Int64
    let sha256: String
    var folderId: String? = nil
    var entry: Int? = nil
    var browseRootId: String? = nil
    var browsePath: String? = nil
}

private struct FolderOfferPayload: Codable {
    var version = 1
    let folderId: String
    let name: String
    let fileCount: Int
    let folderCount: Int
    let totalSize: Int64
    let pageCount: Int
}

private struct FolderManifestPagePayload: Codable {
    var version = 1
    let folderId: String
    let page: Int
    let entries: [FolderEntryPayload]
}

private struct FolderAcceptPayload: Codable {
    let version: Int
    let folderId: String
    let name: String
}

private struct FolderRejectedPayload: Codable {
    let version: Int
    let folderId: String
    let code: String
}

private struct FolderCompletePayload: Codable {
    var version = 1
    let folderId: String
    let sentFiles: Int
}

private struct FolderResultPayload: Codable {
    let version: Int
    let folderId: String
    let savedFiles: Int
}

private struct FileCompletePayload: Codable {
    let transferId: String
    let sha256: String
}

private struct FindDevicePayload: Codable {
    let alertId: String
}

private struct PingPayload: Codable {
    let version: Int
}

private struct CallRequestPayload: Codable {
    let number: String
}

private struct FeatureStatePayload: Codable {
    let version: Int
    let features: [String: Bool]
}

private func defaultRemoteFeatureState() -> [BridgeyFeature: Bool] {
    Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, featureEnabledByLegacyPeer($0)) })
}

private final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    var onDismiss: ((String, String) -> Void)?
    var onAction: ((String, String, String, String?) -> Void)?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        guard let notificationID = response.notification.request.content.userInfo["androidNotificationId"] as? String,
              let deviceID = response.notification.request.content.userInfo["androidDeviceId"] as? String else { return }
        if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            onDismiss?(notificationID, deviceID)
        } else if response.actionIdentifier != UNNotificationDefaultActionIdentifier {
            let replyText = (response as? UNTextInputNotificationResponse)?.userText
            onAction?(notificationID, deviceID, response.actionIdentifier, replyText)
        }
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
    @Published private(set) var notificationHistory: [NotificationHistoryItem] = []
    @Published private(set) var remoteCall: RemoteCallStatus?
    @Published private(set) var callStatus: String?
    @Published private(set) var pingStatus: String?
    let quickActions = QuickActions()
    let folderBrowser = PhoneFolderBrowser()
    let mediaController = MediaController()
    let shortcuts = ShortcutSettings()

    var trustedDevices: [TrustedDeviceInfo] {
        trustRegistry.devices.map { device in
            TrustedDeviceInfo(id: device.id, name: device.name)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private let deviceID: String
    private var deviceName: String
    private let settings: BridgeySettings
    private let identity: MacIdentity
    private let trustRegistry: MacTrustRegistry
    private var listener: NWListener?
    private var session: Session?
    private var discoveryCancellable: AnyCancellable?
    private var settingsCancellable: AnyCancellable?
    private var reconnectWorkItem: DispatchWorkItem?
    private var connectionTimeoutWorkItem: DispatchWorkItem?
    private var heartbeatWorkItem: DispatchWorkItem?
    private var lastTrustedEndpoint: (host: String, port: Int, name: String)?
    private var reconnectAttempt = 0
    private var callRequestID: String?
    private var callTimeoutWorkItem: DispatchWorkItem?
    private var callStatusClearWorkItem: DispatchWorkItem?
    private var pingRequestID: String?
    private var pingStatusClearWorkItem: DispatchWorkItem?
    private var pendingCallNumber: String?
    private var pendingCallExpiryWorkItem: DispatchWorkItem?
    private var remoteFeatureStateReceived = false
    private var clipboardSendID: String?
    private var clipboardTimeoutWorkItem: DispatchWorkItem?
    private let notificationPresenter = NotificationPresenter()
    private var remoteNotificationCategories: [String: UNNotificationCategory] = [:]
    private var incomingFiles: [String: IncomingFileTransfer] = [:]
    private var outgoingFiles: [String: OutgoingFileTransfer] = [:]
    private var outgoingFileSources: [String: URL] = [:]
    private var fileSendQueue = FileSendQueue<URL>()
    private var outgoingFolder: OutgoingFolder?
    private var uploadTargets: [String: (rootID: String, path: String)] = [:]
    private var phoneFolderWindow: PhoneFolderBrowserWindowController?
    private var stateCancellable: AnyCancellable?
    private var folderFileOwners: [String: String] = [:]
    private var filePreparationCancellation: FileCancellationToken?
    private var fileTransferWindow: FileTransferWindowController?
    private var fileDropWindow: FileDropWindowController?
    private lazy var callOverlayWindow = CallOverlayWindowController(pairing: self)
    private var hiddenCallOverlayIdentity: String?
    private var audibleCallIdentity: String?
    private var cancelledTransferIDs = Set<String>()
    private var receivedFilesToReveal: [URL] = []
    private var revealReceivedFilesWorkItem: DispatchWorkItem?
    private var findDeviceSound: NSSound?
    private var lastSentBattery: LocalBatteryStatus?
    private let diagnostics = BridgeyDiagnostics()
    private let notificationHistoryStore: NotificationHistoryStore

    init(deviceID: String, deviceName: String, settings: BridgeySettings) {
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.settings = settings
        let notificationHistoryStore = NotificationHistoryStore()
        self.notificationHistoryStore = notificationHistoryStore
        if settings.notificationHistoryEnabled {
            notificationHistory = notificationHistoryStore.load()
        }
        identity = MacIdentity()
        let trustRegistry = MacTrustRegistry()
        self.trustRegistry = trustRegistry
        trustedDeviceIDs = trustRegistry.deviceIDs
        notificationPresenter.onDismiss = { [weak self] notificationID, deviceID in
            Task { @MainActor [weak self] in
                self?.dismissAndroidNotification(notificationID, deviceID: deviceID)
            }
        }
        notificationPresenter.onAction = { [weak self] notificationID, deviceID, actionToken, replyText in
            Task { @MainActor [weak self] in
                self?.performAndroidNotificationAction(
                    notificationID,
                    deviceID: deviceID,
                    actionToken: actionToken,
                    replyText: replyText
                )
            }
        }
        let remoteNotificationCategory = UNNotificationCategory(
            identifier: "bridgey.android.notification",
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        remoteNotificationCategories[remoteNotificationCategory.identifier] = remoteNotificationCategory
        UNUserNotificationCenter.current().setNotificationCategories(Set(remoteNotificationCategories.values))
        UNUserNotificationCenter.current().delegate = notificationPresenter
        refreshNotificationAuthorization()
        startListener()
        quickActions.available = { [weak self] in self?.isFeatureAvailable($0) == true }
        folderBrowser.available = { [weak self] in
            guard let self, case .connected = self.state else { return false }
            return self.isFeatureAvailable(.files) && self.isFeatureAvailable(.filesBrowse)
        }
        folderBrowser.send = { [weak self] in self?.sendBrowseRequest(kind: $0, payload: $1) == true }
        folderBrowser.upload = { [weak self] url, rootID, path in self?.queueFiles([url], uploadTarget: (rootID, path)) }
        stateCancellable = $state.sink { [weak self] state in
            guard case .connected = state else {
                self?.folderBrowser.reset()
                return
            }
        }
        quickActions.send = { [weak self] in self?.sendQuickPayload(kind: $0, payload: $1) == true }
        mediaController.allowed = { [weak self] in
            guard let self, case .connected = self.state else { return false }
            return self.isFeatureAvailable(.media)
        }
        mediaController.onState = { [weak self] in
            _ = self?.sendQuickPayload(kind: "media.state", payload: $0)
        }
        shortcuts.perform = { [weak self] action in
            switch action {
            case .clipboard: self?.sendClipboard()
            case .call: self?.sendCallFromClipboard()
            case .link: self?.quickActions.sendClipboardLink()
            case .ping: self?.sendPing()
            }
        }
        shortcuts.register()
        settingsCancellable = Publishers.CombineLatest3(
            settings.$globalFeatures,
            settings.$deviceFeatures,
            settings.$notificationHistoryEnabled
        )
            .dropFirst()
            .sink { [weak self] value in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if !self.featureEnabled(.battery) { self.remoteBattery = nil }
                    if !self.featureEnabled(.ping) { self.clearPingStatus() }
                    if !self.isFeatureAvailable(.links) { self.quickActions.reset() }
                    if !self.isFeatureAvailable(.filesBrowse) || !self.isFeatureAvailable(.files) { self.folderBrowser.reset() }
                    self.mediaController.reset()
                    if !self.featureEnabled(.clipboard) { self.clearClipboardSendStatus() }
                    if !self.featureEnabled(.notifications) { self.clearRemoteCall() }
                    if !self.featureEnabled(.calls) {
                        self.clearPendingCall()
                        self.clearCallStatus()
                    }
                    if value.2 {
                        self.notificationHistory = self.notificationHistoryStore.load()
                    } else {
                        self.clearNotificationHistory()
                    }
                    self.sendFeatureState()
                    self.mediaController.refresh()
                    self.publishLocalBattery(force: true)
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
        diagnostics.record(category: "pairing", event: "connection_started")
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
        remoteBattery = nil
        clearPingStatus()
        quickActions.reset()
        mediaController.reset()
        lastSentBattery = nil
        remoteFeatures = defaultRemoteFeatureState()
        remoteFeatureStateReceived = false
        clearRemoteCall()
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
        heartbeatWorkItem?.cancel()
        let current = session
        session = nil
        current?.send(PairingMessage(kind: "pairing.cancel", sessionId: current?.id ?? ""))
        current?.close()
        stopMacSound()
        androidRinging = false
        clearRemoteCall()
        clearPendingCall()
        clearCallStatus()
        clearClipboardSendStatus()
        clearPingStatus()
        quickActions.reset()
        mediaController.reset()
        lastSentBattery = nil
        remoteFeatures = defaultRemoteFeatureState()
        remoteFeatureStateReceived = false
        state = .idle
    }

    func dismiss() {
        connectionTimeoutWorkItem?.cancel()
        heartbeatWorkItem?.cancel()
        let current = session
        session = nil
        current?.close()
        stopMacSound()
        androidRinging = false
        remoteBattery = nil
        clearRemoteCall()
        clearPendingCall()
        clearCallStatus()
        clearClipboardSendStatus()
        clearPingStatus()
        quickActions.reset()
        mediaController.reset()
        lastSentBattery = nil
        remoteFeatures = defaultRemoteFeatureState()
        remoteFeatureStateReceived = false
        state = .idle
    }

    func forget(deviceID: String) {
        trustRegistry.remove(deviceID: deviceID)
        trustedDeviceIDs.remove(deviceID)
        settings.removeDevice(deviceID)
        if case let .connected(connectedID, _) = state, connectedID == deviceID { dismiss() }
        NSLog("PAIRING revoked peerId=%@", String(deviceID.prefix(8)))
    }

    func updateDeviceName(_ value: String) {
        deviceName = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
    }

    func sendCallFromClipboard() {
        guard let value = NSPasteboard.general.string(forType: .string) else {
            setTransientCallStatus("Copy a phone number first")
            return
        }
        sendCall(value)
    }

    func sendCallWhenConnected(_ value: String) {
        guard let number = normalizedPhoneNumber(value) else {
            setTransientCallStatus("Phone link does not contain a valid number")
            return
        }
        pendingCallNumber = number
        pendingCallExpiryWorkItem?.cancel()
        if flushPendingCallIfPossible() { return }
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        callStatus = "Waiting for Android connection…"
        let expiry = DispatchWorkItem { [weak self] in
            guard let self, self.pendingCallNumber == number else { return }
            self.pendingCallNumber = nil
            self.pendingCallExpiryWorkItem = nil
            self.setTransientCallStatus("Android did not connect in time")
        }
        pendingCallExpiryWorkItem = expiry
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: expiry)
    }

    func sendCall(_ value: String) {
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        guard isFeatureAvailable(.calls) else {
            setTransientCallStatus("Calls are turned off or require Bridgey alpha.5 on both devices")
            return
        }
        guard let number = normalizedPhoneNumber(value) else {
            setTransientCallStatus("Clipboard does not contain a valid phone number")
            return
        }
        guard let current = session, case .connected = state,
              let plaintext = try? JSONEncoder().encode(CallRequestPayload(number: number)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            setTransientCallStatus("Android is not connected")
            return
        }
        let messageID = UUID().uuidString.lowercased()
        callRequestID = messageID
        callStatus = "Sending call request…"
        current.send(PairingMessage(
            kind: "calls.request",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        callTimeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self] in
            guard self?.callRequestID == messageID else { return }
            self?.callRequestID = nil
            self?.setTransientCallStatus("Android did not confirm the call request")
        }
        callTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: timeout)
    }

    private func clearCallStatus() {
        callTimeoutWorkItem?.cancel()
        callTimeoutWorkItem = nil
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        callRequestID = nil
        callStatus = nil
    }

    @discardableResult
    private func flushPendingCallIfPossible() -> Bool {
        let isConnected: Bool
        if case .connected = state { isConnected = true } else { isConnected = false }
        guard let number = pendingCallNumber,
              pendingPhoneCallCanDispatch(
                isConnected: isConnected,
                featureStateReceived: remoteFeatureStateReceived
              ) else { return false }
        pendingCallNumber = nil
        pendingCallExpiryWorkItem?.cancel()
        pendingCallExpiryWorkItem = nil
        sendCall(number)
        return true
    }

    private func clearPendingCall() {
        pendingCallNumber = nil
        pendingCallExpiryWorkItem?.cancel()
        pendingCallExpiryWorkItem = nil
    }

    private func setTransientCallStatus(_ status: String) {
        callStatusClearWorkItem?.cancel()
        callStatus = status
        let work = DispatchWorkItem { [weak self] in
            self?.callStatus = nil
            self?.callStatusClearWorkItem = nil
        }
        callStatusClearWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
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
        guard clipboardTextFits(text) else {
            clipboardStatus = "Clipboard exceeds 32 KiB. Send large text or diagnostics as a file."
            return
        }
        let html = NSPasteboard.general.data(forType: .html)
            .flatMap { String(data: $0, encoding: .utf8) }
        let richContent = RichClipboardContent(text: text, html: html)
        let plaintext = richContent.flatMap { try? JSONEncoder().encode($0) } ?? Data(text.utf8)
        guard let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            clipboardStatus = "Encryption failed"
            return
        }
        let messageID = UUID().uuidString.lowercased()
        current.send(PairingMessage(
            kind: richContent == nil ? "clipboard.update" : "clipboard.rich",
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

    func sendPing() {
        guard let current = session, case .connected = state else {
            setTransientPingStatus("Android is not connected")
            return
        }
        guard isFeatureAvailable(.ping) else {
            setTransientPingStatus("Ping requires Bridgey 0.6 on both devices")
            return
        }
        guard let plaintext = try? JSONEncoder().encode(PingPayload(version: 1)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            setTransientPingStatus("Ping could not be encrypted")
            return
        }
        let messageID = UUID().uuidString.lowercased()
        pingRequestID = messageID
        pingStatusClearWorkItem?.cancel()
        pingStatus = "Pinging Android…"
        current.send(PairingMessage(
            kind: "ping.request",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        let timeout = DispatchWorkItem { [weak self] in
            guard self?.pingRequestID == messageID else { return }
            self?.pingRequestID = nil
            self?.setTransientPingStatus("Android did not acknowledge the ping")
        }
        pingStatusClearWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
    }

    private func receivePing(_ message: PairingMessage, in current: Session) throws {
        guard featureEnabled(.ping, current: current) else {
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
              let payload = try? JSONDecoder().decode(PingPayload.self, from: plaintext),
              payload.version == 1 else { throw PairingError.invalidMessage }
        NSSound(named: NSSound.Name("Glass"))?.play()
        setTransientPingStatus("Ping from \(current.peerName)")
        current.send(PairingMessage(kind: "ping.ack", sessionId: current.id, messageId: messageID))
        NSLog("PLUGIN ping received")
    }

    private func setTransientPingStatus(_ value: String) {
        pingStatusClearWorkItem?.cancel()
        pingStatus = value
        let work = DispatchWorkItem { [weak self] in
            self?.pingStatus = nil
            self?.pingStatusClearWorkItem = nil
        }
        pingStatusClearWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
    }

    private func clearPingStatus() {
        pingRequestID = nil
        pingStatusClearWorkItem?.cancel()
        pingStatusClearWorkItem = nil
        pingStatus = nil
    }

    private func sendQuickPayload(kind: String, payload: [String: Any]) -> Bool {
        guard let current = session, case .connected = state, let key = current.pairingKey,
              let data = try? JSONSerialization.data(withJSONObject: payload), data.count <= 32768,
              let encrypted = try? encrypt(data, key: key) else { return false }
        current.send(PairingMessage(kind: kind, sessionId: current.id,
            messageId: UUID().uuidString.lowercased(), nonce: encrypted.nonce, ciphertext: encrypted.ciphertext))
        return true
    }

    private func receiveQuickPayload(_ message: PairingMessage, current: Session) {
        guard session === current, case .connected = state, message.sessionId == current.id,
              let key = current.pairingKey, let id = message.messageId, current.acceptMessageID(id),
              let nonce = message.nonce, let ciphertext = message.ciphertext,
              let data = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: key), data.count <= 8192,
              let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              payload["version"] as? Int == 1 else { return }
        if message.kind == "quick.request" {
            guard let feature = payload["feature"] as? String, let sequence = payload["sequence"] as? Int,
                  current.quickSequence.accept(feature: feature, sequence: sequence) else { return }
        }
        if message.kind == "quick.request", payload["feature"] as? String == "media" {
            guard let requestID = payload["requestId"] as? String, UUID(uuidString: requestID) != nil else { return }
            let reply: (Bool) -> Void = { [weak self, weak current] accepted in
                guard let self, let current, self.session === current else { return }
                _ = self.sendQuickPayload(kind: "quick.result", payload: [
                    "version": 1, "requestId": requestID, "feature": "media", "accepted": accepted
                ])
            }
            guard isFeatureAvailable(.media), let action = payload["action"] as? String,
                  let value = payload["value"] as? String else { reply(false); return }
            mediaController.command(action: action, value: value, completion: reply)
        } else {
            quickActions.receive(message.kind, payload: payload)
        }
    }

    private func publishLocalBattery(force: Bool = false) {
        guard isFeatureAvailable(.battery),
              let current = session, case .connected = state,
              let status = currentMacBatteryStatus(),
              force || status != lastSentBattery,
              let plaintext = try? JSONEncoder().encode(BatteryPayload(
                level: status.level,
                isCharging: status.isCharging
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        lastSentBattery = status
        current.send(PairingMessage(
            kind: "battery.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN battery sent level=%d charging=%@", status.level, String(status.isCharging))
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
            let foldersAvailable = self.isFeatureAvailable(.folders)
            panel.title = foldersAvailable
                ? L10n.text("files.panel.titleFolders", fallback: "Send Files or Folders to Android")
                : L10n.text("files.panel.title", fallback: "Send Files to Android")
            panel.prompt = "Send"
            panel.canChooseFiles = true
            panel.canChooseDirectories = foldersAvailable
            panel.allowsMultipleSelection = true
            panel.level = .floating
            guard panel.runModal() == .OK, !panel.urls.isEmpty else { return }
            guard self.session != nil, case .connected = self.state, self.isFeatureAvailable(.files) else { return }
            self.queueFiles(panel.urls)
        }
    }

    @discardableResult
    func sendDroppedFiles(_ urls: [URL]) -> Bool {
        guard session != nil, case .connected = state else {
            fileTransferStatus = "Not connected — file was not sent"
            return false
        }
        guard isFeatureAvailable(.files) else {
            fileTransferStatus = "File transfer is turned off on one of your devices"
            return false
        }
        let foldersAvailable = isFeatureAvailable(.folders)
        let files = urls.filter { url in
            guard url.isFileURL, let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey]) else {
                return false
            }
            return values.isRegularFile == true || (foldersAvailable && values.isDirectory == true)
        }
        guard !files.isEmpty else {
            fileTransferStatus = "Drop a file, not a folder"
            return false
        }
        queueFiles(files)
        if files.count < urls.count {
            fileTransferStatus = L10n.text("drop.foldersSkipped", fallback: "Folders were skipped. Only files are sent.")
        }
        return true
    }

    func exportDiagnostics() {
        let stateName: String
        switch state {
        case .idle: stateName = "idle"
        case .connecting: stateName = "connecting"
        case .verification: stateName = "verification"
        case .connected: stateName = "connected"
        case .failed: stateName = "failed"
        }
        let localFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
            ($0, settings.isEnabled($0, for: nil))
        })
        guard let report = try? diagnostics.report(
            connectionState: stateName,
            transfers: Array(fileTransfers.values),
            localFeatures: localFeatures,
            remoteFeatures: remoteFeatures
        ) else { return }
        let panel = NSSavePanel()
        panel.title = "Export Bridgey Diagnostics"
        panel.nameFieldStringValue = "Bridgey-Diagnostics.json"
        panel.allowedContentTypes = [.json]
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        try? report.write(to: url, options: .atomic)
    }

    /// Adds files to the outgoing queue. Files are offered one after another,
    /// each as its own transfer, so a failure or cancellation affects only that file.
    private func queueFiles(_ urls: [URL], uploadTarget: (rootID: String, path: String)? = nil) {
        guard session != nil, case .connected = state, isFeatureAvailable(.files) else { return }
        let queuedAt = Date()
        let status = L10n.text("files.queued", fallback: "Queued")
        for (index, url) in urls.enumerated() {
            let transferID = UUID().uuidString.lowercased()
            outgoingFileSources[transferID] = url
            fileTransfers[transferID] = FileTransferRow(
                id: transferID,
                name: url.lastPathComponent,
                status: status,
                active: true,
                startedAt: queuedAt.addingTimeInterval(Double(index) / 1_000),
                queued: true
            )
            if let uploadTarget { uploadTargets[transferID] = uploadTarget }
            fileSendQueue.enqueue(id: transferID, item: url)
        }
        pruneTransferHistory()
        beginFileTransferUI()
        startNextQueuedFile()
    }

    private func startNextQueuedFile() {
        guard session != nil, case .connected = state else { return }
        guard isFeatureAvailable(.files) else {
            stopFileQueue(status: "File transfer is turned off on one of your devices")
            return
        }
        guard let next = fileSendQueue.startNext() else { return }
        if (try? next.item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            startFolder(next.item, folderID: next.id)
        } else {
            prepareFile(next.item, transferID: next.id)
        }
    }

    private func finishQueuedFile(_ transferID: String) {
        if let folderID = folderFileOwners.removeValue(forKey: transferID) {
            if let folder = outgoingFolder, folder.id == folderID, folder.currentTransferID == transferID {
                folder.currentTransferID = nil
                if folder.completedTransferID != transferID, let current = session {
                    // The file failed here: tell Android to drop it so the next file can start.
                    markTransferCancelled(transferID)
                    current.send(PairingMessage(kind: "files.cancel", sessionId: current.id, transferId: transferID))
                }
                sendNextFolderFile(folder)
            }
            return
        }
        if fileSendQueue.finish(id: transferID) { startNextQueuedFile() }
    }

    private func isCurrentOutgoingFile(_ transferID: String) -> Bool {
        fileSendQueue.currentID == transferID || outgoingFolder?.currentTransferID == transferID
    }

    /// Ends every file that has not started yet with the given status. They remain retryable.
    private func stopFileQueue(status: String) {
        fileSendQueue.removeAllPending().forEach { markFileTransferFinished(id: $0, status: status) }
    }

    private func prepareFile(_ url: URL, transferID: String, folderEntry: (folderID: String, entry: Int, size: Int64)? = nil) {
        guard let current = session, case .connected = state else { return }
        let expectedSessionID = current.id
        let preparationCancellation = FileCancellationToken()
        filePreparationCancellation = preparationCancellation
        fileTransferActive = true
        fileTransferStatus = "Preparing \(url.lastPathComponent)…"
        updateFileTransfer(id: transferID, name: url.lastPathComponent, status: fileTransferStatus!, active: true)
        diagnostics.record(category: "transfer", event: "send_started")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let transfer = try OutgoingFileTransfer(url: url, transferID: transferID, cancellation: preparationCancellation)
                DispatchQueue.main.async {
                    guard let self, self.isCurrentOutgoingFile(transferID) else { return }
                    self.filePreparationCancellation = nil
                    if let folderEntry, transfer.offer.size != folderEntry.size {
                        self.markFileTransferFinished(id: transferID, status: L10n.text(
                            "folders.fileChanged",
                            fallback: "A file changed while the folder was being sent"
                        ))
                        self.finishQueuedFile(transferID)
                        return
                    }
                    guard let current = self.session, current.id == expectedSessionID,
                          case .connected = self.state else {
                        self.markFileTransferFinished(id: transferID, status: "Transfer interrupted — reconnect to retry")
                        self.finishQueuedFile(transferID)
                        return
                    }
                    do {
                        var offer = transfer.offer
                        offer.folderId = folderEntry?.folderID
                        offer.entry = folderEntry?.entry
                        offer.browseRootId = self.uploadTargets[transferID]?.rootID
                        offer.browsePath = self.uploadTargets[transferID]?.path
                        let payload = try JSONEncoder().encode(offer)
                        let encrypted = try encrypt(payload, key: current.pairingKey!)
                        self.outgoingFiles[transfer.transferID] = transfer
                        if folderEntry == nil { self.outgoingFileSources[transfer.transferID] = url }
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
                            nonce: encrypted.nonce,
                            ciphertext: encrypted.ciphertext,
                            transferId: transfer.transferID
                        ))
                        self.fileTransferStatus = "Waiting for Android…"
                    } catch {
                        self.fileTransferStatus = "Could not prepare the selected file"
                        self.markFileTransferFinished(id: transferID, status: self.fileTransferStatus!)
                        self.finishQueuedFile(transferID)
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    guard let self, self.isCurrentOutgoingFile(transferID) else { return }
                    self.fileTransferStatus = "Could not read the selected file"
                    self.filePreparationCancellation = nil
                    self.markFileTransferFinished(id: transferID, status: self.fileTransferStatus!)
                    self.finishQueuedFile(transferID)
                }
            }
        }
    }

    private func sendBrowseRequest(kind: String, payload: Data) -> Bool {
        guard let current = session, case .connected = state, let key = current.pairingKey,
              let sealed = try? encrypt(payload, key: key) else { return false }
        current.send(PairingMessage(
            kind: kind,
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: sealed.nonce,
            ciphertext: sealed.ciphertext
        ))
        return true
    }

    private func receiveBrowseAnswer(_ message: PairingMessage, in current: Session) throws {
        guard case .connected = state, message.sessionId == current.id,
              let messageID = message.messageId, current.acceptMessageID(messageID) else { return }
        guard let nonce = message.nonce, let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              plaintext.count <= FolderRules.maxPayloadBytes else { throw PairingError.invalidMessage }
        guard message.kind == "browse.error" else {
            folderBrowser.receive(kind: message.kind, payload: plaintext)
            return
        }
        guard let error = try? JSONDecoder().decode(BrowseErrorPayload.self, from: plaintext) else { return }
        if folderBrowser.receive(error: error) { return }
        // An upload the phone refused: it never sent files.accept, so end the transfer here.
        guard error.version == 1, uploadTargets[error.requestId] != nil,
              let transfer = outgoingFiles.removeValue(forKey: error.requestId) else { return }
        transfer.cancel()
        markTransferCancelled(error.requestId)
        let status = PhoneFolderBrowser.text(forError: error.code)
        fileTransferStatus = status
        markFileTransferFinished(id: error.requestId, status: status)
        finishQueuedFile(error.requestId)
    }

    private static var foldersOffText: String {
        L10n.text("folders.off", fallback: "Folder transfer is turned off on one of your devices")
    }

    /// Scans a queued folder in the background, then offers it with its manifest.
    private func startFolder(_ url: URL, folderID: String) {
        guard let current = session, case .connected = state, isFeatureAvailable(.folders) else {
            markFileTransferFinished(id: folderID, status: Self.foldersOffText)
            finishQueuedFile(folderID)
            return
        }
        let expectedSessionID = current.id
        let cancellation = FileCancellationToken()
        filePreparationCancellation = cancellation
        fileTransferStatus = L10n.text("folders.preparing", fallback: "Preparing folder…")
        updateFileTransfer(id: folderID, name: url.lastPathComponent, status: fileTransferStatus!, active: true)
        diagnostics.record(category: "transfer", event: "folder_send_started")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try FolderScanner.scan(url, isCancelled: { cancellation.isCancelled }) }
            DispatchQueue.main.async {
                guard let self, self.fileSendQueue.currentID == folderID else { return }
                self.filePreparationCancellation = nil
                switch result {
                case let .success(manifest):
                    guard let current = self.session, current.id == expectedSessionID, case .connected = self.state,
                          let key = current.pairingKey else {
                        self.markFileTransferFinished(id: folderID, status: "File transfer interrupted")
                        self.finishQueuedFile(folderID)
                        return
                    }
                    self.offerFolder(manifest, folderID: folderID, through: current, key: key)
                case let .failure(error):
                    let status: String
                    switch error as? FolderScanError {
                    case .empty: status = L10n.text("folders.empty", fallback: "The folder has no files to send")
                    case .tooLarge: status = L10n.text("folders.tooLarge", fallback: "The folder is too large to send")
                    default: status = L10n.text("folders.unreadable", fallback: "Could not read the selected folder")
                    }
                    self.fileTransferStatus = status
                    self.markFileTransferFinished(id: folderID, status: status)
                    self.finishQueuedFile(folderID)
                }
            }
        }
    }

    private func offerFolder(_ manifest: OutgoingFolderManifest, folderID: String, through current: Session, key: Data) {
        let pages = manifest.pages()
        let offer = FolderOfferPayload(
            folderId: folderID,
            name: manifest.name,
            fileCount: manifest.fileCount,
            folderCount: manifest.folderCount,
            totalSize: manifest.totalSize,
            pageCount: pages.count
        )
        guard let sealedOffer = try? encrypt(JSONEncoder().encode(offer), key: key),
              let sealedPages = try? pages.enumerated().map({ index, entries in
                  try encrypt(JSONEncoder().encode(FolderManifestPagePayload(folderId: folderID, page: index, entries: entries)), key: key)
              }) else {
            markFileTransferFinished(id: folderID, status: L10n.text("folders.unreadable", fallback: "Could not read the selected folder"))
            finishQueuedFile(folderID)
            return
        }
        let folder = OutgoingFolder(id: folderID, manifest: manifest)
        outgoingFolder = folder
        current.send(PairingMessage(
            kind: "folders.offer",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: sealedOffer.nonce,
            ciphertext: sealedOffer.ciphertext,
            transferId: folderID
        ))
        for page in sealedPages {
            current.send(PairingMessage(
                kind: "folders.manifest",
                sessionId: current.id,
                messageId: UUID().uuidString.lowercased(),
                nonce: page.nonce,
                ciphertext: page.ciphertext
            ))
        }
        fileTransferStatus = "Waiting for Android…"
        updateFileTransfer(id: folderID, name: manifest.name, status: fileTransferStatus!, active: true)
        scheduleFolderTimeout(folder, after: 15, status: L10n.text("folders.noAnswer", fallback: "Android did not answer"))
    }

    private func receiveFolderAccept(_ message: PairingMessage, in current: Session) throws {
        guard let payload = try decryptFolderPayload(FolderAcceptPayload.self, message, in: current),
              let folder = outgoingFolder, folder.id == payload.folderId, !folder.accepted else { return }
        folder.accepted = true
        folder.timeout?.cancel()
        sendNextFolderFile(folder)
    }

    private func receiveFolderRejected(_ message: PairingMessage, in current: Session) throws {
        guard message.sessionId == current.id, let folder = outgoingFolder else { return }
        let code: String
        if message.ciphertext == nil {
            guard message.transferId == folder.id else { return }
            code = "policy"
        } else {
            guard let payload = try decryptFolderPayload(FolderRejectedPayload.self, message, in: current),
                  payload.folderId == folder.id else { return }
            code = payload.code
        }
        let status: String
        switch code {
        case "policy": status = L10n.text("folders.rejected.policy", fallback: "Folder transfer is turned off on Android")
        case "unsupported": status = L10n.text("folders.rejected.unsupported", fallback: "Receiving folders requires Android 10 or newer")
        case "busy": status = L10n.text("folders.rejected.busy", fallback: "Android is already receiving a folder")
        case "limit_exceeded": status = L10n.text("folders.tooLarge", fallback: "The folder is too large to send")
        case "insufficient_space": status = L10n.text("folders.rejected.space", fallback: "Not enough free space on Android")
        default: status = L10n.text("folders.rejected.other", fallback: "Android could not save the folder")
        }
        fileTransferStatus = status
        endOutgoingFolder(folder, status: status, notifyPeer: false)
    }

    private func sendNextFolderFile(_ folder: OutgoingFolder) {
        guard outgoingFolder === folder, session != nil, case .connected = state else { return }
        guard isFeatureAvailable(.files), isFeatureAvailable(.folders) else {
            endOutgoingFolder(folder, status: Self.foldersOffText, notifyPeer: true)
            return
        }
        let entries = folder.manifest.entries
        while folder.nextEntry < entries.count, entries[folder.nextEntry].isDirectory { folder.nextEntry += 1 }
        guard folder.nextEntry < entries.count, let url = folder.manifest.fileURLs[folder.nextEntry] else {
            completeOutgoingFolder(folder)
            return
        }
        let entry = folder.nextEntry
        folder.nextEntry += 1
        folder.startedFiles += 1
        let transferID = UUID().uuidString.lowercased()
        folder.currentTransferID = transferID
        folderFileOwners[transferID] = folder.id
        prepareFile(url, transferID: transferID, folderEntry: (folder.id, entry, entries[entry].size))
    }

    private func completeOutgoingFolder(_ folder: OutgoingFolder) {
        guard let current = session, !folder.completing else { return }
        folder.completing = true
        sendFolderPayload(FolderCompletePayload(folderId: folder.id, sentFiles: folder.sentFiles), kind: "folders.complete", through: current)
        scheduleFolderTimeout(folder, after: 30, status: L10n.text("folders.noAnswer", fallback: "Android did not answer"))
    }

    private func receiveFolderResult(_ message: PairingMessage, in current: Session) throws {
        guard let payload = try decryptFolderPayload(FolderResultPayload.self, message, in: current),
              let folder = outgoingFolder, folder.id == payload.folderId, folder.completing else { return }
        folder.timeout?.cancel()
        outgoingFolder = nil
        let total = folder.manifest.fileCount
        var status = payload.savedFiles == total
            ? String(format: L10n.text("folders.saved", fallback: "%@ saved on Android"), folder.manifest.name)
            : String(format: L10n.text("folders.savedPartly", fallback: "Saved %d of %d files on Android"), payload.savedFiles, total)
        if folder.manifest.skippedItems > 0 {
            status += " · " + String(format: L10n.text("folders.skipped", fallback: "%d items skipped"), folder.manifest.skippedItems)
        }
        if payload.savedFiles == total { outgoingFileSources.removeValue(forKey: folder.id) }
        fileTransferStatus = status
        markFileTransferFinished(id: folder.id, status: status)
        diagnostics.record(category: "transfer", event: "folder_send_completed", outcome: payload.savedFiles == total ? "ok" : "partial")
        NSLog("PLUGIN folder sent files=%d", payload.savedFiles)
        finishQueuedFile(folder.id)
    }

    /// Ends the outgoing folder. Files already saved on Android stay; the file in progress is cancelled.
    private func endOutgoingFolder(_ folder: OutgoingFolder, status: String, notifyPeer: Bool) {
        guard outgoingFolder === folder else { return }
        outgoingFolder = nil
        folder.timeout?.cancel()
        if notifyPeer, let current = session {
            current.send(PairingMessage(kind: "folders.cancel", sessionId: current.id, transferId: folder.id))
        }
        if let transferID = folder.currentTransferID {
            folderFileOwners.removeValue(forKey: transferID)
            markTransferCancelled(transferID)
            filePreparationCancellation?.cancel()
            filePreparationCancellation = nil
            if notifyPeer, let current = session {
                current.send(PairingMessage(kind: "files.cancel", sessionId: current.id, transferId: transferID))
            }
            outgoingFiles.removeValue(forKey: transferID)?.cancel()
        }
        markFileTransferFinished(id: folder.id, status: status)
        finishQueuedFile(folder.id)
    }

    private func updateFolderRow(fileStatus: String) {
        guard let folder = outgoingFolder, let row = fileTransfers[folder.id] else { return }
        fileTransfers[folder.id] = FileTransferRow(
            id: folder.id,
            name: row.name,
            status: String(
                format: L10n.text("folders.progress", fallback: "%@ (file %d of %d)"),
                fileStatus,
                folder.startedFiles,
                folder.manifest.fileCount
            ),
            active: true,
            startedAt: row.startedAt
        )
    }

    private func scheduleFolderTimeout(_ folder: OutgoingFolder, after seconds: Double, status: String) {
        folder.timeout?.cancel()
        let work = DispatchWorkItem { [weak self, weak folder] in
            guard let self, let folder, self.outgoingFolder === folder else { return }
            self.fileTransferStatus = status
            self.endOutgoingFolder(folder, status: status, notifyPeer: true)
        }
        folder.timeout = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func decryptFolderPayload<Payload: Decodable>(
        _ type: Payload.Type,
        _ message: PairingMessage,
        in current: Session
    ) throws -> Payload? {
        guard case .connected = state, message.sessionId == current.id,
              let messageID = message.messageId, current.acceptMessageID(messageID) else { return nil }
        guard let nonce = message.nonce, let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              plaintext.count <= FolderRules.maxPayloadBytes,
              let payload = try? JSONDecoder().decode(Payload.self, from: plaintext) else {
            throw PairingError.invalidMessage
        }
        return payload
    }

    private func sendFolderPayload<Payload: Encodable>(_ payload: Payload, kind: String, through current: Session) {
        guard let key = current.pairingKey,
              let sealed = try? encrypt(JSONEncoder().encode(payload), key: key) else { return }
        current.send(PairingMessage(
            kind: kind,
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: sealed.nonce,
            ciphertext: sealed.ciphertext
        ))
    }

    func cancelFileTransfer() {
        filePreparationCancellation?.cancel()
        filePreparationCancellation = nil
        fileSendQueue.removeAll().forEach { markFileTransferFinished(id: $0, status: "Transfer cancelled") }
        if let folder = outgoingFolder { endOutgoingFolder(folder, status: "Transfer cancelled", notifyPeer: true) }
        let transferIDs = Set(incomingFiles.keys).union(outgoingFiles.keys)
        transferIDs.forEach { transferID in
            markTransferCancelled(transferID)
            session?.send(PairingMessage(kind: "files.cancel", sessionId: session?.id ?? "", transferId: transferID))
        }
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        outgoingFiles.values.forEach { $0.cancel() }
        outgoingFiles.removeAll()
        transferIDs.forEach { markFileTransferFinished(id: $0, status: "Transfer cancelled") }
        fileTransferActive = false
        fileTransferStatus = "Transfer cancelled"
    }

    func cancelFileTransfer(id transferID: String) {
        if let folder = outgoingFolder, folder.id == transferID {
            endOutgoingFolder(folder, status: "Transfer cancelled", notifyPeer: true)
            fileTransferStatus = "Transfer cancelled"
            return
        }
        if fileSendQueue.removePending(id: transferID) {
            markFileTransferFinished(id: transferID, status: "Transfer cancelled")
            return
        }
        if fileSendQueue.currentID == transferID, outgoingFiles[transferID] == nil {
            filePreparationCancellation?.cancel()
            filePreparationCancellation = nil
        }
        markTransferCancelled(transferID)
        session?.send(PairingMessage(kind: "files.cancel", sessionId: session?.id ?? "", transferId: transferID))
        incomingFiles.removeValue(forKey: transferID)?.cancel()
        outgoingFiles.removeValue(forKey: transferID)?.cancel()
        markFileTransferFinished(id: transferID, status: "Transfer cancelled")
        fileTransferStatus = "Transfer cancelled"
        finishQueuedFile(transferID)
    }

    func retryFileTransfer(id transferID: String) {
        guard let url = outgoingFileSources[transferID] else { return }
        guard session != nil, case .connected = state else {
            markFileTransferFinished(id: transferID, status: "Reconnect before retrying")
            return
        }
        guard isFeatureAvailable(.files) else {
            markFileTransferFinished(id: transferID, status: "File transfer is turned off on one of your devices")
            return
        }
        fileTransfers.removeValue(forKey: transferID)
        outgoingFileSources.removeValue(forKey: transferID)
        let uploadTarget = uploadTargets.removeValue(forKey: transferID)
        diagnostics.record(category: "transfer", event: "retry_started")
        queueFiles([url], uploadTarget: uploadTarget)
    }

    func clearTransferHistory() {
        let inactiveIDs = fileTransfers.values.filter { !$0.active }.map(\.id)
        inactiveIDs.forEach {
            outgoingFileSources.removeValue(forKey: $0)
            uploadTargets.removeValue(forKey: $0)
        }
        fileTransfers = fileTransfers.filter { $0.value.active }
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    func showFileTransferWindow() {
        if fileTransferWindow == nil {
            fileTransferWindow = FileTransferWindowController(pairing: self)
        }
        fileTransferWindow?.show()
    }

    func showPhoneFolderBrowser() {
        if phoneFolderWindow == nil {
            phoneFolderWindow = PhoneFolderBrowserWindowController(browser: folderBrowser)
        }
        phoneFolderWindow?.show()
    }

    func showFileDropWindow() {
        if fileDropWindow == nil {
            fileDropWindow = FileDropWindowController(pairing: self)
        }
        fileDropWindow?.show()
    }

    /// Shows the transfer window for a new transfer. It is not brought forward again while it is
    /// already open, so a queue of files does not take focus from other apps once per file.
    private func beginFileTransferUI() {
        fileTransferActive = true
        if fileTransferWindow?.window?.isVisible != true { showFileTransferWindow() }
    }

    private func startListener() {
        diagnostics.record(category: "transport", event: "listener_started")
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
        remoteBattery = nil
        clearPingStatus()
        quickActions.reset()
        mediaController.reset()
        lastSentBattery = nil
        remoteFeatures = defaultRemoteFeatureState()
        remoteFeatureStateReceived = false
        clearRemoteCall()
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
            self.heartbeatWorkItem?.cancel()
            self.cancelIncomingFiles()
            self.clearClipboardSendStatus()
            self.clearCallStatus()
            if !self.outgoingFiles.isEmpty {
                self.outgoingFiles.values.forEach { $0.cancel() }
                self.outgoingFiles.removeAll()
                self.fileTransferStatus = "File transfer interrupted"
            }
            if case .connected = self.state {
                self.session = nil
                self.remoteBattery = nil
                self.clearPingStatus()
                self.quickActions.reset()
                self.mediaController.reset()
                self.lastSentBattery = nil
                self.clearRemoteCall()
                self.remoteFeatures = defaultRemoteFeatureState()
                self.remoteFeatureStateReceived = false
                self.state = .idle
                NSLog("TRANSPORT disconnected")
                self.diagnostics.record(category: "transport", event: "disconnected", outcome: "reconnecting")
                self.scheduleReconnect()
            } else {
                self.state = .failed("Pairing connection lost")
            }
        }
        current.connection.stateUpdateHandler = { [weak self, weak current] newState in
            DispatchQueue.main.async {
                guard let self, let current else { return }
                switch newState {
                case .ready:
                    NSLog("TRANSPORT connected")
                    current.receive()
                    onReady()
                case .failed, .cancelled:
                    current.onFailure?()
                case let .waiting(error) where localNetworkPermissionDenied(error):
                    guard self.session === current else { return }
                    self.connectionTimeoutWorkItem?.cancel()
                    self.heartbeatWorkItem?.cancel()
                    self.session = nil
                    self.clearPingStatus()
                    self.quickActions.reset()
                    self.mediaController.reset()
                    self.lastSentBattery = nil
                    self.clearRemoteCall()
                    self.remoteFeatureStateReceived = false
                    current.close()
                    self.state = .failed("Local Network access is off. Enable Bridgey in System Settings → Privacy & Security → Local Network.")
                    self.diagnostics.record(category: "transport", event: "local_network_denied", outcome: "permission_required")
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
            case "quick.request", "quick.result":
                receiveQuickPayload(message, current: current)
            case "heartbeat.ping":
                guard message.sessionId == current.id, case .connected = state else { return }
                current.heartbeatSupported = true
                current.send(PairingMessage(kind: "heartbeat.pong", sessionId: current.id, messageId: message.messageId))
            case "heartbeat.pong":
                guard message.sessionId == current.id, case .connected = state else { return }
                current.heartbeatSupported = true
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
                      BridgeyFeature.allCases.filter(featureEnabledByLegacyPeer).allSatisfy({ payload.features[$0.rawValue] != nil }) else {
                    throw PairingError.invalidMessage
                }
                remoteFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
                    ($0, payload.features[$0.rawValue] ?? false)
                })
                remoteFeatureStateReceived = true
                if !isFeatureAvailable(.links) { quickActions.reset() }
                if !isFeatureAvailable(.filesBrowse) || !isFeatureAvailable(.files) { folderBrowser.reset() }
                mediaController.reset()
                mediaController.refresh()
                if remoteFeatures[.battery] == false { remoteBattery = nil }
                if remoteFeatures[.ping] == false { clearPingStatus() }
                if remoteFeatures[.clipboard] == false { clearClipboardSendStatus() }
                if remoteFeatures[.notifications] == false { clearRemoteCall() }
                if remoteFeatures[.calls] == false { clearCallStatus() }
                if remoteFeatures[.folders] == false, let folder = outgoingFolder {
                    endOutgoingFolder(folder, status: Self.foldersOffText, notifyPeer: true)
                }
                if remoteFeatures[.files] == false && fileTransferActive {
                    cancelFileTransfer()
                    fileTransferStatus = nil
                }
                if remoteFeatures[.findDevice] == false {
                    stopMacSound()
                    androidRinging = false
                }
                flushPendingCallIfPossible()
                publishLocalBattery(force: true)
            case "ping.request":
                try receivePing(message, in: current)
            case "ping.ack":
                guard message.sessionId == current.id,
                      message.messageId == pingRequestID else { return }
                pingRequestID = nil
                setTransientPingStatus("Ping delivered")
            case "clipboard.update", "clipboard.rich":
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
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!) else {
                    throw PairingError.invalidMessage
                }
                let text: String
                let html: String?
                if message.kind == "clipboard.rich" {
                    guard let payload = try? JSONDecoder().decode(RichClipboardContent.self, from: plaintext),
                          let content = payload.validated() else { throw PairingError.invalidMessage }
                    text = content.text
                    html = content.html
                } else {
                    guard let decoded = String(data: plaintext, encoding: .utf8),
                          clipboardTextFits(decoded) else { throw PairingError.invalidMessage }
                    text = decoded
                    html = nil
                }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                if let html { NSPasteboard.general.setData(Data(html.utf8), forType: .html) }
                diagnostics.record(category: "clipboard", event: html == nil ? "text_received" : "rich_received")
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
                      payload.callType == nil || normalizedRemoteCallType(payload.callType) != nil,
                      (!payload.title.isEmpty || !payload.text.isEmpty) else {
                    throw PairingError.invalidMessage
                }
                recordNotificationHistory(payload, deviceID: current.remoteDeviceID)
                updateRemoteCall(payload, deviceID: current.remoteDeviceID)
                if shouldUseSystemNotification(callType: payload.callType) {
                    postNotification(payload, deviceID: current.remoteDeviceID)
                }
            case "notifications.remove":
                guard featureEnabled(.notifications, current: current),
                      let reference = try receiveNotificationReference(message, in: current) else { return }
                removeRemoteNotification(reference.notificationId, deviceID: current.remoteDeviceID)
            case "calls.started", "calls.confirmation_required", "calls.rejected":
                guard message.messageId == callRequestID else { return }
                callTimeoutWorkItem?.cancel()
                callTimeoutWorkItem = nil
                callRequestID = nil
                switch message.kind {
                case "calls.started": setTransientCallStatus("Call started on Android")
                case "calls.confirmation_required": setTransientCallStatus("Confirm the call from the Android notification")
                default: setTransientCallStatus("Android rejected the call request")
                }
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
                    if let row = fileTransfers[completion.transferId] {
                        fileTransfers[completion.transferId] = FileTransferRow(
                            id: row.id,
                            name: row.name,
                            status: row.status,
                            active: false,
                            startedAt: row.startedAt,
                            localURL: destination
                        )
                    }
                    fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                    current.send(PairingMessage(
                        kind: "files.complete.ack",
                        sessionId: current.id,
                        transferId: completion.transferId
                    ))
                    FinderMetadata.finishReceivedFile(destination, deviceName: current.peerName)
                    revealReceivedFile(destination)
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
                                self.markFileTransferFinished(id: transferID, status: "Transfer cancelled")
                                self.finishQueuedFile(transferID)
                                return
                            } else {
                                self.fileTransferStatus = "File transfer failed"
                            }
                            self.fileTransferActive = self.fileTransfers.values.contains(where: { $0.active })
                            self.updateFileTransfer(id: transferID, name: transfer.displayName, status: self.fileTransferStatus!, active: false)
                            self.finishQueuedFile(transferID)
                        }
                    }
                )
            case "files.rejected":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                transfer.cancel()
                markTransferCancelled(transferID)
                markFileTransferFinished(id: transferID, status: "File transfer is turned off on Android")
                filePreparationCancellation = nil
                fileTransferStatus = "File transfer is turned off on Android"
                stopFileQueue(status: "File transfer is turned off on Android")
                if let folder = outgoingFolder, folderFileOwners[transferID] == folder.id {
                    endOutgoingFolder(folder, status: "File transfer is turned off on Android", notifyPeer: true)
                }
                finishQueuedFile(transferID)
            case "files.complete.ack":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                outgoingFileSources.removeValue(forKey: transferID)
                if let folder = outgoingFolder, folderFileOwners[transferID] == folder.id {
                    folder.sentFiles += 1
                    folder.completedTransferID = transferID
                }
                fileTransferStatus = "\(transfer.displayName) saved on Android"
                updateFileTransfer(id: transferID, name: transfer.displayName, status: fileTransferStatus!, active: false)
                fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                filePreparationCancellation = nil
                NSLog("PLUGIN file sent name=%@", transfer.displayName)
                finishQueuedFile(transferID)
            case "files.cancel":
                guard let transferID = message.transferId else { return }
                markTransferCancelled(transferID)
                incomingFiles.removeValue(forKey: transferID)?.cancel()
                outgoingFiles.removeValue(forKey: transferID)?.cancel()
                markFileTransferFinished(id: transferID, status: "Transfer cancelled by Android")
                fileTransferStatus = "Transfer cancelled by Android"
                current.send(PairingMessage(kind: "files.cancel.ack", sessionId: current.id, transferId: transferID))
                NSLog("PLUGIN file cancellation received transfer=%@", String(transferID.prefix(8)))
                finishQueuedFile(transferID)
            case "files.cancel.ack":
                if let transferID = message.transferId {
                    markFileTransferFinished(id: transferID, status: "Transfer cancelled")
                }
            case "browse.roots.result", "browse.list.result", "browse.download.result", "browse.error":
                try receiveBrowseAnswer(message, in: current)
            case "folders.accept":
                try receiveFolderAccept(message, in: current)
            case "folders.rejected":
                try receiveFolderRejected(message, in: current)
            case "folders.result":
                try receiveFolderResult(message, in: current)
            case "folders.cancel":
                guard message.sessionId == current.id, let folderID = message.transferId else { return }
                if let folder = outgoingFolder, folder.id == folderID {
                    endOutgoingFolder(folder, status: "Transfer cancelled by Android", notifyPeer: false)
                }
                current.send(PairingMessage(kind: "folders.cancel.ack", sessionId: current.id, transferId: folderID))
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
            diagnostics.record(category: "protocol", event: "message_rejected", outcome: "session_closed")
        }
    }

    private func completeIfConfirmed(_ current: Session) {
        if current.localConfirmed && current.remoteConfirmed {
            connectionTimeoutWorkItem?.cancel()
            saveTrust(current)
            state = .connected(deviceID: current.remoteDeviceID, peerName: current.peerName)
            diagnostics.record(category: "pairing", event: "connected")
            reconnectAttempt = 0
            reconnectWorkItem?.cancel()
            sendFeatureState()
            publishLocalBattery(force: true)
            scheduleHeartbeat(for: current)
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
        let hadActiveTransfers = fileTransfers.values.contains(where: { $0.active })
        filePreparationCancellation?.cancel()
        outgoingFolder?.timeout?.cancel()
        outgoingFolder = nil
        folderFileOwners.removeAll()
        fileSendQueue.removeAll().forEach {
            markFileTransferFinished(id: $0, status: "Transfer interrupted — reconnect to retry")
        }
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        outgoingFiles.values.forEach { $0.cancel() }
        outgoingFiles.removeAll()
        fileTransfers = recoverInterruptedTransfers(fileTransfers)
        fileTransferActive = false
        filePreparationCancellation = nil
        if fileTransferStatus?.hasPrefix("Receiving ") == true {
            fileTransferStatus = "File transfer interrupted"
        }
        if hadActiveTransfers {
            diagnostics.record(category: "transfer", event: "interrupted", outcome: "retry_available")
        }
    }

    /// Shows a received file in Finder. Files that arrive in quick succession,
    /// such as a queue sent from Android, are selected together in one window
    /// instead of bringing Finder forward once per file.
    private func revealReceivedFile(_ url: URL) {
        receivedFilesToReveal.append(url)
        revealReceivedFilesWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let urls = Array(self.receivedFilesToReveal.suffix(maximumTransferHistory))
            self.receivedFilesToReveal.removeAll()
            self.revealReceivedFilesWorkItem = nil
            NSWorkspace.shared.activateFileViewerSelecting(urls)
        }
        revealReceivedFilesWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    private func markTransferCancelled(_ transferID: String) {
        cancelledTransferIDs.insert(transferID)
        if cancelledTransferIDs.count > 64, let first = cancelledTransferIDs.first {
            cancelledTransferIDs.remove(first)
        }
    }

    private func updateFileTransfer(id: String, name: String, status: String, active: Bool) {
        if folderFileOwners[id] != nil {
            updateFolderRow(fileStatus: status)
            return
        }
        let previous = fileTransfers[id]
        fileTransfers[id] = FileTransferRow(
            id: id,
            name: name,
            status: status,
            active: active,
            startedAt: previous?.startedAt ?? Date(),
            retryable: !active && outgoingFileSources[id] != nil
        )
        pruneTransferHistory()
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    private func markFileTransferFinished(id: String, status: String) {
        if folderFileOwners[id] != nil {
            updateFolderRow(fileStatus: status)
            return
        }
        guard let transfer = fileTransfers[id] else { return }
        fileTransfers[id] = FileTransferRow(
            id: transfer.id,
            name: transfer.name,
            status: status,
            active: false,
            startedAt: transfer.startedAt,
            retryable: outgoingFileSources[id] != nil
        )
        pruneTransferHistory()
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    private func pruneTransferHistory() {
        let active = fileTransfers.values.filter { $0.active }
        let history = fileTransfers.values.filter { !$0.active }
            .sorted { $0.startedAt > $1.startedAt }
            .prefix(maximumTransferHistory)
        fileTransfers = Dictionary(uniqueKeysWithValues: (active + Array(history)).map { ($0.id, $0) })
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
        trustRegistry.identityKey(for: deviceID)
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
        let delay = reconnectDelay(attempt: reconnectAttempt)
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

    private func scheduleHeartbeat(for current: Session) {
        heartbeatWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.session === current, case .connected = self.state else { return }
            if heartbeatExpired(supported: current.heartbeatSupported, lastReceivedAt: current.lastReceivedAt) {
                NSLog("TRANSPORT heartbeat timed out")
                current.close()
                return
            }
            current.send(PairingMessage(
                kind: "heartbeat.ping",
                sessionId: current.id,
                messageId: UUID().uuidString.lowercased()
            ))
            self.publishLocalBattery()
            self.mediaController.refresh()
            self.scheduleHeartbeat(for: current)
        }
        heartbeatWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: work)
    }

    private func postNotification(_ payload: RemoteNotificationPayload, deviceID: String) {
        let content = UNMutableNotificationContent()
        content.title = payload.callType == nil ? payload.applicationName : remoteCallStatusTitle(payload.callType)
        content.subtitle = payload.title
        content.body = payload.callType == nil ? payload.text : remoteCallDetail(payload.text, type: payload.callType)
        content.sound = .default
        content.categoryIdentifier = notificationCategoryIdentifier(for: payload, deviceID: deviceID)
        if let attachment = notificationIconAttachment(for: payload) {
            content.attachments = [attachment]
        }
        content.userInfo = [
            "androidPackage": payload.packageName,
            "androidNotificationId": payload.notificationId,
            "androidDeviceId": deviceID,
        ]
        let request = UNNotificationRequest(
            identifier: remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: payload.notificationId),
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

    func performRemoteCallAction(_ action: RemoteCallAction) {
        guard let call = remoteCall else { return }
        performAndroidNotificationAction(
            call.notificationID,
            deviceID: call.deviceID,
            actionToken: action.id,
            replyText: nil
        )
    }

    func hideCallOverlay() {
        if let call = remoteCall {
            hiddenCallOverlayIdentity = callOverlayIdentity(call.notificationID, deviceID: call.deviceID)
        }
        callOverlayWindow.hide()
    }

    private func updateRemoteCall(_ payload: RemoteNotificationPayload, deviceID: String) {
        guard let callType = normalizedRemoteCallType(payload.callType) else { return }
        let identity = callOverlayIdentity(payload.notificationId, deviceID: deviceID)
        if let existing = remoteCall,
           existing.notificationID != payload.notificationId || existing.deviceID != deviceID {
            clearRemoteCall()
        }
        clearCallStatus()
        let actions = (payload.actions ?? []).filter { !$0.allowsReply }.prefix(4).map {
            RemoteCallAction(id: $0.actionToken, title: $0.title)
        }
        remoteCall = RemoteCallStatus(
            notificationID: payload.notificationId,
            deviceID: deviceID,
            applicationName: payload.applicationName,
            caller: payload.title,
            detail: remoteCallDetail(payload.text, type: callType),
            type: callType,
            actions: actions
        )
        mediaController.callChanged(active: ["incoming", "ongoing"].contains(callType))
        if hiddenCallOverlayIdentity != identity {
            callOverlayWindow.show()
        }
        if callType == "incoming", audibleCallIdentity != identity {
            NSSound.beep()
            audibleCallIdentity = identity
        }
    }

    func clearNotificationHistory() {
        notificationHistoryStore.clear()
        notificationHistory = []
    }

    private func recordNotificationHistory(_ payload: RemoteNotificationPayload, deviceID: String) {
        guard settings.notificationHistoryEnabled else { return }
        let item = NotificationHistoryItem(
            id: remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: payload.notificationId),
            packageName: String(payload.packageName.prefix(256)),
            applicationName: String(payload.applicationName.prefix(128)),
            title: String(payload.title.prefix(1_024)),
            text: String(payload.text.prefix(8_192)),
            receivedAt: Date()
        )
        notificationHistory = notificationHistoryStore.record(item, in: notificationHistory)
    }

    private func notificationIconAttachment(for payload: RemoteNotificationPayload) -> UNNotificationAttachment? {
        guard let data = remoteNotificationIconData(payload.applicationIcon) else { return nil }
        let fileManager = FileManager.default
        guard let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let directory = caches.appendingPathComponent("Bridgey/NotificationIcons", isDirectory: true)
        let file = directory.appendingPathComponent(
            remoteNotificationIconFileName(packageName: payload.packageName, data: data)
        )
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            if !fileManager.fileExists(atPath: file.path) { try data.write(to: file, options: .atomic) }
            return try UNNotificationAttachment(
                identifier: "android-app-icon",
                url: file,
                options: [UNNotificationAttachmentOptionsTypeHintKey: UTType.png.identifier]
            )
        } catch {
            NSLog("PLUGIN notification icon attachment failed package=%@ error=%@", payload.packageName, String(describing: error))
            return nil
        }
    }

    private func notificationCategoryIdentifier(for payload: RemoteNotificationPayload, deviceID: String) -> String {
        let actions = (payload.actions ?? []).prefix(4).compactMap { action -> UNNotificationAction? in
            guard action.actionToken.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                  !action.title.isEmpty,
                  action.title.count <= 64 else { return nil }
            if action.allowsReply {
                return UNTextInputNotificationAction(
                    identifier: action.actionToken,
                    title: action.title,
                    options: [],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Reply"
                )
            }
            return UNNotificationAction(identifier: action.actionToken, title: action.title, options: [])
        }
        guard !actions.isEmpty else { return "bridgey.android.notification" }
        let categoryID = remoteNotificationCategoryIdentifier(
            deviceID: deviceID,
            notificationID: payload.notificationId,
            actionTokens: actions.map(\.identifier)
        )
        remoteNotificationCategories[categoryID] = UNNotificationCategory(
            identifier: categoryID,
            actions: actions,
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        while remoteNotificationCategories.count > 129,
              let staleID = remoteNotificationCategories.keys.first(where: { $0 != "bridgey.android.notification" }) {
            remoteNotificationCategories.removeValue(forKey: staleID)
        }
        UNUserNotificationCenter.current().setNotificationCategories(Set(remoteNotificationCategories.values))
        return categoryID
    }

    private func dismissAndroidNotification(_ notificationID: String, deviceID: String) {
        guard case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = session,
              current.remoteDeviceID == deviceID,
              featureEnabled(.notifications, current: current),
              !notificationID.isEmpty,
              notificationID.count <= 512,
              let plaintext = try? JSONEncoder().encode(NotificationReferencePayload(notificationId: notificationID)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "notifications.dismiss",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "notification", event: "dismiss_sent")
    }

    private func performAndroidNotificationAction(
        _ notificationID: String,
        deviceID: String,
        actionToken: String,
        replyText: String?
    ) {
        guard case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = session,
              current.remoteDeviceID == deviceID,
              featureEnabled(.notifications, current: current),
              !notificationID.isEmpty,
              notificationID.count <= 512,
              actionToken.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              (replyText?.count ?? 0) <= 4_096,
              let plaintext = try? JSONEncoder().encode(NotificationActionCommandPayload(
                notificationId: notificationID,
                actionToken: actionToken,
                replyText: replyText
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "notifications.action",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "notification", event: replyText == nil ? "action_sent" : "reply_sent")
    }

    private func receiveNotificationReference(_ message: PairingMessage, in current: Session) throws -> NotificationReferencePayload? {
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(NotificationReferencePayload.self, from: plaintext),
              !payload.notificationId.isEmpty,
              payload.notificationId.count <= 512 else {
            throw PairingError.invalidMessage
        }
        return payload
    }

    private func removeRemoteNotification(_ notificationID: String, deviceID: String) {
        if remoteCall?.notificationID == notificationID && remoteCall?.deviceID == deviceID {
            mediaController.callChanged(active: false)
            remoteCall = nil
            callOverlayWindow.hide()
            hiddenCallOverlayIdentity = nil
            audibleCallIdentity = nil
        }
        let identifier = remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: notificationID)
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        diagnostics.record(category: "notification", event: "removed_remotely")
    }

    private func clearRemoteCall() {
        mediaController.callChanged(active: false)
        guard let call = remoteCall else { return }
        remoteCall = nil
        callOverlayWindow.hide()
        hiddenCallOverlayIdentity = nil
        audibleCallIdentity = nil
        let identifier = remoteNotificationRequestIdentifier(
            deviceID: call.deviceID,
            notificationID: call.notificationID
        )
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
    }

    private func callOverlayIdentity(_ notificationID: String, deviceID: String) -> String {
        "\(deviceID)\u{0}\(notificationID)"
    }

    private func saveTrust(_ current: Session) {
        guard let identityKey = current.remoteIdentityKey else { return }
        if trustRegistry.remember(
            deviceID: current.remoteDeviceID,
            name: current.peerName,
            identityKey: identityKey
        ) {
            trustedDeviceIDs.insert(current.remoteDeviceID)
        }
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
    var lastReceivedAt = Date()
    var heartbeatSupported = false
    var onMessage: ((PairingMessage) -> Void)?
    var onFailure: (() -> Void)?
    private var buffer = Data()
    private var seenMessageIDs = Set<String>()
    var quickSequence = QuickRequestSequence()

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
                        let message = try decodeProtocolMessage(Data(line))
                        self.lastReceivedAt = Date()
                        self.onMessage?(message)
                    } catch {
                        NSLog("TRANSPORT invalid message error=%@", String(describing: error))
                        self.close()
                        self.onFailure?()
                        return
                    }
                }
                guard self.buffer.count <= maximumProtocolFrameBytes else {
                    NSLog("TRANSPORT protocol frame exceeded size limit")
                    self.close()
                    self.onFailure?()
                    return
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

struct PairingMessage: Codable {
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

enum PairingError: Error { case invalidMessage, cancelled }

private final class FileCancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool { lock.withLock { value } }
    func cancel() { lock.withLock { value = true } }
}

/// The folder being sent: its manifest and the position of the next file.
private final class OutgoingFolder {
    let id: String
    let manifest: OutgoingFolderManifest
    var nextEntry = 0
    var startedFiles = 0
    var sentFiles = 0
    var currentTransferID: String?
    var completedTransferID: String?
    var accepted = false
    var completing = false
    var timeout: DispatchWorkItem?

    init(id: String, manifest: OutgoingFolderManifest) {
        self.id = id
        self.manifest = manifest
    }
}

private final class OutgoingFileTransfer {
    private static let chunkSize = 24 * 1024
    private static let acknowledgementWindow: Int64 = 64
    let transferID: String
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

    init(url: URL, transferID: String, cancellation: FileCancellationToken) throws {
        self.url = url
        self.transferID = transferID
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

/// Finder-style collision handling: "name.ext", then "name 2.ext", "name 3.ext", and so on.
/// A name whose partial file is still being received counts as taken.
func uniqueReceivedFileURL(_ requested: URL) -> URL {
    func isTaken(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path) ||
            FileManager.default.fileExists(atPath: url.appendingPathExtension("bridgey-part").path)
    }
    guard isTaken(requested) else { return requested }
    let extensionPart = requested.pathExtension
    let stem = requested.deletingPathExtension().lastPathComponent
    for number in 2...10_000 {
        let name = extensionPart.isEmpty ? "\(stem) \(number)" : "\(stem) \(number).\(extensionPart)"
        let candidate = requested.deletingLastPathComponent().appendingPathComponent(name)
        if !isTaken(candidate) { return candidate }
    }
    return requested.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
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
        finalURL = uniqueReceivedFileURL(directory.appendingPathComponent(sanitized))
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
