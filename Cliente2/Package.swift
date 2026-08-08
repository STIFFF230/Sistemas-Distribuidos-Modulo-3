// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "ChatMacClient",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "ChatMacClient",
            path: "Sources/ChatMacClient"
        )
    ]
)
