# XBClient

面向 [Xboard](https://github.com/cedar2025/Xboard) 的多平台客户端。代理、分流和 TUN 统一走 [Aerion](https://github.com/MoeclubM/Aerion)；Android 用 Kotlin / Jetpack Compose / [MIUIX](https://github.com/compose-miuix-ui/miuix) / `VpnService`，Windows 与 Linux 桌面端用 Electron、Vue 和 Rust 后端。

应用名、包名、站点与 API 由 GitHub Actions 从 Secrets 注入，仓库里不保存默认品牌信息。

## 架构

```text
Xboard API / OAuth / 订阅节点
        │
        ▼
┌───────────────┐     ┌──────────────────────────┐
│ Android UI    │     │ Electron Vue + 系统集成  │
│ VpnService    │     │ 系统代理 / 托盘 / 更新   │
└───────┬───────┘     └────────────┬─────────────┘
        │  JNI / JSON              │ FFI / JSON
        ▼                          ▼
        └──────── rust/aerion-core ┘
                     │
                     ▼
                   Aerion
          协议客户端 · 路由 · TUN
```

`rust/aerion-core` 是 Android 与桌面共用的适配层：把订阅节点 JSON 或 Clash YAML 编成 Aerion 配置，再拉起本地 SOCKS 与 TUN。

## 功能

- 登录、套餐、工单、流量记录、邀请与站点公告（对接 Xboard 用户 API）
- 节点列表与连通性测试
- TUN 全局 / 规则分流（mihomo YAML，经 Aerion 静态路由表）
- Android 应用分流；桌面端系统代理
- OAuth 回调、AdMob（由 Secrets 配置）
- 开源许可页

连接侧协议由 Aerion 提供，当前 UI 允许：AnyTLS、Hysteria2、Trojan、VLESS、VMess、Mieru、Naive、TUIC、Shadowsocks、HTTP、SOCKS5，以及 direct / block。能力边界见 [Aerion 文档](https://github.com/MoeclubM/Aerion/blob/main/docs/limitations.md)。

## 项目结构

| 路径 | 职责 |
| --- | --- |
| `apps/android/` | 原生 Android 与 `VpnService` |
| `apps/electron/` | Windows / Linux 壳、系统集成、打包 |
| `apps/electron/web/` | Vue 界面 |
| `apps/electron/backend/` | 桌面 Rust 后端 |
| `rust/aerion-core/` | 两端共享的 Aerion 适配 |
| `rust/third_party/` | 构建所需的第三方源码 |
| `gradle/` | Android JNI 构建 |
| `scripts/ci/` | GitHub Actions 脚本 |

## 构建

禁止本地出包。Android APK/AAB、Windows 安装包和 Linux deb 只通过 Actions 构建：

- `.github/workflows/debug.yml`：分支推送与手动 Beta
- `.github/workflows/release.yml`：版本标签正式发布

标识、站点、API、OAuth、AdMob 和签名只放在 GitHub Secrets，不使用 `local.properties`、Gradle 参数、本地签名文件或安装目录旁配置。

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

可选：

```text
XBCLIENT_WEBSITE_URL
XBCLIENT_PRIVACY_POLICY_URL
XBCLIENT_USER_AGREEMENT_URL
```

Android 构建在 runner 上把 Secrets 写进最终应用。Electron 只在 runner 生成会被忽略的临时 `build-config.json`，打包结束后删除。

## 验证

本地只做不产生产物的检查，例如：

```text
git diff --check
cargo fmt --all --check --manifest-path rust/aerion-core/Cargo.toml
cargo fmt --all --check --manifest-path apps/electron/backend/Cargo.toml
```

平台是否可用，以对应 Actions job 的最终结论为准，不以本地构建或仍在跑的工作流代替。

## 相关项目

- [Aerion](https://github.com/MoeclubM/Aerion) — 协议与 TUN 核心
- [NodeRS](https://github.com/MoeclubM/NodeRS) — Xboard 机器节点
- [Xboard](https://github.com/cedar2025/Xboard) — 面板

## 许可

Apache License 2.0。见 `LICENSE`、`NOTICE` 与应用内开源许可页。
