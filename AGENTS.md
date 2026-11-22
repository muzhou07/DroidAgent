# DroidAgent 开发指南

> 本文件供在本仓库工作的开发者和 AI 编码工具参考。作者：[muzhou07](https://github.com/muzhou07)。

## 1. 项目事实

- **形态**：原生 Android 应用，Kotlin + Jetpack Compose，不使用 Node、WebView、React Native。
- **包名**：`com.muzhou.droidagent`；Debug 变体为 `com.muzhou.droidagent.debug`。
- **SDK**：`minSdk 26`、`targetSdk 35`、`compileSdk 35`。
- **工具链**：JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21。
- **模块**：
  - `app/`：Android UI、应用层、设置、会话存储、AgentRunner。
  - `agent/`：可独立复用的 Agent 内核（LLM、工具、MCP、Shell、VFS、Web）。
  - `agent/` 不应反向依赖 `app/`。
- **许可**：MIT。作者与维护者：`muzhou07`。
- **仓库**：<https://github.com/muzhou07/DroidAgent>

## 2. 产品约束

修改代码时需保持以下行为：

1. 会话创建后**工作区不可修改**。
2. 冷启动进入新对话页，并沿用**上次退出前的工作区**；工作区失效时回退 `default`。
3. 输入区保持附件、思考强度切换与“模型与 API”快捷入口。
4. 模型/网络错误以 `note_error` 消息**持久化到会话**，红色左对齐显示，且**不进入模型上下文**。
5. 思考过程自动折叠：仅当前推理展开，进入下一轮或结束后收起。
6. 设置项**实时保存**，没有“保存”按钮；设置子页保留左右滑动过渡。
7. 审批语义保持现状：
   - 三种模式：`ASK` / `AUTO` / `READ_ONLY`。
   - `ASK` 下所有 `mutating` 工具逐次弹窗，可勾选“本会话始终允许”（Shell/删除同样适用）。
8. 底部 Sheet 使用 Material 3；弹出菜单、输入框、滑块使用 Miuix 风格。
9. 新对话页为默认页；首条消息后由模型生成会话标题。
10. 应用图标为蓝底白色网络节点，保留 Android 13 `monochrome` 主题图标。

## 3. 代码地图

```
agent/src/main/java/com/muzhou/droidagent/agent/
  AgentLoop.kt          工具调用循环、审批入口、上下文压缩、消息提交
  PromptBuilder.kt      系统提示词、项目规则、压缩提示词
  AgentSettings.kt      PermissionMode / ReasoningEffort / 运行参数
  llm/                  OpenAI 兼容客户端、请求/响应模型、SSE
  tool/                 AgentTool 接口、内置工具、审批数据结构
  workspace/            VFS 抽象、本地文件系统、SAF
  shell/                内置 Shell、Termux RUN_COMMAND、后台任务
  mcp/                  Streamable HTTP 客户端与管理器
  web/                  DuckDuckGo/Tavily 搜索、网页抓取、SSRF 防护

app/src/main/java/com/muzhou/droidagent/
  data/                 AppContainer、SettingsRepository、SessionStore、AgentRunner
  ui/chat/              聊天列表、Composer、审批面板、工具卡片、ViewModel
  ui/markdown/          Markdown 解析/渲染、代码高亮
  ui/settings/          设置主页与子页
  ui/drawer/            侧栏会话树
  ui/files|terminal|workspace
```

### 关键数据

- DataStore：`droidagent_settings`，包含 `api_key`、`tavily_key`、`extra_headers`、`last_workspace_id` 等。
- SQLite：`droidagent.db`，当前 `DB_VERSION = 3`，启用 WAL。
- 表：`sessions`、`messages`、`workspaces`、`mcp_servers`。
- 密钥：`SecretCrypto` 使用 Android Keystore（AES/GCM，别名 `droidagent_secret_key_v1`），存储格式 `v1:<Base64(IV+密文)>`。

## 4. 关键流程

### Agent 循环
`ChatViewModel` → `AgentRunner.run()` → `AgentLoop.run()`：

1. 构建系统提示词（工作区、权限、任务清单、项目规则、MCP 状态）。
2. 流式请求模型，累积文本、推理和工具调用参数。
3. 无工具调用则结束；有工具调用则逐个审批并执行。
4. 工具结果写回历史并持久化，进入下一轮。
5. 接近上下文窗口时调用模型生成摘要，保留最近消息。

### 审批
- 审批入口在 `AgentLoop.executeTool()`。
- `AgentTool.mutating = true` 的工具在 `ASK` 模式需要审批。
- `ALLOW_SESSION` 会把工具名加入 `AgentSessionContext.allowedTools`。
- 审批 UI 在 `ui/chat/ApprovalSheet.kt`，展示命令、参数与 diff。
- 新增写操作必须经过审批层，并设置 `mutating = true`、实现有意义的 `approvalPreview()`。

### 持久化
- 用户消息、助手消息、工具结果、错误提示统一写入 `messages`。
- `note_error` 只用于 UI 展示，构造请求时会被过滤。
- 写消息使用事务；序号通过 `MAX(seq)+1` 计算。
- 冷启动调用 `deleteEmptyDrafts()` 清理空草稿。

### 设置与密钥
- `SettingsRepository.settings` 是全局 `StateFlow`，保存时立即写 DataStore。
- API Key / Tavily Key / 自定义请求头 / MCP 请求头必须加密存储。
- 加密失败时**不能回退为明文**。
- 冷启动时把仍为明文的数据自动改写为密文。
- 公网 LLM Base URL 强制 HTTPS；明文 HTTP 仅允许本机/局域网。

### 工作区与 Shell
- `WorkspaceKind`：`LOCAL`、`EXTERNAL`、`SAF`。
- 本地 VFS 使用 canonical path 防路径穿越；符号链接不得越界，递归删除不跟随 symlink。
- Shell：`ShellProvider` 根据工作区选择 `LocalShellExecutor` 或 `TermuxShellExecutor`。
- Termux 需要 `allow-external-apps=true`、`termux-setup-storage`、`RUN_COMMAND` 权限；未满足时返回明确提示，不静默等待。
- 后台任务：`ShellJobManager`，工具为 `job_list` / `job_output` / `job_kill`。

### MCP
- 仅支持 Streamable HTTP（2025-03-26）。
- `McpManager` 负责连接、工具发现与代理。
- 工具名格式 `mcp__<server>__<tool>`。
- MCP 响应限制 8MB，SSE 行数受限；连接不跟随重定向。
- MCP 工具默认视为 `mutating`，除非服务器声明 `readOnlyHint = true`。

### Web
- `WebTools` 负责搜索与抓取。
- `web_fetch` 禁止 localhost、内网、CGNAT、链路本地、组播、IPv6 ULA/映射地址。
- OkHttp 使用固定 DNS 解析结果防止 DNS rebinding；重定向逐跳校验。
- Markdown 外链仅允许 `http` / `https` / `mailto`。

## 5. 修改代码的规则

### 新增工具
1. 实现 `AgentTool`，提供准确的 `name`、`description`、`parameters`。
2. 写操作设置 `mutating = true`。
3. 实现 `summarize()`；有写操作时实现 `approvalPreview()`。
4. 在 `AgentRunner.prepare()` 注册。
5. 处理异常并返回 `ToolResult(isError = true)`，不要抛出未捕获异常。
6. 控制输出大小，建议不超过 6 万字符，避免超出上下文限制。
7. 需要网络、路径或 Shell 能力时，复用现有安全封装。

### 新增设置
1. `AppSettings` 增加字段，`toAgentSettings()` 按需映射。
2. `SettingsRepository` 增加 DataStore key、读取、保存。
3. 敏感值必须走 `SecretCrypto`。
4. `SettingsViewModel.saveSettings()` 自动保存；不引入手动保存按钮。
5. 设置页放在对应分组子页，保持 Miuix 输入框/滑块风格。

### 数据库变更
1. 增加 `DB_VERSION`，在 `onUpgrade` 中写兼容迁移。
2. 不要删除用户已有数据；已有列保持可读。
3. 大表查询确认走索引。
4. 写入使用事务。

### UI 约定
- 中文文案，表达简洁，避免空泛描述。
- 错误统一使用会话内红色消息。
- 思考内容默认折叠。
- 新弹出菜单使用自定义锚点定位（4–6dp）。
- 保持深/浅色主题颜色来自 `DsTheme.colors`，不要硬编码颜色。
- 修改界面后同步 `docs/screenshots/`。

### 安全约定
- 不记录 API Key、请求头、用户文件内容到日志。
- 不把密钥、`release.jks`、`keystore.properties` 加入 Git。
- 新增网络请求必须考虑 SSRF、超时和响应大小限制。
- 新增文件操作必须限制在工作区内，并防止符号链接越界。
- 处理不可信 MCP/网页/模型输出时，保持“内容不可信”的假设。

## 6. 构建、测试与发布

### 本地构建

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease
# 已签名: app/build/outputs/apk/release/app-release.apk
# 未配置签名: app-release-unsigned.apk
```

签名读取仓库根目录 `keystore.properties`（已忽略）：

```properties
storeFile=release.jks
storePassword=******
keyAlias=droidagent
keyPassword=******
```

缺少该文件时自动回退为 unsigned，便于 CI。**不要提交** `release.jks`、`keystore.properties`。

### 测试

```bash
./gradlew :agent:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

发布前至少确认：

- 单元测试通过
- `lintDebug` 0 errors
- `assembleDebug` 可安装
- `assembleRelease` 可签名并通过 `apksigner verify`
- 关键手工流程：对话、审批、写文件、Shell、MCP、错误冷启动、上次工作区、Termux（如可用）

### 发布检查
1. 更新 `README.md` 的功能、限制与测试覆盖。
2. 更新 `SECURITY.md`（安全相关改动）。
3. 更新 `docs/RELEASE_NOTES_v1.0.0.md`（用户可见改动、产物、签名指纹）。
4. 更新 `THIRD_PARTY_NOTICES.md`（新增依赖/参考项目）。
5. 更新截图（UI 改动）。
6. 确认 `git status` 不包含密钥、APK、日志、数据库。
7. 使用已签名 APK，不要发布 `app-release-unsigned.apk`。

## 7. 常见坑

- **WSL 构建不稳定**：避免 `-Dkotlin.compiler.execution.strategy=in-process`；必要时加 `--max-workers=1 --no-daemon`。
- **Termux 不执行**：先查 `allow-external-apps=true`、`termux-setup-storage`、RUN_COMMAND 权限。
- **R8**：新增反射/序列化类型时检查 `app/proguard-rules.pro` 与 `agent/consumer-rules.pro`。
- **数据库**：表结构变更通过 migration 完成。
- API Key：新增加解密逻辑时保持存储格式稳定，避免已有数据无法读取。
- **审批语义**：当前“本会话始终允许”对 Shell/删除仍有效；改语义前先确认产品决策。
- 工作区路径：数据库中的外部路径需要校验存在性与边界。
- **响应大小**：模型、MCP、网页请求都要有超时与上限，避免 OOM/卡死。

## 8. 文档同步清单

任何变更完成后，检查是否需要同步：

- `README.md`：功能、构建、测试、限制、用法
- `SECURITY.md`：安全设计、已知风险、报告渠道
- `docs/RELEASE_NOTES_v1.0.0.md`：版本说明与产物
- `THIRD_PARTY_NOTICES.md`：依赖与参考项目
- `AGENTS.md`：开发流程、约束、命令
- `docs/screenshots/`：UI 变化
- `LICENSE` / 作者信息：仅在项目归属变化时修改

## 9. 提交与协作

- 保持提交聚焦，一个提交只做一类改动。
- 不提交构建产物、日志、临时文件、密钥。
- 修改行为时同步测试与文档。
- 安全问题不要公开披露，按 `SECURITY.md` 的渠道报告。
- 作者：`muzhou07` · <https://github.com/muzhou07>
