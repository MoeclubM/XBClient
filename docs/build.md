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
