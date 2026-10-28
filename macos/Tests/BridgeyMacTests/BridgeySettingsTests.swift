import XCTest
@testable import BridgeyMac

final class BridgeySettingsTests: XCTestCase {
    func testGlobalSwitchOverridesDeviceSetting() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: true))
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: nil))
    }

    func testDeviceSwitchOverridesEnabledGlobalDefault() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: false))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: true))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: nil))
    }

    func testFeatureRequiresBothDevicesToOfferIt() {
        XCTAssertTrue(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: true))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: false))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: false, remoteEnabled: true))
    }


    func testNewerFeaturesAreOffForLegacyPeers() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.calls))
        XCTAssertFalse(featureEnabledByLegacyPeer(.ping))
        XCTAssertTrue(featureEnabledByLegacyPeer(.battery))
    }

    func testRemoteInputIsOffForLegacyPeersAndNeedsADeviceGrant() {
        XCTAssertEqual(BridgeyFeature.remoteInput.rawValue, "remote_input")
        XCTAssertFalse(featureEnabledByLegacyPeer(.remoteInput))
        XCTAssertTrue(BridgeyFeature.remoteInput.needsDeviceGrant)
        XCTAssertFalse(BridgeyFeature.clipboard.needsDeviceGrant)
    }

    func testRemoteInputNeedsTheGlobalSwitchAndAnExplicitDeviceGrant() {
        XCTAssertFalse(effectiveGrantEnabled(globalEnabled: true, deviceGranted: nil))
        XCTAssertFalse(effectiveGrantEnabled(globalEnabled: true, deviceGranted: false))
        XCTAssertFalse(effectiveGrantEnabled(globalEnabled: false, deviceGranted: true))
        XCTAssertTrue(effectiveGrantEnabled(globalEnabled: true, deviceGranted: true))
        XCTAssertFalse(remoteControlEffectivelyEnabled(globalEnabled: true, deviceGrants: [:]))
        XCTAssertFalse(remoteControlEffectivelyEnabled(globalEnabled: false, deviceGrants: ["a": true]))
        XCTAssertTrue(remoteControlEffectivelyEnabled(globalEnabled: true, deviceGrants: ["a": false, "b": true]))
    }
}
