import Foundation
import IOKit.ps

struct LocalBatteryStatus: Equatable {
    let level: Int
    let isCharging: Bool
}

func normalizedBatteryStatus(current: Int, maximum: Int, isCharging: Bool) -> LocalBatteryStatus? {
    guard current >= 0, maximum > 0 else { return nil }
    let level = Int((Double(current) / Double(maximum) * 100).rounded())
    return LocalBatteryStatus(level: min(max(level, 0), 100), isCharging: isCharging)
}

func currentMacBatteryStatus() -> LocalBatteryStatus? {
    let snapshot = IOPSCopyPowerSourcesInfo().takeRetainedValue()
    guard let sources = IOPSCopyPowerSourcesList(snapshot).takeRetainedValue() as? [CFTypeRef] else {
        return nil
    }

    for source in sources {
        guard let description = IOPSGetPowerSourceDescription(snapshot, source).takeUnretainedValue()
            as? [String: Any],
              let currentCapacity = description[kIOPSCurrentCapacityKey] as? Int,
              let maximumCapacity = description[kIOPSMaxCapacityKey] as? Int,
              maximumCapacity > 0 else { continue }
        let isCharging = description[kIOPSIsChargingKey] as? Bool ?? false
        if let status = normalizedBatteryStatus(
            current: currentCapacity,
            maximum: maximumCapacity,
            isCharging: isCharging
        ) {
            return status
        }
    }
    return nil
}
