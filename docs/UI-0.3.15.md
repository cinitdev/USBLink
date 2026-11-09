# 0.3.15 本地构建与验证记录

日期：2026-11-03。未发布 GitHub，未安装到真实手机。

## 变更

- Android 手动开启共享前，WebUI 需要新鲜的 USB 调试开启观察；守护进程在保存偏好或启动网络前再次读取系统 `adb_enabled`。关闭、未知、读取失败均拒绝，关闭共享始终可用。检测不修改系统设置。
- Windows 设备、连接和设置采用手机 WebUI 的 AppOpt 蓝白 / 藏蓝主题。连接页为可搜索来源列表 + 当前来源 USB 详情；同一详情区保留跨来源的本机挂载及单个断开。来源和设备数量不设产品上限。
- 没有增加 USB 重挂载、桌面 ADB 启动或后台重试。原有配对、安全检查、串行操作和驱动确认继续沿用。

## 针对性验证

- 宿主：新增 `UsbDebuggingTestMain` 检查关闭 / 未知拒绝无状态修改、开启后允许、未开启调试时仍可关闭共享；通过。
- 宿主：既有 `AdbRootTestMain` 的 75 项检查通过。WebUI 环境检测和桌面来源 / 挂载状态共 25 项测试通过。没有重跑大文件传输或整套测试。
- 浏览器显式示例数据：USB 调试关闭 / 未知均禁止开启；桌面在 12 个来源中搜索、切换，两部手机相同 bus ID 分别连接，断开其中一台后另一台保留。检查 820×600 最小窗口无横向溢出，以及设备 / 设置分组。该预览不证明真实 USB 挂载。
- 深色藏蓝样式通过构建并保留系统主题媒体查询；未切换用户系统主题进行验收。

## 虚拟机上的真实检测

目标是已运行的 Pixel_9_Pro / emulator-5554，系统报告 Android 17、Root 可用、`ro.debuggable=0`。使用现有桌面 ADB，未重启 ADB 服务器、未接触真实手机。

直接执行模块 `usblinkctl environment` 的实际脚本，未替换系统命令：

```json
{"ok":true,"data":{"usbDebugging":true,"shellUid":2000}}
{"ok":true,"data":{"usbDebugging":true,"shellUid":0}}
```

第一行为普通 Shell，第二行为 `su` Shell。因此检测能够区分控制 Shell 是否有 Root，且不会把设备已有 Root 等同于当前 Shell 有 Root。该检查不等同于 APatch / Magisk / KernelSU WebUI 宿主的完整授权流程验收。

`mobile/daemon/device-test/.../AdbRootObservationProbe.java` 在虚拟机运行实际 `AndroidPlatform`，使用独立临时偏好目录；不启动共享或网络，不打进模块。关闭偏好后实际状态为 `enabled=false, active=false, state=shell`；开启偏好后为 `enabled=true, active=false, state=unsupported`。前后 adbd PID、`service.adb.root` 和 TCP 属性不变，检查通过。

**本虚拟机 ROM 不支持 adbd Root。已证明准确检测 / 拒绝，并没有证明 adbd 在支持的 ROM 上完成 Root → Shell → Root 切换。** 有系统 Root 不代表系统 adbd 支持 Root。用户的 K40 / K70 ROM 是否支持，需要在其具体 ROM 上另行验证，不能从这个结果推断。

## 构建

- Windows：`npm run tauri:build`，产物为中文 NSIS Setup.exe。
- 手机：`powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb -SkipTests`，Android 12+ ARM64 USB ADB 模块 ZIP。测试在构建前单独执行。
- 实验能力仍限于 USB ADB，不包含 MTP、外接手机或 U 盘共享。
