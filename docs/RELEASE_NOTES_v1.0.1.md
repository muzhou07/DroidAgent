# DroidAgent v1.0.1

- 发布日期：2026-11-23
- 作者：[muzhou07](https://github.com/muzhou07)
- 仓库：<https://github.com/muzhou07/DroidAgent>
- 包名：`com.muzhou.droidagent`
- 版本号：`1.0.1`（versionCode 2）
- 系统要求：Android 8.0（API 26）及以上
- 许可：MIT

## 1.0.1 更新内容

修复 1.0.0 发布前临时加入的过高安全策略导致的部分功能不可用问题。

具体调整：

- `web_fetch` 恢复访问 localhost、内网、CGNAT、保留地址与 IPv6 ULA/映射地址。
- 模型 Base URL 支持 HTTP 与 HTTPS。
- MCP 支持任意大小响应、SSE 流式响应与重定向。
- `web_fetch` 正文读取上限为 10MB，`max_chars` 上限为 200,000。
- Markdown 外链支持任意 URI scheme。

这些地址由模型或用户配置直接访问，请只在可信工作区中使用。提示注入可能诱导 Agent 读取工作区文件并发送到外部服务。

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

- Android 15 模拟器完成对话、工具调用、审批、文件写入、Shell、MCP、子代理、附件与网页抓取测试。
- 单元测试通过；`lintDebug` 0 errors；`assembleDebug` 与签名 `assembleRelease` 通过。
- `web_fetch` 访问本机与内网地址的路径已放开。
- 冷启动沿用上次工作区；Termux 在共享工作区执行 `echo OK` 返回退出码 0。

## 产物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- Release APK（已签名）：`app/build/outputs/apk/release/app-release.apk`
- APK SHA-256：`63CE5A3068B7B3717FEB19BBFBF600D1937F50F8F84C0FA9BFD6713477D4DEEB`
- 签名：APK Signature Scheme v2，别名 `droidagent`，RSA 4096
- 证书 SHA-256：`97:A4:5A:D3:67:77:DF:BA:54:2D:6A:36:E7:9D:56:84:9D:76:CC:72:29:27:77:34:D1:1A:86:D6:F7:BD:A6:F2`
- 发布页：<https://github.com/muzhou07/DroidAgent/releases>

签名文件与密码保存在本地，不随仓库发布。

## 已知限制

见 [README](../README.md) 中的“已知限制”。

完整开发指南见 [AGENTS.md](../AGENTS.md)。
