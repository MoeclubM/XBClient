// swift-tools-version:5.7
import PackageDescription

let package = Package(
  name: "tauri-plugin-xbclient-mobile",
  platforms: [
    .macOS(.v10_13),
    .iOS(.v13),
  ],
  products: [
    .library(
      name: "tauri-plugin-xbclient-mobile",
      type: .static,
      targets: ["tauri-plugin-xbclient-mobile"]
    )
  ],
  dependencies: [
    .package(name: "Tauri", path: "../.tauri/tauri-api"),
    .package(url: "https://github.com/googleads/swift-package-manager-google-mobile-ads.git", from: "12.0.0")
  ],
  targets: [
    .target(
      name: "tauri-plugin-xbclient-mobile",
      dependencies: [
        .byName(name: "Tauri"),
        .product(name: "GoogleMobileAds", package: "swift-package-manager-google-mobile-ads")
      ],
      path: "Sources"
    )
  ]
)
