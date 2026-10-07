# USBLink Mobile

面向 Android 12 及以上（API 31+）、ARM64、APatch 的手机模块，兼容 KernelSU 模块/WebUI。作者：一只小柒夏。目标真机是 Redmi K40 + Android 12 + APatch。后端为 DEX JAR + app_process，没有独立 APK。

**独立的 USB ADB 实验模块 0.3.3**：电脑可在 USBLink 0.3.11 点击连接，现有 ADB 经虚拟 USB 接口发现手机。保留重启恢复共享修复、市场名称显示、蓝白 WebUI 和静默自动同步；安装最低要求改为 Android 12。见 [实验模块说明](experimental/usbip/MODULE.md)。它不支持 MTP / OTG；以下 0.2.2 说明仅适用于默认 TCP 构建。

## 当前发布：USB ADB 实验模块 0.3.3

下载：[Windows 0.3.11 + Mobile 0.3.3 正式发布版](https://github.com/cinitdev/USBLink/releases/tag/v0.3.11)。手机安装 `USBLink-Mobile-0.3.3-usb-adb-experimental-arm64.zip`，电脑安装 `USBLink-0.3.11-x64-Setup.exe`。已使用 Windows 0.3.11 时无需重复安装。

1. 在 APatch 模块页覆盖安装 ZIP，按管理器提示完成更新或重启。同一 USB 实验通道的组网配置和共享开关会保留；从旧 TCP 模块升级时不会自动开启新 USB 通道。
2. 手机开发者选项开启普通 **USB 调试**，不需要无线调试，也不要求手机接到另一台电脑上。
3. 手机 WebUI 与电脑 USBLink 创建／加入同一个网络，保管好配对码。
4. 手机“设备”页开启“共享这台手机”。重启后会保留开启意愿，等待系统完成启动、网络防护稳定后才恢复共享；等待期间可关闭开关。
5. Windows“连接”页选择手机和它的共享设备，点击“连接设备”。首次连接请在手机确认电脑 RSA 授权，之后电脑现有 ADB / Android Studio 可使用该 USB 调试接口。**无需执行 `adb connect`**。
6. Windows 可单独断开该设备；手机关闭共享会撤销所有本机共享会话。掉线后由用户手动重新连接，不会自动重挂载。

模块显示手机系统提供的市场名称，再回退设备名称或原型号，不使用机型表。WebUI 使用蓝白／藏蓝配色，后台静默同步状态，保留正在编辑的表单；从后台返回立即读取新状态。

此版本只有 Android ADB Interface，**没有 MTP 便携存储、Fastboot、外接 U 盘或外接手机共享**。独立试验已在 Redmi K40／Android 12／APatch 验证驱动、认证、64 MiB 双向传输和清理；用户反馈早期整合模块可用。最新重启修复通过自动回归，尚未完成新版刷入后的重启、Android Studio 安装 APK 和多品牌真机验收。天玑等 ARM64 设备具备架构基础，ROM 和 Root 兼容性仍需实测。细节见 [模块说明](experimental/usbip/MODULE.md) 与 [重启修复验证记录](docs/reboot-sharing-0.3.2-validation.md)。

## 旧 TCP 模块 0.2.2（开发兼容路径）

以下说明仅适用于旧 TCP 构建，不是本次发布的 USB 实验 ZIP 使用步骤。

本版共享手机自己的 **系统 adbd TCP 调试**，经 EasyTier 加密网络连接电脑现有 ADB / Android Studio。已移除系统无线调试的 TLS 配对通道、mDNS 发现、配对窗口与手动端口。**不需要开启无线调试，不需要 USB 线**。

1. 在 APatch 覆盖安装 `USBLink-Mobile-0.2.2-arm64.zip`，按管理器提示完成更新。打开模块 WebUI，必要时点击模块“操作”启动管理服务。
2. 在开发者选项开启普通“USB 调试”，用于系统 RSA 电脑授权。模块不会自行更改这个开关。
3. 在“连接”页创建网络，把网络配对码交给电脑 USBLink 加入；也可以让手机加入电脑创建的网络。手机可以使用 Wi-Fi 或移动数据，实际连通性取决于网络和中继。
4. 在“设备”页开启共享。页面会等待网络、系统 adbd 和端口防护确认就绪，再显示电脑连接地址。
5. 电脑需同步更新 USBLink 至 **0.3.10 或以上**，在连接页选择手机查看经过认证的共享状态；手机关闭共享时仍应显示在线，ADB 就绪时可复制连接命令。电脑使用现有 SDK 的命令 `adb connect <手机虚拟地址>:3242`。首次连接解锁手机、核对并允许电脑 RSA 指纹；出现 `unauthorized` 时检查手机授权弹窗。
6. 已授权后可使用 `adb -s <手机虚拟地址>:3242 install app.apk`、shell、logcat 和 Android Studio 调试。断网后的 ADB 重连由电脑手动执行。
7. 关闭共享会撤销监听和会话，恢复模块接管前的 TCP 属性，并确认内部监听停止。恢复失败时点击“关闭共享并重试恢复”，不要把错误当作已完整恢复。

**切换模式会重启系统 adbd，可能短暂断开已有 USB / 网络调试连接。日常启停不需要重启整部手机。** USB 调试保持开启不表示模块共享已开启。

从 0.1.x 升级后，旧的无线共享开启偏好不会自动开启新方案，需要手动启用一次。此后 TCP 模式的共享开关会保存；持久开启时服务启动会恢复开启意愿。失败不会在当前进程内不断重启 adbd，需明确关闭再开启。

### 旧 TCP 构建的实现范围

本版不包含 OTG U 盘、外接手机、MTP、fastboot 或完整 USB/IP 导出。Windows 原有 USB/IP 共享与挂载功能保留，手机的这个通道不会变成 Windows 的实体 USB 设备。

模块不捆绑另一套 adb，不操作电脑的 adb server，不关闭 ADB 身份认证或 SELinux。要求 ROM 的 `ro.adb.secure=1`、普通 USB 调试开启，允许 Root 修改临时 service 属性并重启系统 adbd。缺少这些能力时显示错误，不绕过系统保护。

### 旧 TCP 构建的访问限制与恢复

- 本机管理为 Unix 域套接字，双向核验 Root UID，无网络 Root 命令接口。
- 在线检测只绑定实际 EasyTier 地址的 3241 端口，使用配对密钥认证随机挑战和共享状态；该接口只读，不接收 Root 操作。共享关闭时继续响应，服务退出、换网或状态过期后撤销。旧 Windows 版不认识这些状态，需要两端一起更新。
- 对外代理只绑定实际 EasyTier 地址的 3242 端口，IPv4 / IPv6 都限制入口接口为 usblink0。
- 系统 adbd 的临时内部端口为 15558。先限制非本机入站以及非 Root 本机访问，再修改 `service.adb.tcp.port`；从不修改持久属性，不关闭 RSA 认证。
- 传统 ADB TCP 不自带 TLS；远端链路由 EasyTier AES 加密，电脑认证由 Android RSA 机制负责。
- 私有恢复记录先落盘，保存原 service 属性、持久属性快照与 boot ID。清理只处理模块自己的记录和精确标记规则。遇到外部修改不覆盖，遇到失败不提前移除防护。
- 开机不会恢复上一启动周期的临时属性；模块进程异常退出后，监督程序尝试恢复。Root 进程仍可能受 ROM / SELinux 限制，这不是“永不被杀”的保证。
- 共享意愿与实际就绪分别展示，网络凭据不写前端持久化、不出现在进程参数或日志。

当前没有在 K40 上安装和运行 0.2.2，主机编译、模拟生命周期与 56 MiB 传输测试不能替代手机真实授权、IPv6/防火墙、异常退出恢复及跨网络安装验证。

## 构建与测试

依赖 JDK 11+、Android SDK platform 35 / build-tools 35.0.0、Node.js 以及仓库根目录 npm 依赖：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/fetch-easytier.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb
```

发布产物：`release/USBLink-Mobile-0.3.3-usb-adb-experimental-arm64.zip`，同目录生成 SHA256 文件。构建会执行核心、组网、名称、认证、TCP 恢复/防护、重启准备、USB/IP 协议、模块生命周期和 WebUI 测试，然后编译 DEX 与网页。不带 `-ExperimentalUsb` 时仍生成旧 TCP 模块，默认构建不混入实验 USB 实现或标识。

安装 Git Bash 后可运行脚本模拟测试：

```powershell
node mobile/tests/script-lifecycle.mjs
node mobile/tests/wrapper-lifecycle.mjs
node mobile/tests/action-lifecycle.mjs
```

这些测试只使用夹具，不操作真实手机、路由或防火墙。浏览器交互预览仅在显式 `?preview=1` 时启用，并标明示例数据；生产环境缺少管理器桥接会显示不可用。

EasyTier 2.6.4 官方 ARM64 文件保持未修改，附 LGPL / GPL 全文、源码链接与 SHA256 清单，见 `vendor/easytier/NOTICE.md`。
