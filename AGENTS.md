# 项目协作约定

在写代码或改代码时，必须遵守：

1. 拒绝过度封装：简单逻辑直接内联，非必要不拆分新函数。
2. 强制优先复用：写新代码前必须先使用项目中现成的接口和工具。
3. 克制防御编程：信任内部数据，仅在必要边界做判空和类型检查，不要使用过多判断。
4. 避免冗杂：功能模块按功能拆分开，防止单代码文件过大。
5. 使用命令行编辑文件前请注意文件内容编码与命令行环境是否匹配；不匹配时不允许编辑，防止非英文内容被破坏。
6. 不优先在本地构建测试；优先使用静态检查、阅读差异和远端 CI 验证。只有本地构建测试确有必要时才执行。
7. 如果必须在本地进行构建测试，构建完成后必须清理本地构建缓存/产物，避免留下无关文件。

Do not introduce new boundary rules / guardrails / blockers / caps (e.g. max-turns), fallback behaviors, or silent degradation just to make it run.
Do not add mock/simulation fake success paths (e.g. returning (mock) ok, templated outputs that bypass real execution, or swallowing errors).
Do not write defensive or fallback code; it does not solve the root problem and only increases debugging cost.
Prefer full exposure: let failures surface clearly (explicit errors, exceptions, logs, failing tests) so bugs are visible and can be fixed at the root cause.
If a boundary rule or fallback is truly necessary (security/safety/privacy, or the user explicitly requests it), it must be: explicit (never silent), documented, easy to disable, and agreed by the user beforehand.
