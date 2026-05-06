# XBClient

XBClient 是一个面向 Xboard 的 Android 客户端。Android 层使用 Kotlin、Jetpack Compose 与 Material 3；Rust 仅保留 AnyTLS 代理内核，并通过 JNI 提供节点测试与系统 VPN 能力。

## 当前能力

- 登录、注册、邀请信息、订阅信息与节点自动刷新。
- AnyTLS 订阅解析，订阅请求使用 `mihomo` User-Agent。
- 系统 VPN 连接、重连、停止、通知栏快捷操作、IPv4/IPv6。
- 节点真连接延迟测试：默认目标 `cp.cloudflare.com`，同一连接测试两次并使用第二次延迟。
- 应用排除/白名单规则，支持本机应用列表搜索。
- 激励广告参数、广告开关、支付开关、奖励数量由服务端插件下发，客户端不写死广告单元 ID。

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
```

也可以通过环境变量或 Gradle 参数传入：

```powershell
$env:XBCLIENT_DEFAULT_API_URL="https://example.com"
$env:XBCLIENT_APP_NAME="XBClient"
$env:XBCLIENT_ADMOB_APP_ID="ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx"
.\gradlew.bat :app:assembleDebug
```

等价 Gradle 参数：

```powershell
.\gradlew.bat :app:assembleDebug `
  -Pxbclient.defaultApiUrl=https://example.com `
  -Pxbclient.appName=XBClient `
  -Pxbclient.admobAppId=ca-app-pub-xxxxxxxxxxxxxxxx~xxxxxxxxxx
```

## 签名

Debug 与 Release 共用同一签名配置，以便相同版本线互相覆盖安装。

默认读取：

```text
app/config/release-signing.jks
```

签名密码不要提交。可在本机创建被忽略的：

```text
app/config/release-signing.local.txt
```

内容示例：

```properties
XBCLIENT_RELEASE_STORE_PASSWORD=<keystore password>
XBCLIENT_RELEASE_KEY_PASSWORD=<key password>
XBCLIENT_RELEASE_KEY_ALIAS=xbclient
```

如果需要重新生成签名文件：

```powershell
keytool -genkeypair `
  -v `
  -keystore app\config\release-signing.jks `
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
XBCLIENT_RELEASE_STORE_PASSWORD
XBCLIENT_RELEASE_KEY_PASSWORD
```

建议同时设置：

```text
XBCLIENT_APP_NAME
```


版本号由 Git 自动生成：`versionCode` 使用当前提交的 Unix 时间戳，`versionName` 优先使用当前精确 tag 去掉前缀 `v`，非 tag 构建使用提交时间戳与短提交号。Debug 固定追加 `.debug` 后缀。

设置命令示例：

```powershell
gh secret set XBCLIENT_DEFAULT_API_URL
gh secret set XBCLIENT_APP_NAME
gh secret set XBCLIENT_ADMOB_APP_ID
gh secret set XBCLIENT_RELEASE_STORE_PASSWORD
gh secret set XBCLIENT_RELEASE_KEY_PASSWORD
```

## 构建

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease --stacktrace
cargo test --manifest-path rust\xbclient-core\Cargo.toml
```

## 发布与历史清理

清理真实 API、真实名称或广告 ID 后，需要重写 Git 历史并强推：

```powershell
git push --force-with-lease origin main
```

强推后，其他本地副本需要重新克隆或按新历史重置。
