# 项目协作约定

在写代码或改代码时，必须遵守：

1. 拒绝过度封装：简单逻辑直接内联，非必要不拆分新函数。
2. 优先复用：写新代码前必须先使用项目中现成的接口和工具。
3. 克制防御编程：仅在必要边界做判空和类型检查，不要使用过多判断。
4. 功能模块按功能拆分开，防止单代码文件过大。
5. 使用命令行编辑文件前请注意文件内容编码与命令行环境是否匹配；防止非英文内容被破坏。
6. 禁止在本地 Windows 环境执行 Android、Electron Windows 或其他平台构建；本地仅允许不产生构建产物的静态检查。
7. Android 与 Windows 安装包必须直接通过 GitHub Actions 构建，并等待对应工作流得到明确的最终结论；不得以本地构建替代 Actions 验证。
8. 若用户明确批准临时本地诊断构建，结束后必须清理全部本地构建缓存与产物，避免留下无关文件。

Do not introduce new boundary rules / guardrails / blockers / caps (e.g. max-turns), fallback behaviors, or silent degradation just to make it run.
Do not add mock/simulation fake success paths (e.g. returning (mock) ok, templated outputs that bypass real execution, or swallowing errors).
Do not write defensive or fallback code; it does not solve the root problem and only increases debugging cost.
Prefer full exposure: let failures surface clearly (explicit errors, exceptions, logs, failing tests) so bugs are visible and can be fixed at the root cause.
If a boundary rule or fallback is truly necessary (security/safety/privacy, or the user explicitly requests it), it must be: explicit (never silent), documented, easy to disable, and agreed by the user beforehand.
