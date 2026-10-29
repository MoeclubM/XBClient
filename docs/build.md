# 构建 / Build

安装包只通过 GitHub Actions 构建，不要在本地出包。  
Packages are built only on GitHub Actions. Do not produce local release artifacts.

- `.github/workflows/debug.yml`：分支推送与手动 Beta / branch push and manual beta
- `.github/workflows/release.yml`：版本标签正式发布 / version-tag release

应用名、站点、API、OAuth、AdMob 和签名只放在 GitHub Secrets。  
App name, site, API, OAuth, AdMob, and signing live in GitHub Secrets only.

必需 / required:

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

可选 / optional: `XBCLIENT_WEBSITE_URL`、`XBCLIENT_PRIVACY_POLICY_URL`、`XBCLIENT_USER_AGREEMENT_URL`。

平台是否可用，以对应 job 的最终结论为准。  
Treat the finished Actions job as the source of truth for whether a platform build works.

## 面板类型与 Google Play 更新

`XBCLIENT_PANEL_TYPE` 放在 GitHub Secrets，支持 `xboard`（未设置时默认）与 `pxboard`。`XBCLIENT_DEFAULT_API_URL` 为对应面板公开地址，不要带 `/api/v1`。同一份代码包含两种面板对接，使用相同的连接内核和设备设置；当前发布包固定连接发布者选择的站点，不提供多站点账号管理。

PxBoard 需要部署客户端认证接口及新版 `subscriptions` 插件，详见 [PxBoard 原生客户端协议](https://github.com/MoeClub-Open/PxBoard/blob/main/docs/native-clients.md)。它使用浏览器完成站点登录和确认，再通过一次性校验领取会话；不依赖自定义 OAuth 回调地址。节点与分流从同一次认证 Mihomo 配置请求中读取，期限和用量从访问账户同步；商业与账户操作通过短期一次性链接打开本站前台。功能展示跟随面板启用的插件。Xboard 专用广告奖励不会迁移为 PxBoard 功能。

切换站点或面板类型会要求重新登录并清除旧连接缓存，不删除服务端用户、套餐或余额，也不清除设备偏好。Google Play 安装的 Android 客户端通过 Play 更新，不使用 GitHub APK 覆盖。

更新已上架应用：

1. 保留 `XBCLIENT_APPLICATION_ID`、AdMob 应用信息及现有上传签名相关 Secrets；只调整面板类型和 API 地址。
2. 推送代码，由 Beta Builds 生成已签名 APK 和 AAB。正式标签的 Release Builds 同样提供 AAB。所有 Android、Windows、Linux 构建均由 GitHub Actions 完成。
3. 在原 Google Play Console 应用中，进入“测试和发布 → 测试 → 内部测试”，创建版本并上传新 AAB。不要创建新应用或更换包名。
4. 先通过 Play 内部测试更新现有安装，检查授权登录、节点连接和用户前台；验证后在原应用正式轨道发布。

`versionCode` 使用提交时间戳。上传前仍须核对其大于 Play 已发布及测试轨道中的最高值；仅重新运行同一个提交不会提高此值。AAB 使用原上传密钥签名，最终安装包由 Play App Signing 使用应用签名密钥签署。上传密钥与应用签名密钥可能不同，所以不能假定 GitHub APK 能覆盖 Play 安装。

Google 官方说明：[更新应用](https://support.google.com/googleplay/android-developer/answer/9859350)、[创建和发布版本](https://support.google.com/googleplay/android-developer/answer/9859348)、[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)。
