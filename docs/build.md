# 构建 / Build

安装包只通过 GitHub Actions 构建，不要在本地出包。  
Packages are built only on GitHub Actions. Do not produce local release artifacts.

- `.github/workflows/debug.yml`：分支推送与手动 Beta / branch push and manual beta
- `.github/workflows/release.yml`：版本标签正式发布 / version-tag release

应用名、站点、商业支付开关、AdMob 广告单元、OAuth 和签名统一固化在 GitHub Secrets，无需在面板侧安装专门的客户端开关插件。  
App branding, site URL, payment switches, AdMob ad units, OAuth, and signing live in GitHub Secrets only.

### 必需 Secrets / Required Secrets

```text
XBCLIENT_DEFAULT_API_URL
XBCLIENT_APP_NAME
XBCLIENT_APPLICATION_ID
XBCLIENT_USER_AGENT
XBCLIENT_RELEASE_STORE_BASE64
XBCLIENT_RELEASE_STORE_PASSWORD
XBCLIENT_RELEASE_KEY_ALIAS
XBCLIENT_RELEASE_KEY_PASSWORD
```

其中 `XBCLIENT_APPLICATION_ID` 与 `XBCLIENT_RELEASE_*` 仅 Android 构建使用；桌面端（Windows / Linux）仅校验 `XBCLIENT_DEFAULT_API_URL`、`XBCLIENT_APP_NAME`、`XBCLIENT_USER_AGENT` 及按需的 `XBCLIENT_OAUTH_CALLBACK_SCHEME`。

### 可选与按需 Secrets / Optional & Conditional Secrets

| Secret | 默认值 | 适用范围 | 说明 |
| --- | --- | --- | --- |
| `XBCLIENT_PANEL_TYPE` | `xboard` | 全平台 | 可选 `xboard` 或 `pxboard` |
| `XBCLIENT_APP_PAYMENT_ENABLED` | `true` | 全平台 | 设为 `false` 时关闭客户端内在线支付渠道、在线余额充值及外部网页收银台/控制台入口，仅允许账户余额抵扣与兑换码 |
| `XBCLIENT_OAUTH_CALLBACK_SCHEME` | `pxboard` 下默认 `xbclient` | 全平台 | `XBCLIENT_PANEL_TYPE=xboard` 时必填；`pxboard` 使用浏览器授权轮询，无需依赖自定义回调 scheme |
| `XBCLIENT_ADMOB_APP_ID` | 空 | Android | 当配置了任一 `*_AD_UNIT_ID` 时必填；全部广告单元留空时可省略，客户端不初始化 MobileAds SDK |
| `XBCLIENT_APP_OPEN_AD_UNIT_ID` | 空 | Android | 非空即启用 AdMob 开屏广告，在 `pxboard` 与 `xboard` 下均纯客户端生效，无需面板配置 |
| `XBCLIENT_PLAN_REWARDED_AD_UNIT_ID` | 空 | Android | 非空且面板启用激励发放时在套餐页展示激励广告入口；`pxboard` 由官方 `admob` 插件校验 Google AdMob ECDSA SSV 回调（`/api/v1/extensions/admob/ssv`），`xboard` 由 `Xboard-XBClient` 校验 |
| `XBCLIENT_POINTS_REWARDED_AD_UNIT_ID` | 空 | Android | 非空且面板启用激励发放时在账户页展示激励广告入口；`pxboard` 由官方 `admob` 插件校验 Google AdMob ECDSA SSV 回调，`xboard` 由 `Xboard-XBClient` 校验 |
| `XBCLIENT_WEBSITE_URL` | 空 | 全平台 | 官网链接 |
| `XBCLIENT_PRIVACY_POLICY_URL` | 空 | 全平台 | 隐私政策链接 |
| `XBCLIENT_USER_AGREEMENT_URL` | 空 | 全平台 | 用户协议链接 |

> **激励广告安全边界**：开屏广告与客户端支付展示开关完全固化在构建参数中；观看激励广告后增加账户余额或赠送套餐属于服务端账本变更，必须由服务端校验 Google AdMob ECDSA SSV 回调签名后入账，不能由客户端本地直接加款。`pxboard` 在面板启用官方 `admob` 插件并开启对应场景后展示激励广告入口，AdMob 控制台 SSV 回调地址填写 `https://<站点域名>/api/v1/extensions/admob/ssv`；`xboard` 则配合 `Xboard-XBClient` 插件完成验签发奖。

平台是否可用，以对应 job 的最终结论为准。  
Treat the finished Actions job as the source of truth for whether a platform build works.

## 面板类型与 Google Play 更新

