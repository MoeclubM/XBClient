# XBClient

支持 [Xboard](https://github.com/cedar2025/Xboard) 与 [PxBoard](https://github.com/MoeClub-Open/PxBoard) 的 Android、Windows、Linux 客户端。

基于独立多端订阅客户端 [Neto](https://github.com/MoeclubM/Neto) 与 [Aerion](https://github.com/MoeclubM/Aerion) 内核构建，提供商业面板封装能力。登录面板账号后自动将节点与规则同步至共享连接内核，并在原生界面提供套餐购买、兑换码、返利、公告与工单功能。

订阅同步使用独立的 Aerion 适配器：Xboard 请求 `flag=aerion`，PxBoard 请求 `format=aerion`。继续读取 YAML 节点和规则；Flux 等 Aerion 扩展不进入面板的普通 Mihomo 订阅适配器。

[![Release](https://img.shields.io/github/v/release/MoeclubM/XBClient?style=flat-square)](https://github.com/MoeclubM/XBClient/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)

<p align="center">
  <img src="apps/android/src/main/res/drawable-nodpi/ic_launcher.png" width="96" alt="XBClient">
</p>

## 特性

- **面板一体**：登录 / 注册 / 第三方登录，套餐购买与兑换，工单，流量记录，邀请返利，站点公告。
- **全平台原生 UI**：Android 采用 Jetpack Compose + Miuix，Windows 采用 WinUI 3（.NET 9），Linux Wayland 采用 GTK4 + libadwaita，无 Electron / WebView 壳开销。
- **连接与节点分离**：独立无标题连接页、独立底栏节点页、支持自定义卡片与排序的 FlClash 风格主页仪表盘。
- **TUN 与分流**：全局、规则或直连模式；Android 支持分应用代理；Windows 与 Linux Wayland 支持系统代理与 TUN 双接管及系统托盘。
- **协议齐全**：Flux、Sudoku、AnyTLS、Hysteria2、Trojan、VLESS、VMess、Mieru、Naive、TUIC、Shadowsocks，以及 HTTP / SOCKS5。
- **多语言**：中文、英语、日语、俄语、波斯语，可跟随系统。

应用名、包名、站点地址和 API 在发布时注入，仓库里不存放默认品牌信息。通过 `XBCLIENT_PANEL_TYPE` 选择 Xboard 或 PxBoard；安装包指向发布者配置的站点。PxBoard 使用浏览器授权登录，节点与流量在应用内显示，套餐、支付、兑换码和工单复用站点用户前台。

## 平台

| 平台 | 安装包 | 连接方式 |
| --- | --- | --- |
| Android | arm64 APK / Play AAB | 系统 VPN（`NetoVpnService`） |
| Windows | WinUI 3 x64 独立压缩包 | 系统代理或 Wintun TUN（需管理员） |
| Linux Wayland | GTK4 deb（amd64 / arm64） | 系统代理或 TUN + StatusNotifierItem 托盘 |

## 使用教程

### 1. 安装

到 [Releases](https://github.com/MoeclubM/XBClient/releases) 下载对应平台的安装包：

- Android：安装 APK，按系统提示允许安装未知来源（如需要）。
- Windows：运行安装程序。使用 TUN 时请右键「以管理员身份运行」。
- Windows 系统代理无需管理员权限，详细行为与恢复方式见 [Windows 使用说明](docs/windows.md)。
- Linux：安装 deb 后，TUN 需要能访问 `/dev/net/tun`。

### 2. 登录

打开应用，用面板邮箱和密码登录；站点若开启了第三方登录，也可以走 OAuth。没有账号可以在应用内注册（邀请码按站点要求填写）。

登录后会同步套餐、流量和节点。没有有效套餐时，先到「套餐」页购买、兑换或重置流量。

### 3. 连接

1. 在节点列表里选择一个节点，需要时先点「测试」看延迟。
2. 点「连接」。Android 首次会请求 VPN 权限，请允许。
3. 状态变为「已连接」后即可使用。桌面端默认可以打开系统代理；需要全局接管网卡时再打开 TUN。

### 4. 分流（可选）

- **规则分流**：订阅若带了 Clash / Mihomo `rules`，连接时会按规则走代理或直连。也可以在设置里粘贴自定义 YAML。
- **应用分流（仅 Android）**：白名单只让选中的 App 走节点，黑名单则让选中的 App 直连。
- **DNS / IPv6**：可在设置中指定节点解析 DNS、代理 DNS、直连 DNS，以及 Fake-IP 或 IPv6。

### 5. 更新

应用内发现新版本时，可直接下载安装，或打开 Release 页面手动更新。

## 相关项目

- [Neto](https://github.com/MoeclubM/Neto) — 独立多端原生订阅与连接客户端框架
- [Aerion](https://github.com/MoeclubM/Aerion) — 协议、路由与 TUN 核心
- [NodeRS](https://github.com/MoeclubM/NodeRS) — Xboard 机器节点
- [Xboard](https://github.com/cedar2025/Xboard) — 面板

能力细节见 [Aerion](https://github.com/MoeclubM/Aerion)。自行编译安装包时，品牌、签名和站点配置说明见 [docs/build.md](docs/build.md)。

## 许可

Apache License 2.0。见 [LICENSE](LICENSE)、[NOTICE](NOTICE) 与应用内「开源许可」页。
