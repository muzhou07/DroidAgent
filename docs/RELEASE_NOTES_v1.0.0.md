# DroidAgent v1.0.0

- 发布日期：2026-11-23
- 作者：[muzhou07](https://github.com/muzhou07)
- 仓库：<https://github.com/muzhou07/DroidAgent>
- 包名：`com.muzhou.droidagent`
- 系统要求：Android 8.0（API 26）及以上
- 许可：MIT

DroidAgent 的首个公开版本，提供原生 Android 客户端与可复用的 Agent 内核。

## 包含

### Agent 与模型

- OpenAI Chat Completions 兼容客户端，支持 SSE 流式输出、工具调用增量与 `reasoning_content`。
- Agent Loop：多轮工具调用、最大轮次限制、上下文自动压缩、Token 估算、取消与部分结果持久化。
- 思考强度 None / Low / High / Max（默认 High），通过 `reasoning_effort` 发送。
- 请求超时与 12 分钟无活动自动停止。
- 每轮 Token 与耗时统计；会话级轮数、步数、tok/s、缓存命中与上下文占用。
- 模型或网络错误写入会话历史，冷启动后仍可查看，不进入模型上下文。

### 工具与工作区

- 文件工具：读取、写入、编辑、删除、目录浏览、glob、grep。
- Shell：内置 toybox；共享存储工作区可选用 Termux `RUN_COMMAND`。
- 后台任务：`run_in_background`、`job_list`、`job_output`、`job_kill`。
- 网页搜索（DuckDuckGo / Tavily）与网页正文抓取。
- MCP Streamable HTTP（2025-03-26）：工具发现、调用、会话 ID、JSON/SSE 响应。
- 子代理 `task`，支持两层嵌套。
- 工作区：应用私有、共享存储 `/sdcard/DroidAgent/`、SAF 目录；会话创建后绑定工作区。
- 附件：图片 data URL 与文本/代码文件。
- Markdown 渲染与代码高亮。

### 界面

- 多会话、自动标题、重命名、删除、重新生成、停止、复制。
- 侧栏按工作区分组，搜索标题与消息内容。
- 新对话页、沿用上次工作区、随机问候语。
- 思考过程自动折叠；输入区提供思考强度与“模型与 API”快捷入口。
- 设置采用分组卡片与子页，实时保存；深色、浅色、跟随系统主题。
- 底部 Sheet 使用 Material 3；菜单、输入框、滑块采用 Miuix 风格。
- 蓝色底白色网络节点图标，适配 Android 13 主题图标。

### 安全

- API Key、Tavily Key、自定义请求头与 MCP 请求头使用 Android Keystore（AES/GCM）加密存储。
- Keystore 不可用时保持原密文，不回退为明文；启动时把明文数据改写为密文。
- 默认询问审批，支持自动与只读模式；审批面板展示命令、参数与 diff。
- `web_fetch` 拦截 localhost、内网、CGNAT、保留地址与 IPv6 ULA/映射地址，并通过固定 DNS 解析防止 DNS rebinding。
- 公网 LLM 地址强制 HTTPS；明文 HTTP 仅允许本机或局域网。
- 本地文件系统限制在工作区内，拒绝路径穿越与符号链接越界。
- MCP 响应限制 8MB 与 SSE 行数，连接不跟随重定向。
- 禁用云备份与设备迁移。

## 验证

- Android 15 模拟器（1080×1920）完成对话、工具调用、审批、文件写入、Shell、MCP、子代理、附件与网页抓取测试。
- 本地、共享存储与 SAF 工作区的文件操作和 Shell 可用。
- 冷启动沿用上次工作区；Termux 在共享工作区执行 `echo OK` 返回退出码 0。
- 后台任务与错误持久化完成端到端验证。
- 单元测试通过；`lintDebug` 0 errors；`assembleDebug` 与签名 `assembleRelease` 通过。

## 产物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK（已签名）：`app/build/outputs/apk/release/app-release.apk`
- APK SHA-256：`4EFDBCC08A2790D03BC7277122B748D4CCC9706EEF545EB2EE652EB077B48B84`
- 签名：APK Signature Scheme v2，别名 `droidagent`，RSA 4096
- 证书 SHA-256：`97:A4:5A:D3:67:77:DF:BA:54:2D:6A:36:E7:9D:56:84:9D:76:CC:72:29:27:77:34:D1:1A:86:D6:F7:BD:A6:F2`
- 发布页：<https://github.com/muzhou07/DroidAgent/releases>

签名文件与密码保存在本地，不随仓库发布。

## 已知限制

见 [README](../README.md) 中的“已知限制”。

完整开发指南见 [AGENTS.md](../AGENTS.md)。
