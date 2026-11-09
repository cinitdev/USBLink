# WebUI 后台调用与切页响应 · 0.3.24

日期：2026-11-09。本地 USB 模块 0.3.24，TCP 源码 0.2.14；Windows 保持 0.3.19。

## 原因和改动

原调用使用带回调的三参数 ksu.exec，但管理器在原生方法内部同步等待 shell 完成。JS 的 Promise 以及操作前让出一帧都不能消除这段等待；共享启动、历史读取、系统检测较慢时仍可能阻塞页面。现优先检测并使用四参数 ksu.spawn，将固定 usblinkctl 命令入后台队列；stdout 按行收集，收到 exit 后验证完整 JSON。原始 stderr 不保存、不展示，输出收集最多 128 Ki UTF-16 码元，超出提示结果未确认；控制服务原有 64 KiB 响应上限不变。

功能探测仅在提交前进行。旧 exec-only 宿主保持三参数兼容，仍可能有卡顿；需更新管理器或 KsuWebUI 才能使用后台接口。spawn 抛错、错误事件、超时均不回退重试。保留原有单次操作串行、只读状态校验、USB 调试开启门槛、失效和过期处理。迟到回调由临时空接收器吸收，不保存输出或改变操作结果。

历史复用 Intl 日期格式器、列表分组和未变化行，详情折叠时不渲染长命令和返回正文。静默刷新以及分批补齐最新页均保留现有展开详情；内容变化仍更新。存储 1000 条、默认显示 100 条以及单条返回 4 KiB 上限保持不变。

2026-11-09 核对官方源码（外部主分支可能继续变化）：

- APatch：`app/src/main/java/me/bmax/apatch/ui/webui/WebViewInterface.kt`，exec 使用同步 `.exec()`，spawn 使用 `.enqueue()`。
- KernelSU：`manager/app/src/main/java/me/weishu/kernelsu/ui/webui/WebViewInterface.kt`，相同流事件和退出契约。
- KOWX712/KsuWebUIStandalone：`app/src/main/java/io/github/a13e300/ksuwebui/WebViewInterface.kt`，相同四参数 spawn，stdout/stderr/exit/error 回调。

## 针对性验证

- `node --test mobile/webui/tests/api.test.mjs mobile/webui/tests/request-flow.test.mjs mobile/webui/tests/history.test.mjs`：30 项通过，涵盖后台返回前事件循环可继续、分行 JSON、优先 spawn、exec-only 兼容、拒绝执行、错误后不补试、超时迟到回调、输出越界、旧结果隔离及 100 条分页。
- 本地 Vite + 内置浏览器打开 `tests/navigation-regression.html`：使用实际 App、原生适配器和受控的模拟后台。共享不返回时四页可切换；状态读取不返回时仍能切页；共享仅提交一次；有界批次补齐 100 条长输出；仅展开记录生成正文；HTML 作为文本显示；后台新结果不折叠详情。结果 PASS。
- 不重复运行无关桌面或守护进程全套测试，不重放 ADB 命令、不挂载 USB、不操作真机。

原生 spawn 仍可能同步创建 Root shell，首次 Root 授权和管理器本身的初始化耗时无法仅由网页消除。上述源码核对与浏览器模拟验证不等同于 K40/K70、APatch/Magisk/KernelSU 的真机帧率或耗时测量；本次未刷入手机。

## 交付

构建命令：`powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb -SkipTests`。
产物：`release/USBLink-Mobile-0.3.24-usb-adb-experimental-arm64.zip`，同目录 SHA256 文件。测试入口不进入生产 webroot。

构建成功，ZIP 大小 8,534,178 字节。已核对 ZIP CRC、版本／作者、说明文件和 WebUI 构建一致、DEX 内容、脚本 LF、测试页面未入包及校验文件一致。
SHA256：`121f7084673ad661c7b2408b5da4bb16e70480567949a805bcd703b48ef76620`。
