# USBLink

USBLink 是一个中文 Windows 桌面工具，为免费的 USB/IP 组件提供图形界面。它内置 EasyTier 加密组网，可以共享和连接任意数量的 USB 设备，不需要账号或命令行。

## 运行方式

同一个 `USBLink.exe` 可以安装在两台电脑上：

- 一台电脑在“连接”页创建配对码，另一台电脑输入配对码加入。
- USB 所在电脑在“设备”页选择需要共享的设备。
- 被控电脑在“连接”页选择远程电脑和需要连接的设备。
- “设置”页会显示内置 EasyTier，并检测 usbipd-win 和 usbip-win2。
- 安装组件后返回 USBLink，状态会立即重新检测；设置页打开期间也会自动刷新。

## 免费组件

- [EasyTier](https://github.com/EasyTier/EasyTier)：内置的去中心化加密网络，无需安装或注册账号。
- [usbipd-win](https://github.com/dorssel/usbipd-win/releases/latest)：安装在 USB 所在电脑上的共享服务。
- [usbip-win2](https://github.com/vadimgrn/usbip-win2/releases/latest)：安装在被控电脑上的远程 USB 驱动。
- 最低安全版本：usbip-win2 0.9.8.0；安装或更新后应重启 Windows。

USBLink 内置 EasyTier 2.6.4 的官方未修改二进制，并附带 LGPL-3.0 许可证和精确源码链接。首次创建或加入连接时，Windows 会请求管理员授权并安装 `USBLink EasyTier Network` 服务；配对信息由 Windows DPAPI 加密保存。首次共享设备时，USBLink 会创建一条仅允许当前 EasyTier 虚拟网段访问 TCP 3240-3241 的防火墙规则。应用会检查 Windows 中的真实规则、启动 usbipd 服务并验证本机 3240 端口，设备页也提供“修复共享”按钮。

USBLink 0.2.9 会同时检查 usbip-win2 客户端和实际安装的 UDE 内核驱动。官方已确认 0.9.7.8 存在可导致内存损坏和蓝屏的严重缺陷；旧版或无法验证版本时，应用会阻止远程挂载并引导更新。USBLink 不再自动重连，所有手动挂载均使用 `--once`。

USB/IP 自带的远程设备列表有时只会按 VID/PID 显示通用名称，例如把使用 Google ADB 标识的 Redmi 手机显示成 Pixel。两端都运行 USBLink 时，共享端会通过经过配对密钥验证的 TCP 3241 接口同步 Windows 识别到的真实友好名称；接口不可用时会退回中性设备类型，不会把 VID/PID 数据库名称当成真实机型。

设备进入已连接状态后，共享端仍会发布真实名称，接收端也会记住经过配对验证的名称；名称接口短暂不可用时，不会再从 Redmi K40 退回“Android 调试设备”。

管理员操作由 USBLink 使用 Windows 原生 `ShellExecuteExW` 完成，不依赖 PowerShell。EasyTier 服务和 USB 共享命令由隐藏的 USBLink 管理员辅助进程直接执行，防火墙配置使用 Windows 自带的 `netsh.exe`。

EasyTier 默认连接两个经过实际握手验证的社区节点：国内 `tcp://183.230.36.171:11010`，海外备用 `tcp://107.172.5.203:11010`。它会优先建立 P2P 直连，穿透失败时社区节点可能转发经过加密的流量。可以在设置页替换首选节点。

早期版本使用的 `public.easytier.top` 已经停止解析。USBLink 会自动迁移包含这个地址的本机配置和旧配对码并持久化，不会在每次启动时重复提示修复。

USBLink 启动后还会核对 Windows 中实际安装的 EasyTier 服务参数。旧服务缺少 TCP 3240-3241 白名单、备用中继或仍使用失效节点时，会请求一次管理员授权并自动升级服务配置。

## USB 连接状态

USBLink 0.2.11 使用只读的 `usbip port` 查询本机真实挂载状态。“已连接到本机”独立于远程设备列表和 EasyTier 发现结果；远程列表短暂失败不会隐藏本机仍已挂载的 USB。端口查询失败时保留上次结果并显示“状态待确认”，成功查询到空列表后才移除已连接设备。该状态表示驱动仍有挂载记录，不等于已经验证应用数据传输正常。

共享端的 `Attached` 会显示为“正在被使用”，仍属于已共享设备。连接和断开后都会重新查询状态；不会触发自动重连。

## 开发构建

```powershell
npm install
npm run build
npm test
cargo test --manifest-path src-tauri\Cargo.toml
npm run tauri:build
```

Release 程序位于 `src-tauri\target\release\USBLink.exe`。

第三方组件和许可证说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 0.2.12 修复

本版修复中继输入被刷新覆盖、失败设置被提前保存、旧配对码与旧查询覆盖新状态、刷新误解除操作锁、缓存损坏白屏，以及带空格路径开机启动失败。耗时系统命令在后台执行，组件命令具有超时处理，驱动检测独立于网络状态。详细触发场景与验证范围见 [修复记录](BUGFIX_REPORT.md)。

本机交付程序位于 `release/USBLink.exe`，修改前的备份位于 `backups/2026-09-10-bugfix/`。构建产物和本机备份不纳入 Git；克隆仓库后可按上面的步骤构建 EXE。
