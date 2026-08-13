// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "BridgeyMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "BridgeyMac", targets: ["BridgeyMac"])],
    targets: [
        .executableTarget(name: "BridgeyMac"),
        .testTarget(name: "BridgeyMacTests", dependencies: ["BridgeyMac"]),
    ],
    swiftLanguageVersions: [.v5]
)

