# 安全策略

DroidAgent 是一个可以读写工作区、执行 Shell 并调用外部 MCP 服务的 Agent 工具。请仅在受信任的设备与工作区中使用。

- 作者 / 维护者：[muzhou07](https://github.com/muzhou07)
- 仓库：<https://github.com/muzhou07/DroidAgent>

## 支持的版本
仅对最新发布版本提供安全修复，当前为 `v1.0.0`。

## 报告漏洞
优先使用 GitHub 私有漏洞报告：

<https://github.com/muzhou07/DroidAgent/security/advisories/new>

请勿在公开 Issue 中披露可利用细节。报告中建议包含：
- 受影响的版本与 Android 版本；
- 复现步骤；
- 影响范围（文件访问、命令执行、MCP、网络请求等）；
- 建议的修复方向（如有）。

## 安全设计说明
- API Key、Tavily Key、自定义请求头与 MCP 请求头使用 Android Keystore（AES/GCM）加密存储；Keystore 不可用时不会回退为明文。应用禁用云备份与设备迁移，避免密钥随备份外流。
- 写文件、删除、Shell、MCP 写工具默认需要用户审批；支持只读模式。在“询问”模式下勾选“本会话始终允许”会按工具名放行本会话后续调用，Shell/删除等高风险工具请谨慎勾选。
- 工具路径被限制在工作区根目录内，拒绝 `..` 路径穿越；本地文件系统会解析符号链接并拒绝越界路径，递归删除不会跟随符号链接。
- `web_fetch` 禁止访问 localhost、内网、CGNAT、链路本地、组播与 IPv6 ULA/映射地址；通过固定 DNS 解析结果阻止 DNS rebinding，并对重定向逐跳校验。
- 公网 LLM 服务强制 HTTPS；明文 HTTP 仅允许本机或局域网地址，避免 API Key 在公网明文传输。
- MCP 响应体限制为 8MB，SSE 行数与总长度受限，避免恶意服务器造成内存耗尽。
- 聊天中的外部链接仅允许 `http` / `https` / `mailto`，避免模型输出触发任意 Intent。
- MCP 请求头与 URL 由用户配置，请只添加可信服务器；MCP 连接不跟随重定向，避免请求头被转发到其他主机。

## 已知风险
- 自动执行模式会跳过审批，请仅在沙箱或可恢复的工作区中使用。
- Termux Shell 与共享存储工作区可访问工作区之外的文件系统路径，需由用户自行承担风险。
- MCP 服务器可能返回恶意内容；模型输出与工具结果均不可信。提示注入可能诱导 Agent 读取工作区文件并通过 `web_fetch` / `web_search` 外发，处理不可信仓库时请使用只读模式或断网环境。
- 会话记录、附件与工作区文件保存在应用私有目录，但 SQLite 数据库未做整库加密；设备被 root 或调试备份被导出时可能被读取。
- 应用允许明文 HTTP 仅为兼容本地/局域网 LLM 服务；MCP 与网页抓取仍可能访问 HTTP 地址。
