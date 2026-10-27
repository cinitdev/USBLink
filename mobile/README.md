# USBLink Mobile

面向 Android 12 及以上（API 31+）、ARM64 的 Root 手机模块，同一 ZIP 支持 Magisk、APatch 和 KernelSU。Magisk 用户通过独立 KsuWebUI 应用打开界面；APatch／KernelSU 可使用管理器内置 WebUI。作者：一只小柒夏。已有真机目标是 Redmi K40 + Android 12 + APatch；Magisk／KernelSU 尚未完成真机验收。后端为 DEX JAR + app_process，没有独立 USBLink APK。

**独立的 USB ADB 实验模块 0.3.12（随 v0.3.12 正式发布）**：电脑可在 USBLink 0.3.11 或更新版本点击连接，现有 ADB 经虚拟 USB 接口发现手机。保留 ADB Root 开关、请求历史、传输修复、断网省电、市场名称、蓝白 WebUI 和静默自动同步。见 [实验模块说明](experimental/usbip/MODULE.md)。它不支持 MTP / OTG。

## 0.3.12：损坏运行记录的启动恢复

模块数据在 `/data/adb/usblink/`。进程记录损坏时，持有启动锁并确认无模块进程后，备份并移走损坏记录，保留配对、共享偏好、历史与恢复日志。相关脚本已在 K40 / Android 12 上确认恢复服务；完整 ZIP 尚未重新刷入测试。

## 0.3.11：路由检查误断开修复

路由读取时限改为 4 秒，单次失败后进行一次只读核实，兼容等价的数字路由表名；添加规则结果不明时读取实际状态，绝不重复添加。确认缺失／冲突仍停止共享，并分别记录具体错误。仅运行网络观察回归（87 项通过）后打包，未在 K70 实测，Windows 无需更新。

## 0.3.10：网络状态查询误断开修复

状态查询超时或返回不完整时，分别验证原 EasyTier 进程、同一 `usblink0` 网卡与 IP、实际路由；这些仍正常才保留已有 USB 会话。不沿用过期中继／设备在线结果，WebUI 显示“状态待确认”，状态查询恢复后自动同步。接口消失／重建、IP 改变、进程退出或路由无法确认仍撤销会话，并显示细分原因；不自动重新挂载。

适用于用户反馈的 K70 / Android 15 / 0.3.8 闲置也出现 `NETWORK_UNAVAILABLE` 的排查。确定并修复了代码中的误撤销路径，尚未取得该手机故障现场日志或刷入新包实测。回归范围与限制见 [验证记录](docs/network-observation-0.3.10-validation.md)。默认 TCP 开发模块共享此修复，版本为 0.2.7。

## 0.3.9：Magisk / APatch / KernelSU

