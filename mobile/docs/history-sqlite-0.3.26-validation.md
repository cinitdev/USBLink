# Mobile 0.3.26 · SQLite 历史验证

日期：2026-11-13。仅修改手机历史存储、查询、WebUI 按需详情及对应构建配置；未修改桌面 USB 挂载、网络协议或 adbd 权限。

## 最终行为

- Android 内置 SQLite，路径 `/data/adb/usblink/adb-history.db`，可能伴随 `-wal` / `-shm`。结构化列保存请求和关联返回，无 JSON 记录列。
- 最新 200 条，每页 50 条。旧 JSON 不读取、不导入、不改写；第一次使用本版数据库时从空历史开始。
- 仅连续的同来源、同请求类型、同原始参数去重：`A A A B A` 保留 `A B A`。保留第一次的返回，被抑制请求的结果不能覆盖它；重启保留去重边界，清空重置边界。
- USB 转发线程只更新有界内存记录，独立线程合并写入变化行。较早页用 `id < 游标` 与主键索引；筛选和搜索覆盖全部保留记录，包括返回文本。
- 列表不带完整输出；展开时按 ID 查询详情。保持响应大小限制、分类、脱敏、文本转义、4 KiB 输出上限和过期 epoch 检查。不重放命令取得结果。
- 历史只读控制请求不再触发完整网络状态刷新。控制服务仍保持原有请求处理模型，不宣称所有控制请求都能并行。

## 已执行的定向检查

| 检查 | 结果 |
| --- | --- |
| `HistorySqlTestMain`，真实 SQLite JDBC | 217 个断言通过；含连续去重、首次返回归属、重启、清空、230 条裁剪为 200 条、四页无漏项、Unicode 长命令与输出、跨页搜索、详情及主键查询计划 |
| `AdbHistoryTestMain` | 60 个断言通过，覆盖可观察请求、分类、敏感字段和记录范围 |
| `AdbResponseTestMain` | 26 个断言通过，覆盖返回关联、协议结果、隐私边界及旧 JSON 不读取／不改写 |
| WebUI `api.test.mjs` + `history.test.mjs` | 21 项通过，含摘要／详情协议、50 条分页和搜索 |
| WebUI `request-flow.test.mjs` | 9 项通过；请求流程源码本次未修改 |
| 浏览器 `tests/navigation-regression.html` | PASS；模拟原生异步桥接，启动共享／状态读取期间四页可切换，仅提交一次共享；50 条摘要、按需读取并转义输出、刷新保留展开并更新结果 |
| 本地 WebUI 交互预览 | 首屏 50 条、较早页 50 条、末页 25 条且禁用继续翻页；返回最新恢复 50 条；展开可读取示例安装返回 `Success` |

主机测试通过测试专用 JDBC 运行同一 SQL 存储逻辑，不能代替 Android 真机 SQLite／app_process 验证。JDBC 和日志接口 JAR 只存在于构建缓存，版本及校验值固定，不进入手机模块。未执行全量 USB／网络回归，未刷入或操作真实手机，未测量真机加载时延或耗电。

## 构建及产物

定向检查后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -UsbAdb -SkipTests
```

Java 编译、Android DEX 与 Vite 构建成功。构建阶段的 `-SkipTests` 避免重复运行全量套件，定向结果见上表。

- 文件：`release/USBLink-Mobile-0.3.26-usb-adb-arm64.zip`
- 大小：8,528,562 字节
- SHA-256：`40e9b0b9c1c458a65d78a49d748071d2c42d9ce7e06cf70af3454936a47353b8`
- 元数据版本 `0.3.26`、版本码 `326`、作者“一只小柒夏”。
- 已核对 ZIP 与内含 JAR 的 CRC、脚本 LF、README／WebUI／daemon 与最终构建文件一致、SQLite 实现进入 DEX、测试依赖未打包、校验文件匹配。
- 本地构建，未推送或发布 GitHub。

性能改动是减少每次读取的数据和进程调用、按索引分页、异步写入；没有仅凭更换存储格式就宣称真机必然快多少。
