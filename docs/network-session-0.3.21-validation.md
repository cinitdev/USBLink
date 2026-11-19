# VPN 路由下的断流会话诊断与修复

日期：2026-11-19。目标为用户当前连接的 Redmi K70 / Android 15，安装模块版本 0.3.35。用户明确允许在保持故障现场的情况下通过 USB 只读检查；未断开、重启或刷写设备。

## 真机证据

- 模块目录元数据确认 `usblink-mobile` / 0.3.35。守护进程记录 USB/IP `CLIENT_IO`，状态快照和 socket 表仍有指向电脑的活动 USB/IP 会话，所以前次清理没有释放该占用。
- Windows 0.3.20 和既有桌面进程当前存在，配对认证查询当时可达。电脑仍有手机旧端口连接处于关闭清理阶段，以及模块服务端口监听。
- 手机路由查询显示，root 进程对电脑虚拟网地址的默认选择会进 singBox service 路由；将已分配的 USBLink 地址作为源地址后，内核选择 `usblink0`。0.3.35 的 `DesktopOwner` 用未绑定源地址的 `new Socket()` 回连电脑 3241，因此代理的 SYN-SENT 连接无法观察电脑 USB/IP 四元组。该条件使 `releaseReplaced` 一直收到未知观察，按现有安全策略保留旧会话。

没有从真机确认修复后的认证响应。模块未刷写，且没有临时上传测试程序或重启服务；因此这是由源代码和路由表相互印证的根因，不是本次真机复测通过的声明。

## 修复

- `DesktopOwner.queryTransport` 和旧协议 `query` 均将出站检测 socket 绑定至原 USB/IP accepted socket 的本地地址及动态端口。它不写死网卡名/IP；路由由 Android 的地址和策略表决定。如果地址不再可绑定，观察保持未知，不回退到错误网卡，不提前释放会话。
- Windows 手动 attach 路径最多等候 6 秒，对认证应用存在状态进行只读轮询，吸收切换网络时的一次短暂失败。仅当观察变为在线且 USB 服务通过现有检查后，继续本次唯一人工 attach；不在失败后自动 attach，也不应用到周期发现、设备列表刷新或页面浏览。

## 定向验证

- `DesktopOwnerTestMain`：5 组 owner 认证、7 组 transport 认证、绑定源地址的旧版协议回退、无效／失效源地址不发起未绑定连接，全部通过。
- `OwnerLifecycleTest`：79 项断言通过，覆盖已确认的四元组消失、未知／健康状态保护、后台释放、更新交接和只接受一次新的人工导入。未连接 K70 USB/IP 或更改桌面挂载。
- `cargo test --offline peer_health::`：5 项通过，覆盖在线阶段、共享授权和短暂失败等待。
- `mobile/scripts/build.ps1 -UsbAdb -SkipTests`：生产 WebUI、DEX 模块 ZIP 构建成功；按用户节省测试额度要求跳过全量移动端 suite。Java 定向测试已单独运行。
- `npm run tauri:build`：Vite 和 Windows release/Tauri NSIS 构建成功。安装包 SHA256：`4c180ac458f03fdefcacf1dc3c0e7f58c5bdd774fb38d28a51c93c42ceb09798`。最终产物的 NTFS 完整性标签已从生成目录继承的 Low 显式改为 Medium，EXE 字节及 SHA256 未改变。
- 手机包 `USBLink-Mobile-0.3.36-usb-adb-arm64.zip` SHA256：`a536bbca3a2999663ec053ea4c51812ae91d35406a9a7c3d52177f13e14d06f0`。

构建仅产出本地文件；没有安装 Windows 更新、刷写手机、制造新的网络中断、自动重新连接、推送或发布 GitHub。真机验证仍需用户安装两端新版后遇到同类网络切换时观察旧会话是否自行清理及一次手动连接能否成功。