`XBCLIENT_PANEL_TYPE` 放在 GitHub Secrets，支持 `xboard`（未设置时默认）与 `pxboard`。`XBCLIENT_DEFAULT_API_URL` 为对应面板公开地址，不要带 `/api/v1`。同一份代码包含两种面板对接，使用相同的连接内核和设备设置；当前发布包固定连接发布者选择的站点，不提供多站点账号管理。

PxBoard 使用统一 `/rpc/v1` 接口完成全量原生交互：支持邮箱密码登录注册、找回密码、两步验证（TOTP 启用/关闭与登录挑战）、浏览器一次性授权、第三方 OAuth 登录与原生无 Cookie 账号绑定/解绑，通过 `subscriptions/client.profile` 增量同步节点、分流规则、套餐配额并自动初始化默认订阅链接，在客户端内原生提供套餐购买、优惠码试算、余额支付、在线收银台与余额充值（受 `XBCLIENT_APP_PAYMENT_ENABLED` 控制）、激励广告（配合 `admob` 插件）、兑换码、推广返佣与工单。界面模块自动跟随 PxBoard 已启用的插件（如未启用 `affiliate` 则自动隐藏返佣模块）。

登录对接分别存放在独立适配器中：

| 平台 | PxBoard | XBoard + OAuth 插件 |
| --- | --- | --- |
| Android | `PxBoardAuthAdapter.kt` / `PxBoardSession.kt` | `XboardAuthAdapter.kt` / `XboardApi.kt` |
| Windows / Linux | `crates/xbclient-panel/src/pxboard.rs` | `crates/xbclient-panel/src/xboard.rs` |

PxBoard 客户端通过 `/rpc/v1` 读取站点启用的插件与 OAuth 提供方；点击第三方登录或浏览器授权后，在系统浏览器打开本站授权页，由服务端完成第三方登录、人机验证与动态验证码，再由客户端轮询领取会话，不依赖自定义回调 scheme。XBoard 则在 `XBCLIENT_APP_PAYMENT_ENABLED=true` 时直接调用原生 `/api/v1/user/order/*` 与 `/api/v1/user/getQuickLoginUrl` 完成下单与网页收银台跳转，不依赖额外插件。

XBoard 从 `/api/v1/guest/comm/config` 加载 OAuth 插件的服务列表，通过系统浏览器访问插件授权入口，并使用安装包配置的 scheme 接收回调。适配器独立处理登录、注册确认、错误和 `token2Login` 换取会话，桌面端不再使用内嵌浏览器进行第三方授权。

两套适配器同时编译进应用，由 `XBCLIENT_PANEL_TYPE` 选择本站使用的接口，不探测失败后切到另一种协议。切换类型需通过应用更新，不新增站点切换入口。服务端的用户和 OAuth 绑定继续保存在各自面板和插件中。

切换站点或面板类型会要求重新登录并清除旧连接缓存，不删除服务端用户、套餐或余额，也不清除设备偏好。Google Play 安装的 Android 客户端通过 Play 更新，不使用 GitHub APK 覆盖。

更新已上架应用：

1. 保留 `XBCLIENT_APPLICATION_ID`、现有上传签名相关 Secrets，按上架合规与变现需求设置 `XBCLIENT_APP_PAYMENT_ENABLED`（例如 Play 包设为 `false`）及 `XBCLIENT_APP_OPEN_AD_UNIT_ID` 等参数，并确认 `XBCLIENT_PANEL_TYPE` 与 `XBCLIENT_DEFAULT_API_URL`。
2. 推送代码，由 Beta Builds 生成已签名 APK 和 AAB。正式标签的 Release Builds 同样提供 AAB。所有 Android、Windows、Linux 构建均由 GitHub Actions 完成。
3. 在原 Google Play Console 应用中，进入“测试和发布 → 测试 → 内部测试”，创建版本并上传新 AAB。不要创建新应用或更换包名。
4. 先通过 Play 内部测试更新现有安装，检查授权登录、节点连接和用户前台；验证后在原应用正式轨道发布。

`versionCode` 使用提交时间戳。上传前仍须核对其大于 Play 已发布及测试轨道中的最高值；仅重新运行同一个提交不会提高此值。AAB 使用原上传密钥签名，最终安装包由 Play App Signing 使用应用签名密钥签署。上传密钥与应用签名密钥可能不同，所以不能假定 GitHub APK 能覆盖 Play 安装。

Google 官方说明：[更新应用](https://support.google.com/googleplay/android-developer/answer/9859350)、[创建和发布版本](https://support.google.com/googleplay/android-developer/answer/9859348)、[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)。
