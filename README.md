# XBClient

XBClient 是面向 Xboard 的多平台客户端。代理、路由与 TUN 能力统一使用 Aerion；Android 使用 Kotlin、Jetpack Compose 与系统 `VpnService`，Windows/Linux 桌面端使用 Electron、Vue 和 Rust 后端。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `apps/android/` | 原生 Android 应用与 `VpnService` |
| `apps/electron/` | Windows/Linux Electron 壳、系统集成与打包配置 |
| `apps/electron/web/` | Electron Vue renderer |
| `apps/electron/backend/` | Electron Rust 后端 |
| `rust/aerion-core/` | Android 与桌面端共享的 Aerion 适配层 |
| `rust/third_party/` | Rust 构建所需的正式第三方源码 |
| `gradle/` | Android Aerion JNI 构建逻辑 |
| `scripts/ci/` | GitHub Actions 专用脚本 |

应用名与 Android 包名由 GitHub Actions 从对应 Secret 注入，仓库内不保存默认值。

## 构建与配置

项目禁止本地构建。Android APK/AAB、Windows 安装包和 Linux deb 只能通过 GitHub Actions 构建：

- `.github/workflows/debug.yml`：分支推送和手动触发的 Beta 构建。
- `.github/workflows/release.yml`：版本标签触发的正式构建与发布。

应用标识、站点、API、OAuth、AdMob 和签名配置只允许保存在 GitHub Secrets，不使用 `local.properties`、Gradle 参数、本地签名配置或安装目录旁配置文件。

必需 Secrets：

```text
XBCLIENT_DEFAULT_API_URL
XBCLIENT_APP_NAME
XBCLIENT_APPLICATION_ID
XBCLIENT_ADMOB_APP_ID
XBCLIENT_USER_AGENT
XBCLIENT_OAUTH_CALLBACK_SCHEME
XBCLIENT_RELEASE_STORE_BASE64
XBCLIENT_RELEASE_STORE_PASSWORD
XBCLIENT_RELEASE_KEY_ALIAS
XBCLIENT_RELEASE_KEY_PASSWORD
```

可选 Secrets：

```text
XBCLIENT_WEBSITE_URL
XBCLIENT_PRIVACY_POLICY_URL
XBCLIENT_USER_AGREEMENT_URL
```

Android 构建从 Actions 环境读取 Secrets 并写入最终应用。Electron 仅在 Actions runner 上生成被忽略的临时 `build-config.json`，打包完成后由 runner 销毁；仓库和本机不保存这些配置。

## 验证约定

本地只允许不产生构建产物的静态检查，例如：

```text
git diff --check
cargo fmt --all --check --manifest-path rust/aerion-core/Cargo.toml
cargo fmt --all --check --manifest-path apps/electron/backend/Cargo.toml
```

任何平台可用性结论均以 GitHub Actions 对应 job 的最终结论为准，不以本地构建或仍在运行的工作流代替。

## 开源许可

本项目采用 Apache License 2.0。详见 `LICENSE`、`NOTICE` 与 Android 应用内的开源许可页面。
