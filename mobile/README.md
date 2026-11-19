# USBLink Mobile

0.3.36 修复 Android VPN 分流时模块反向检测电脑误走代理网卡。手机现在绑定旧 USB/IP 入站 socket 的本地 USBLink 地址后再做认证查询，让断流的旧会话可被正确核实和释放。需要与 Windows 0.3.21 一起更新；电脑手动连接时短暂在线探测失败会继续只读等待 6 秒。没有自动挂载或重复 attach。此前旧会话修复及保护规则见 [定向验证](../docs/network-session-0.3.20-validation.md)。

当前正式版本 **USB 0.3.36 / TCP 0.2.21**：SQLite 最多保留 **200 条**、每页 **50 条**。仅记录实际 ADB 命令，过滤 track-app、sync 通道、JDWP 和文件探测。按同一电脑实际发送的命令和参数去重，忽略 shell／shell,v2 等协议包装，连续重复只保留第一条及返回，后台协议不打断连续性。已有 SQLite 数据库按新规则整理，旧 JSON 不读取、不导入。列表读取摘要，展开再查询详情。数据库为 `/data/adb/usblink/history/adb-history.db`；配对与共享偏好不变，本版断流会话修复需要电脑更新到 0.3.21。正式发布见 [GitHub v0.3.21](https://github.com/cinitdev/USBLink/releases/tag/v0.3.21)。

0.3.34 把守护进程的 stdout 与 stderr 分开：`logs/daemon.log` 只留模块自己的 JSON 错误，MIUI 等在打开 SQLite 时打印的框架堆栈改到 `logs/daemon.stderr.log`（另有 `daemon.previous.stderr.log`），两边都按 64 KiB 截断。状态里的模块版本改为读取 `module.prop`，不再写死。

0.3.31 在真我 GT5／Android 16 上确认 SQLite 默认同步模式读取抛出空指针。打开数据库时明确指定 WAL 和 FULL 后，同机隔离数据库的初始化、命令／返回保存、连续去重和重开持久化均通过；不删除或归档原历史。未重启共享或替换真机运行模块，详见 [验证记录](docs/history-android-0.3.31-validation.md)。

0.3.30 修复 USB 调试已开启、但系统 adbd 已停止时无法开启共享的问题：先建立端口防护，再请求一次启动并等待实际监听；关闭时恢复原服务状态。正在切换或未知状态给出对应错误，不再提示调试开关未开启。未在反馈的真我 GT5／Android 16 真机验证，详见 [验证记录](docs/adbd-start-0.3.30-validation.md)。

0.3.29 修复历史脱敏误判：普通 `usblink-` 文件名、路径和网络名正常记录，只隐藏 `USBLINK1-` 配对码及明确的凭据字段。旧记录已替换的原文不能恢复，修复对新记录生效。

0.3.36 与 Windows **0.3.21** 一起正式发布。除本页开头的 SQLite 历史与断流修复外，保留共享期间 WebUI 切页优化、USB 调试检测和本地 WebUI Shell Root 检测；adbd Root 运行模式已删除。默认 TCP 开发源码为 0.2.21，本次仅分发 USB ADB ZIP。建议两端一起更新。验证边界见 [Windows 验证记录](../docs/network-session-0.3.21-validation.md) 和本页下方各模块记录。

从旧版升级后请重启手机一次，让旧进程和临时挂载退出；配对、共享偏好和历史保留。旧 Root 配置文件不再读取。日常共享启停仍无需重启手机。下方版本条目是历史记录，不代表已移除功能仍可使用。

面向 Android 12 及以上（API 31+）、ARM64 的 Root 手机模块，同一 ZIP 支持 Magisk、APatch 和 KernelSU。Magisk 用户通过独立 KsuWebUI 应用打开界面；APatch／KernelSU 可使用管理器内置 WebUI。作者：一只小柒夏。用户反馈骁龙和天玑设备均已测试可用；已记录的开发真机环境为 Redmi K40 + Android 12 + APatch，各 ROM／管理器组合仍以实测为准。后端为 DEX JAR + app_process，没有独立 USBLink APK。

电脑在 USBLink 连接页点击连接后，现有 ADB 经虚拟 USB 接口发现手机。保留传输修复、关闭共享断网、手机名称和静默自动同步。WebUI 为 AppOpt 风格的设备／连接／历史／设置四页；见 [模块说明](experimental/usbip/MODULE.md)。模块不支持 MTP / OTG。以下带版本号的小节是历次实现记录，当前使用步骤见“当前发布”。

## 0.3.12：损坏运行记录的启动恢复

模块数据按用途存放，根目录只保留三份互斥锁和 `backups/`：

| 目录 | 内容 |
| --- | --- |
| `config/` | `mesh.json`（配对）、`sharing.json`（共享开关） |
| `history/` | `adb-history.db` 及 SQLite 的 `-wal`／`-shm`／`-journal` |
| `runtime/` | `adbd-tcp.json`、`mesh.toml`、`mesh.route`、进程记录、状态快照、`stopping`、`supervisor.lock/pid` |
| `logs/` | 当前／上一份日志与启动错误；守护进程的框架堆栈单独放在 `daemon.stderr.log` |
| `backups/` | 旧 JSON 归档（`legacy/`）、运行记录修复备份，保留不删 |

启动时的整理**不是**启动条件：没有模块进程时才尝试，文件能搬就搬；搬不动就留在原处继续用，本次启动照常完成，也不会写 `startup.error`。旧位置和新位置都有同一文件且内容不同时，保留上一版实际使用的根目录文件为当前数据，把旧的归档到 `backups/legacy`；内容相同才删除旧副本。配对、共享偏好、历史和恢复记录不会被隐藏。SQLite 数据库与配套文件作为一组处理，打开前若发现分处两个目录会先合并。旧 JSON 历史只归档、不读取、不导入。

0.3.32 的修复范围见 [更新日志](../CHANGELOG.md)。

## 0.3.11：路由检查误断开修复

路由读取时限改为 4 秒，单次失败后进行一次只读核实，兼容等价的数字路由表名；添加规则结果不明时读取实际状态，绝不重复添加。确认缺失／冲突仍停止共享，并分别记录具体错误。仅运行网络观察回归（87 项通过）后打包，未在 K70 实测，Windows 无需更新。

## 0.3.10：网络状态查询误断开修复

状态查询超时或返回不完整时，分别验证原 EasyTier 进程、同一 `usblink0` 网卡与 IP、实际路由；这些仍正常才保留已有 USB 会话。不沿用过期中继／设备在线结果，WebUI 显示“状态待确认”，状态查询恢复后自动同步。接口消失／重建、IP 改变、进程退出或路由无法确认仍撤销会话，并显示细分原因；不自动重新挂载。

适用于用户反馈的 K70 / Android 15 / 0.3.8 闲置也出现 `NETWORK_UNAVAILABLE` 的排查。确定并修复了代码中的误撤销路径，尚未取得该手机故障现场日志或刷入新包实测。回归范围与限制见 [验证记录](docs/network-observation-0.3.10-validation.md)。默认 TCP 开发模块共享此修复，版本为 0.2.7。

## 0.3.9：Magisk / APatch / KernelSU

- 三种管理器安装同一个 ZIP，当前为 `USBLink-Mobile-0.3.36-usb-adb-arm64.zip`，无需按 Root 类型分别下载。Magisk 要求 20.4+；所有管理器均要求 Android 12+、ARM64。
- **Magisk**：在 Magisk 模块页安装 ZIP，按管理器提示完成首次重启；自行安装 [KsuWebUI](https://github.com/KOWX712/KsuWebUIStandalone)，授予它 Root 权限，打开后选择 USBLink。Magisk 官方没有内置 WebUI，模块“操作”按钮用于启动／检查后台，随后在 KsuWebUI 打开界面。
- **APatch / KernelSU**：安装 ZIP 后按管理器提示完成更新，在模块 WebUI 打开；也可使用已获 Root 授权的 KsuWebUI。
- 后台使用对应管理器自带的 BusyBox，Magisk 新增 `/data/adb/magisk/busybox` 路径；找不到 `flock` 时仍拒绝无锁启动。关闭界面不会停止共享服务，模块禁用／卸载仍执行原清理流程。
- KsuWebUI 复用现有 `ksu.exec` 回调接口；不自动下载 APK、不创建网络 Root 控制入口、不绕过 Root 授权。默认 TCP 开发构建同步为 0.2.6。

版本 0.3.9 的管理器兼容性经过源码核对、安装／运行路径夹具测试和主机构建，尚未刷入 Magisk／KernelSU 真机。验证边界详见 [验证记录](docs/root-managers-0.3.9-validation.md)。

## 0.3.8：已移除的历史功能

旧版曾提供 adbd Root 开关，0.3.18 已完整移除。旧版验证记录仅供回溯：[历史记录](docs/adb-root-0.3.8-validation.md)。

## 0.3.7：远程 ADB 请求历史

连接页新增历史记录，显示远程 shell / exec、应用管理请求及 SYNC 文件操作的时间、来源 IP 和内容。手机私有目录保留最近 200 条，最新 20 条随状态静默刷新，支持翻页与确认清空，重启仍保留。记录不代表执行成功；不捕获交互式 shell 按键、文件内容、命令输出、认证材料或未经过模块的调试。默认 TCP 开发构建同步支持该功能，版本为 0.2.4。实现和验证边界见 [历史记录验证说明](docs/history-0.3.7-validation.md)。

## 0.3.6：双向传输与中断诊断

将手机 adbd 上传写入从 USB 请求分发线程中拆出，避免上传受阻时连读请求、取消请求都无法处理。保持单写入线程、有界队列与顺序，未完成写入不会提前报告成功。设备／连接页显示最近一次意外中断的固定原因、时间和诊断码，并保留于当前后台进程内；日志仅记录原因与已完成字节数，不记录调试内容或配对信息。真机观察与验证边界见 [传输验证记录](docs/duplex-0.3.6-validation.md)。

## 0.3.5：共享端口复用修复

修复网络恢复或重新开启共享时，旧连接的 TIME_WAIT 导致 USB 监听报 `EADDRINUSE` 的问题。失败的监听构造立即释放资源，真实端口冲突显示具体 TCP 端口。保留实际网络丢失时撤销会话、电脑手动重新连接的行为。此次反馈来自 K70 / Android 15 / 0.3.4；验证范围与限制见 [端口修复验证记录](docs/port-reuse-0.3.5-validation.md)。

## 0.3.4：共享开关与断网省电

关闭共享时停止 EasyTier、认证在线服务和远程会话，恢复调试配置；配对码及网络配置保留。再次开启共享时连接原网络，电脑仍需手动连接 USB。关闭状态下创建、加入网络及修改中继仅保存配置，重启也不会擅自联网；只有“退出网络”会忘记配对。

连接页主动断网显示为“已断开 · 配对信息已保留”。本地管理服务保持可用，日常启停无需重启。清理失败会保留错误和重试入口，不能把开关已关闭等同于清理成功。

空闲时停止 EasyTier 查询及重复的调试恢复，后台观察改为每 5 秒；共享时每 2 秒的防护检查保持。WebUI 隐藏时暂停前端轮询，状态仍验证进程身份和 10 秒时效。没有真机电池测量，因此只能确认减少了后台活动，不能承诺具体耗电百分比。

## 当前发布：USB ADB 模块 0.3.36

下载：[Windows 0.3.21 + Mobile 0.3.36 正式发布版](https://github.com/cinitdev/USBLink/releases/tag/v0.3.21)。手机安装 `USBLink-Mobile-0.3.36-usb-adb-arm64.zip`，电脑安装 `USBLink-0.3.21-x64-Setup.exe`。断流后旧会话释放需新版两端配合。模块名称与安装包不再包含实验标识。

1. 在 Magisk／APatch／KernelSU 模块页覆盖安装 ZIP，按管理器提示完成更新并重启一次，清除旧进程及可能遗留的临时挂载。同一 USB 通道的组网配置、共享开关及历史会保留；从旧 TCP 模块升级时不会自动开启新 USB 通道。
2. 手机开发者选项开启普通 **USB 调试**，不需要无线调试，也不要求手机接到另一台电脑上。
3. 手机 WebUI 与电脑 USBLink 创建／加入同一个网络，保管好配对码。
4. 手机“设备”页开启“共享这台手机”。重启后会保留开启意愿，等待系统完成启动、网络防护稳定后才恢复共享；等待期间可关闭开关。
5. Windows“连接”页选择手机和它的共享设备，点击“连接设备”。首次连接请在手机确认电脑 RSA 授权，之后电脑现有 ADB / Android Studio 可使用该 USB 调试接口。**无需执行 `adb connect`**。
6. Windows 可单独断开该设备；手机关闭共享会撤销所有本机共享会话。掉线后由用户手动重新连接，不会自动重挂载。

模块显示手机系统提供的市场名称，再回退设备名称或原型号，不使用机型表。WebUI 使用蓝白／藏蓝配色，后台静默同步状态，保留正在编辑的表单；从后台返回立即读取新状态。

此版本只有 Android ADB Interface，**没有 MTP 便携存储、Fastboot、外接 U 盘或外接手机共享**。此前独立试验已在 Redmi K40／Android 12／APatch 验证驱动、认证、64 MiB 双向传输和清理。用户于 2026-11-09 反馈已在骁龙和天玑手机上测试可用。本次发布未将新版刷入 K70，也未复现断网过程；这些结果不等同于新包在所有 ROM／管理器、代理核心与路由配置上的完整验收。见 [模块说明](experimental/usbip/MODULE.md) 和 [网络会话验证](../docs/network-session-0.3.21-validation.md)。

## 旧 TCP 模块（开发兼容路径）

以下说明仅适用于默认 TCP 构建，不是本次 USB ADB ZIP 使用步骤。当前源码版本为 0.2.21（本次不分发 TCP ZIP），共享开关同样控制网络；已发布历史文档中的 0.2.2 关闭后在线行为不再适用。

本版共享手机自己的 **系统 adbd TCP 调试**，经 EasyTier 加密网络连接电脑现有 ADB / Android Studio。已移除系统无线调试的 TLS 配对通道、mDNS 发现、配对窗口与手动端口。**不需要开启无线调试，不需要 USB 线**。

1. 如需开发兼容路径，自行构建并在 Magisk／APatch／KernelSU 安装 `USBLink-Mobile-0.2.15-arm64.zip`，按管理器提示完成更新。Magisk 使用 KsuWebUI，其他管理器可直接打开模块 WebUI；必要时点击模块“操作”启动管理服务。
2. 在开发者选项开启普通“USB 调试”，用于系统 RSA 电脑授权。模块不会自行更改这个开关。
3. 在“连接”页创建网络，把网络配对码交给电脑 USBLink 加入；也可以让手机加入电脑创建的网络。手机可以使用 Wi-Fi 或移动数据，实际连通性取决于网络和中继。
4. 在“设备”页开启共享。页面会等待网络、系统 adbd 和端口防护确认就绪，再显示电脑连接地址。
5. 电脑需同步更新 USBLink 至 **0.3.10 或以上**，在连接页选择手机查看经过认证的共享状态；手机关闭共享时断网，ADB 就绪时可复制连接命令。电脑使用现有 SDK 的命令 `adb connect <手机虚拟地址>:3242`。首次连接解锁手机、核对并允许电脑 RSA 指纹；出现 `unauthorized` 时检查手机授权弹窗。
6. 已授权后可使用 `adb -s <手机虚拟地址>:3242 install app.apk`、shell、logcat 和 Android Studio 调试。断网后的 ADB 重连由电脑手动执行。
7. 关闭共享会撤销监听和会话，恢复模块接管前的 TCP 属性，并确认内部监听停止。恢复失败时点击“关闭共享并重试恢复”，不要把错误当作已完整恢复。

**切换模式会重启系统 adbd，可能短暂断开已有 USB / 网络调试连接。日常启停不需要重启整部手机。** USB 调试保持开启不表示模块共享已开启。

从 0.1.x 升级后，旧的无线共享开启偏好不会自动开启新方案，需要手动启用一次。此后 TCP 模式的共享开关会保存；持久开启时服务启动会恢复开启意愿。失败不会在当前进程内不断重启 adbd，需明确关闭再开启。

### 旧 TCP 构建的实现范围

本版不包含 OTG U 盘、外接手机、MTP、fastboot 或完整 USB/IP 导出。Windows 原有 USB/IP 共享与挂载功能保留，手机的这个通道不会变成 Windows 的实体 USB 设备。

模块不捆绑另一套 adb，不操作电脑的 adb server，不关闭 ADB 身份认证或 SELinux。要求 ROM 的 `ro.adb.secure=1`、普通 USB 调试开启，允许 Root 修改临时 service 属性并重启系统 adbd。缺少这些能力时显示错误，不绕过系统保护。

### 旧 TCP 构建的访问限制与恢复

- 本机管理为 Unix 域套接字，双向核验 Root UID，无网络 Root 命令接口。
- 在线检测只绑定实际 EasyTier 地址的 3241 端口，使用配对密钥认证随机挑战和共享状态；该接口只读，不接收 Root 操作。共享关闭时停止响应并断网，服务退出、换网或状态过期后撤销。旧 Windows 版不认识这些状态，需要两端一起更新。
- 对外代理只绑定实际 EasyTier 地址的 3242 端口，IPv4 / IPv6 都限制入口接口为 usblink0。
- 系统 adbd 的临时内部端口为 15558。先限制非本机入站以及非 Root 本机访问，再修改 `service.adb.tcp.port`；从不修改持久属性，不关闭 RSA 认证。
- 传统 ADB TCP 不自带 TLS；远端链路由 EasyTier AES 加密，电脑认证由 Android RSA 机制负责。
- 私有恢复记录先落盘，保存原 service 属性、持久属性快照与 boot ID。清理只处理模块自己的记录和精确标记规则。遇到外部修改不覆盖，遇到失败不提前移除防护。
- 开机不会恢复上一启动周期的临时属性；模块进程异常退出后，监督程序尝试恢复。Root 进程仍可能受 ROM / SELinux 限制，这不是“永不被杀”的保证。
- 共享意愿与实际就绪分别展示，网络凭据不写前端持久化、不出现在进程参数或日志。

当前没有在 K40 上安装和运行 0.2.5，主机编译、模拟生命周期与 56 MiB 传输测试不能替代手机真实授权、IPv6/防火墙、异常退出恢复及跨网络安装验证。

## 构建与测试

依赖 JDK 11+、Android SDK platform 35 / build-tools 35.0.0、Node.js 以及仓库根目录 npm 依赖：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/fetch-easytier.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -UsbAdb
```

构建产物：`release/USBLink-Mobile-0.3.36-usb-adb-arm64.zip`，同目录生成 SHA256 文件。构建会执行管理器安装／BusyBox 发现、核心、组网、网络观察故障、名称、认证、TCP 恢复/防护、重启准备、ADB 历史及返回解析、USB/IP 协议、模块集成、桌面进程交接和 WebUI 测试，然后编译 DEX 与网页。不带 `-UsbAdb` 时生成 TCP 0.2.21 模块；旧参数 `-ExperimentalUsb` 作为别名兼容。

安装 Git Bash 后可运行脚本模拟测试：

```powershell
node mobile/tests/script-lifecycle.mjs
node mobile/tests/wrapper-lifecycle.mjs
node mobile/tests/action-lifecycle.mjs
node mobile/tests/root-managers.mjs
```

历史主机检查使用真实 SQLite JDBC（固定版本及 SHA-256，缓存在 mobile/build/test-libs），仅供测试，不打包到手机；手机使用 Android 内置 SQLite。其余测试使用夹具，不操作真实手机、路由或防火墙。浏览器交互预览仅在显式 `?preview=1` 时启用，并标明示例数据；生产环境缺少管理器桥接会显示不可用。

EasyTier 2.6.4 官方 ARM64 文件保持未修改，附 LGPL / GPL 全文、源码链接与 SHA256 清单，见 `vendor/easytier/NOTICE.md`。