- 三种管理器安装同一个 ZIP，当前为 `USBLink-Mobile-0.3.12-usb-adb-experimental-arm64.zip`，无需按 Root 类型分别下载。Magisk 要求 20.4+；所有管理器均要求 Android 12+、ARM64。
- **Magisk**：在 Magisk 模块页安装 ZIP，按管理器提示完成首次重启；自行安装 [KsuWebUI](https://github.com/KOWX712/KsuWebUIStandalone)，授予它 Root 权限，打开后选择 USBLink。Magisk 官方没有内置 WebUI，模块“操作”按钮用于启动／检查后台，随后在 KsuWebUI 打开界面。
- **APatch / KernelSU**：安装 ZIP 后按管理器提示完成更新，在模块 WebUI 打开；也可使用已获 Root 授权的 KsuWebUI。
- 后台使用对应管理器自带的 BusyBox，Magisk 新增 `/data/adb/magisk/busybox` 路径；找不到 `flock` 时仍拒绝无锁启动。关闭界面不会停止共享服务，模块禁用／卸载仍执行原清理流程。
- KsuWebUI 复用现有 `ksu.exec` 回调接口；不自动下载 APK、不创建网络 Root 控制入口、不绕过 Root 授权。默认 TCP 开发构建同步为 0.2.6。

版本 0.3.9 的管理器兼容性经过源码核对、安装／运行路径夹具测试和主机构建，尚未刷入 Magisk／KernelSU 真机。Root 管理器兼容不代表所有 ROM 支持 adbd Root，详见 [验证记录](docs/root-managers-0.3.9-validation.md)。

## 0.3.8：ADB Root 权限开关

手机 WebUI“设置 → ADB 权限”新增默认开启、持久保存的 Root 偏好。系统启动完成、adbd 就绪后应用；开机期间可先关闭取消启用。运行中启停不需要重启手机，但会重启系统 adbd、中断 USB 与远程调试，电脑需手动重新连接。实际权限按系统 adbd 的进程身份与 UID 校验，切换未确认时显示等待／错误，失败不在当前进程内自动重复重启。

仅使用 ROM 已支持的 `service.adb.root` 能力。APatch / KernelSU 能让模块以 Root 运行，不能证明量产版 adbd 支持 Root；不支持时显示原因，普通权限共享仍可使用。未确认降为普通 UID 2000 时暂停远程共享。保持 RSA、SELinux、系统 adbd 二进制及 USB 调试开关原样。

Root 偏好与共享／组网独立，切换 Root 不会启动已关闭的共享或网络。停止模块尝试恢复原临时属性和权限，外部冲突不覆盖，失败保留恢复记录。默认 TCP 开发构建同步至 0.2.5。此次没有在真实手机修改权限或安装模块；见 [ADB Root 验证说明](docs/adb-root-0.3.8-validation.md)。

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

## 当前发布：USB ADB 实验模块 0.3.12

下载：[Windows 0.3.12 + Mobile 0.3.12 正式发布版](https://github.com/cinitdev/USBLink/releases/tag/v0.3.12)。手机安装 `USBLink-Mobile-0.3.12-usb-adb-experimental-arm64.zip`，电脑安装 `USBLink-0.3.12-x64-Setup.exe`。Windows 0.3.11 仍兼容；新的可搜索多设备列表需更新电脑版。正式发布渠道不改变手机 USB ADB 功能的实验范围。

1. 在 Magisk／APatch／KernelSU 模块页覆盖安装 ZIP，按管理器提示完成更新或重启。同一 USB 实验通道的组网配置和共享开关会保留；从旧 TCP 模块升级时不会自动开启新 USB 通道。
2. 手机开发者选项开启普通 **USB 调试**，不需要无线调试，也不要求手机接到另一台电脑上。
3. 手机 WebUI 与电脑 USBLink 创建／加入同一个网络，保管好配对码。
4. 手机“设备”页开启“共享这台手机”。重启后会保留开启意愿，等待系统完成启动、网络防护稳定后才恢复共享；等待期间可关闭开关。
5. Windows“连接”页选择手机和它的共享设备，点击“连接设备”。首次连接请在手机确认电脑 RSA 授权，之后电脑现有 ADB / Android Studio 可使用该 USB 调试接口。**无需执行 `adb connect`**。
6. Windows 可单独断开该设备；手机关闭共享会撤销所有本机共享会话。掉线后由用户手动重新连接，不会自动重挂载。

模块显示手机系统提供的市场名称，再回退设备名称或原型号，不使用机型表。WebUI 使用蓝白／藏蓝配色，后台静默同步状态，保留正在编辑的表单；从后台返回立即读取新状态。

此版本只有 Android ADB Interface，**没有 MTP 便携存储、Fastboot、外接 U 盘或外接手机共享**。独立试验已在 Redmi K40／Android 12／APatch 验证驱动、认证、64 MiB 双向传输和清理；用户反馈早期整合模块可用。最新重启修复通过自动回归，尚未完成新版刷入后的重启、Android Studio 安装 APK 和多品牌真机验收。天玑等 ARM64 设备具备架构基础，ROM 和 Root 兼容性仍需实测。细节见 [模块说明](experimental/usbip/MODULE.md) 与 [重启修复验证记录](docs/reboot-sharing-0.3.2-validation.md)。

## 旧 TCP 模块（开发兼容路径）

以下说明仅适用于默认 TCP 构建，不是 USB 实验 ZIP 使用步骤。当前源码版本为 0.2.8（本次未另行构建 TCP ZIP），共享开关同样控制网络；已发布历史文档中的 0.2.2 关闭后在线行为不再适用。

本版共享手机自己的 **系统 adbd TCP 调试**，经 EasyTier 加密网络连接电脑现有 ADB / Android Studio。已移除系统无线调试的 TLS 配对通道、mDNS 发现、配对窗口与手动端口。**不需要开启无线调试，不需要 USB 线**。

1. 在 Magisk／APatch／KernelSU 覆盖安装 `USBLink-Mobile-0.2.8-arm64.zip`，按管理器提示完成更新。Magisk 使用 KsuWebUI，其他管理器可直接打开模块 WebUI；必要时点击模块“操作”启动管理服务。
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
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb
```

构建产物：`release/USBLink-Mobile-0.3.12-usb-adb-experimental-arm64.zip`，同目录生成 SHA256 文件。构建会执行管理器安装／BusyBox 发现、核心、组网、网络观察故障、名称、认证、TCP 恢复/防护、重启准备、ADB 历史、ADB Root、USB/IP 协议、模块集成和 WebUI 测试，然后编译 DEX 与网页。不带 `-ExperimentalUsb` 时生成 TCP 0.2.8 模块，默认构建不混入实验 USB 实现或标识。

安装 Git Bash 后可运行脚本模拟测试：

```powershell
node mobile/tests/script-lifecycle.mjs
node mobile/tests/wrapper-lifecycle.mjs
node mobile/tests/action-lifecycle.mjs
node mobile/tests/root-managers.mjs
```

这些测试只使用夹具，不操作真实手机、路由或防火墙。浏览器交互预览仅在显式 `?preview=1` 时启用，并标明示例数据；生产环境缺少管理器桥接会显示不可用。

EasyTier 2.6.4 官方 ARM64 文件保持未修改，附 LGPL / GPL 全文、源码链接与 SHA256 清单，见 `vendor/easytier/NOTICE.md`。
