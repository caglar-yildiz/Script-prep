// swift-tools-version: 5.9
import PackageDescription

// The text rules shared with the Android app: script parsing, scan cleanup, file formats,
// the library and voice cast storage. Tested on every build with `swift test`.
let package = Package(
    name: "MemoCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "MemoCore", targets: ["MemoCore"]),
    ],
    targets: [
        .target(name: "MemoCore"),
        .testTarget(name: "MemoCoreTests", dependencies: ["MemoCore"]),
    ]
)
