# DroidAgent v1.1.0

- 发布日期：2026-11-28
- 作者：[muzhou07](https://github.com/muzhou07)
- 仓库：<https://github.com/muzhou07/DroidAgent>
- 包名：`com.muzhou.droidagent`
- 版本号：`1.1.0`（versionCode 3）
- 系统要求：Android 8.0（API 26）及以上
- 许可：MIT

## 1.1.0 更新内容

### Plan / Action 模式

- 输入区新增 Plan / Action 切换按钮，位于思考强度左侧；设置 → Agent 设置也可切换。
- Plan 模式只提供只读工具，不注册写入、编辑、删除、Shell 与后台任务终止工具。
- Plan 模式下即使模型请求写操作也会被内核拦截，并提示切换到 Action 模式。
- 系统提示词按模式注入约束，Plan 输出计划、风险与待确认事项。

### 设置与弹窗

- 设置页重组：Agent 模式、权限模式与 Termux 合并为紧凑结构，移除重复说明。
- 「Agent 设置」行不再显示右侧摘要。
- MIT License、添加/编辑 MCP、发现新版本统一改为窗口式弹窗：
  - 标题居中，正文区域独立滚动，底部按钮固定；
  - 先测量按钮再分配正文高度，卡片始终位于系统手势条上方；
  - 键盘弹出时自动避让，按钮不会被遮挡。

### MCP

- 新增 MCP 服务器默认启用。
- 表单移除右上角关闭按钮与启用开关，保存后直接生效。

### 对话与工具卡片

- 思考与工具执行卡片统一为单行布局：
  - 思考新增图标；
  - 工具名旁直接显示命令、路径、URL 等具体内容，过长自动省略；
  - 右侧执行状态图标（等待 / 运行 / 完成 / 失败）保持可见；
  - 两种卡片同宽同高，圆角与内边距一致。
- 上下文压缩后仅在会话中显示一行「上下文已压缩」，不再展示摘要正文；连续压缩不重复追加提示。

### 网页能力

- 搜索按 Tavily → Bing → DuckDuckGo 顺序回退。
- 抓取支持桌面/移动 UA 重试、重定向、编码识别与正文提取。
- `web_fetch` 支持任意 http(s) 地址，包括本机、内网与保留地址。

## 主要功能

- OpenAI Chat Completions 兼容客户端：SSE 流式、工具调用、`reasoning_content`。
- Agent Loop：多轮工具调用、上下文压缩、Token 估算、取消与部分结果持久化。
- 思考强度 None / Low / High / Max，通过 `reasoning_effort` 发送。
- 文件、Shell、后台任务、网页搜索与抓取、MCP、子代理。
- 应用私有、共享存储与 SAF 工作区；会话创建后绑定工作区。
- 多会话、自动标题、附件、Markdown 渲染、用量统计。
- 询问、自动、只读三种权限模式；审批面板展示命令、参数与 diff。
- API Key、Tavily Key、自定义请求头与 MCP 请求头使用 Android Keystore 加密存储。
- 深色、浅色、跟随系统主题；Miuix 风格菜单、输入框与滑块。

## 验证

- 单元测试通过；`lintDebug` 0 errors；`assembleDebug` 与签名 `assembleRelease` 通过。
- Android 15 模拟器（1080×1920）验证单行工具卡片、思考图标、失败态配色、压缩提示与 Plan / Action 入口。
- 真实设备验证设置弹窗（MIT / MCP / 更新）在键盘弹出与手势条场景下按钮完整可见。

## 产物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK（已签名）：`app/build/outputs/apk/release/app-release.apk`
- Debug SHA-256：`251415F66C6117D2BDDE34FCDF07AEE3ED8BA9D2003A141CBBF2F254D2672540`
- Release SHA-256：`8E94A1F4CC3B7CE0E3020C6840E05D208DCF2821324848619E28ACB71EAA6596`
- 签名：APK Signature Scheme v2，别名 `droidagent`，RSA 4096
- 证书 SHA-256：`97:A4:5A:D3:67:77:DF:BA:54:2D:6A:36:E7:9D:56:84:9D:76:CC:72:29:27:77:34:D1:1A:86:D6:F7:BD:A6:F2`
- 发布页：<https://github.com/muzhou07/DroidAgent/releases>

签名文件与密码保存在本地，不随仓库发布。

## 已知限制

见 [README](../README.md) 中的“已知限制”。

完整开发指南见 [AGENTS.md](../AGENTS.md)。
