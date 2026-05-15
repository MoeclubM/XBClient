# XBClient

XBClient 是一个面向 Xboard 的 Android 客户端。Android 层使用 Kotlin、Jetpack Compose 与 Material 3；Rust 仅保留 AnyTLS 代理内核，并通过 JNI 提供节点测试与系统 VPN 能力。

## 当前能力

- 登录、注册、邀请信息、订阅信息与节点自动刷新。
- AnyTLS 订阅解析，订阅请求使用 `mihomo` User-Agent。
- 系统 VPN 连接、重连、停止、通知栏快捷操作、IPv4/IPv6。
- 节点真连接延迟测试：默认目标 `cp.cloudflare.com`，同一连接测试两次并使用第二次延迟。
- 应用排除/白名单规则，支持本机应用列表搜索。
- 激励广告默认关闭；网页套餐/支付入口默认开启，即使未安装 Xboard-XBClient 插件也可通过 Xboard 原版快捷登录进入套餐页。
- 安装 Xboard-XBClient 插件后，可由服务端下发广告开关、支付入口开关和 AdMob SSV 参数，客户端不写死广告单元 ID。

## 包名与项目名

- Gradle 项目名：`XBClient`
- Android 包名：`moe.telecom.xbclient`
- 默认应用名：`XBClient`

不要把真实站点 API、真实应用名、AdMob App ID、广告单元 ID、登录账号或密码写入 README、源码、提交信息或可提交配置文件。

## 本地构建配置

`local.properties` 已被 `.gitignore` 忽略，可以只在本机写入：

```properties
sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk
xbclient.defaultApiUrl=https://example.com
xbclient.appName=XBClient
xbclient.admobAppId=ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx
xbclient.userAgent=SecOneApp
xbclient.oauthCallbackScheme=secone
```

也可以通过环境变量或 Gradle 参数传入：

```powershell
$env:XBCLIENT_DEFAULT_API_URL="https://example.com"
$env:XBCLIENT_APP_NAME="XBClient"
$env:XBCLIENT_ADMOB_APP_ID="ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
$env:XBCLIENT_USER_AGENT="SecOneApp"
$env:XBCLIENT_OAUTH_CALLBACK_SCHEME="secone"
.\gradlew.bat :app:assembleDebug
```

等价 Gradle 参数：

```powershell
.\gradlew.bat :app:assembleDebug `
  -Pxbclient.defaultApiUrl=https://example.com `
  -Pxbclient.appName=XBClient `
  -Pxbclient.admobAppId=ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx `
  -Pxbclient.userAgent=SecOneApp `
  -Pxbclient.oauthCallbackScheme=secone
```

## 签名

仓库不提交任何 keystore。Debug 构建使用 Android 默认 debug 签名；Release 构建必须显式提供签名文件和密码。

本机 Release 构建可使用被 `.gitignore` 忽略的 keystore，例如：

```text
app/config/release-signing.local.jks
```

构建时传入：

```powershell
$env:XBCLIENT_RELEASE_STORE_FILE="app/config/release-signing.local.jks"
$env:XBCLIENT_RELEASE_STORE_PASSWORD="<keystore password>"
$env:XBCLIENT_RELEASE_KEY_ALIAS="xbclient"
$env:XBCLIENT_RELEASE_KEY_PASSWORD="<key password>"
.\gradlew.bat :app:assembleRelease
```

如果需要重新生成签名文件：

```powershell
keytool -genkeypair `
  -v `
  -keystore app\config\release-signing.local.jks `
  -storetype PKCS12 `
  -alias xbclient `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -storepass "<keystore password>" `
  -keypass "<key password>" `
  -dname "CN=XBClient, OU=Release, O=XBClient, L=Unknown, ST=Unknown, C=US"
```

## GitHub Secrets

当前工作流需要这些 Secrets：

```text
XBCLIENT_DEFAULT_API_URL
XBCLIENT_ADMOB_APP_ID
XBCLIENT_RELEASE_STORE_BASE64
XBCLIENT_RELEASE_STORE_PASSWORD
XBCLIENT_RELEASE_KEY_ALIAS
XBCLIENT_RELEASE_KEY_PASSWORD
```

建议同时设置：

```text
XBCLIENT_APP_NAME
XBCLIENT_USER_AGENT
XBCLIENT_OAUTH_CALLBACK_SCHEME
```


版本号由 Git 自动生成：`versionCode` 使用当前提交的 Unix 时间戳；`versionName` 优先使用当前精确 tag 去掉前缀 `v`。非 tag 构建会基于最近 release tag 生成 `版本-beta.提交数.短提交号`，例如 `0.0.1-beta.2.e0418843`；没有任何 tag 时才使用提交时间戳与短提交号。Debug 固定追加 `.debug` 后缀。

设置命令示例：

```powershell
gh secret set XBCLIENT_DEFAULT_API_URL
gh secret set XBCLIENT_APP_NAME
gh secret set XBCLIENT_ADMOB_APP_ID
gh secret set XBCLIENT_USER_AGENT
gh secret set XBCLIENT_OAUTH_CALLBACK_SCHEME
gh secret set XBCLIENT_RELEASE_STORE_BASE64
gh secret set XBCLIENT_RELEASE_STORE_PASSWORD
gh secret set XBCLIENT_RELEASE_KEY_ALIAS
gh secret set XBCLIENT_RELEASE_KEY_PASSWORD
```

## 构建

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease --stacktrace
cargo test --manifest-path rust\xbclient-core\Cargo.toml
```

## 开源许可

本项目采用 Apache License 2.0 开源。详见 `LICENSE` 与 `NOTICE`。

## 发布与历史清理

清理真实 API、真实名称或广告 ID 后，需要重写 Git 历史并强推：

```powershell
git push --force-with-lease origin main
```

强推后，其他本地副本需要重新克隆或按新历史重置。
