# Mobile 0.3.27 · ADB 命令记录与连续去重修正

日期：2026-11-13。本次基于用户反馈修正 0.3.26，未操作真实手机，未推送或发布 GitHub。

## 原因与修改

0.3.26 比较原始协议字符串。同一 `adb shell getprop` 通过 `shell:` 或 `shell,v2,raw:` 发送时产生不同签名；内部跟踪／探测请求还会打断相邻命令判断。

- 记录前只接受可以整理为 ADB 命令的操作。过滤 track-app、track-jdwp、JDWP、SYNC 通道、STAT／LIST 文件探测及未知服务。
- 保留可观察的 Shell／exec、安装阶段、push/pull、设备管理和可识别反向转发命令。参数与路径来自手机实际收到的数据，不虚构电脑路径或 host-only 参数。
- 同一来源按去掉协议包装后的完整命令和参数比较，签名在脱敏之前生成。`shell:`／`shell,v2:`、等效的 ABB 包装和 SEND／SND2 路径请求不再造成重复。
- 仅保留连续重复命令的第一条及该次返回；被抑制的响应不能覆盖首次结果。后台协议不打断连续性；不同实际命令、参数或来源保持独立。
- 启动时以同一规则整理已有 SQLite，保留可确认相同命令的最早一条。原始参数已脱敏／截断的旧记录不据展示文本猜测相同。仍保存 200 条、每页 50 条；旧 JSON 不读取、不导入。
- WebUI 详情展示可读 ADB 命令及关联返回，移除原始服务字符串的展示。观察器继续消费被过滤文件探测的响应，保证后续文件操作结果不串位。

## 定向验证

| 检查 | 结果 |
| --- | --- |
| `HistorySqlTestMain`，真实 SQLite JDBC | 232 个断言通过；新增协议包装归一、内部服务过滤、过滤后连续去重、不同参数／来源、旧 SQLite 整理与重启边界 |
| `AdbResponseTestMain` | 27 个断言通过；含过滤 STAT 后下一次 push 的 OKAY 仍关联正确 |
| `AdbHistoryTestMain` | 58 个断言通过；命令格式、安装阶段、脱敏、边界及转发字节不变 |
| WebUI `history.test.mjs` + `api.test.mjs` | 21 项通过 |
| 本地交互预览 | 50 条摘要、命令记录说明；展开安装阶段只展示 `adb shell cmd package install-commit 128` 与示例 `Success`，不显示原始 `abb_exec:` 服务字符串 |

关键序列检查：`getprop → getprop` 保留一条；两个 getprop 间夹杂 track-app／sync 仍保留一条；`getprop → wm size → getprop` 保留三条。保留首次结果，不拿后续执行的输出替换。不同命令中的敏感参数即使脱敏后显示相同，也不会误合并。

未重复运行全量网络／USB 套件；未声称已经在 K70 实测。观察器测试与真实 SQLite 主机测试不能替代真机传输和 ROM 验证。

## 产物

执行 `powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -UsbAdb -SkipTests`，Java、DEX、Vite 构建成功。

- `release/USBLink-Mobile-0.3.27-usb-adb-arm64.zip`
- 大小：8,529,822 字节；版本码：327
- SHA-256：`0f79fdcca5da8ffb51d3e20047c8b5f23144a5759d817d006f98a2821ccb9df1`
- 已验证 ZIP／JAR CRC、元数据、脚本 LF、README／WebUI／daemon 与最终构建一致、校验文件匹配；测试 JDBC 依赖未进入模块。
- 电脑无需更新，TCP 开发源码版本同步为 0.2.17。
