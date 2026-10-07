# USB ADB 实验模块交付检查 · 2026-10-07

交付：Mobile 0.3.0 USB ADB 实验 ZIP + Windows 0.3.11 中文 NSIS 安装程序。当前未刷入此整合 ZIP，也未自动再次挂载真机。

- 手机 Java：56 项后台、52 项组网、50 项在线认证、94 项 adbd 属性/防护/恢复、575 项 USB/IP 协议、27 项实验模块生命周期/导出认证检查通过。
- 手机 WebUI：14 项接口与并发交互测试通过；实验版页面已在本地预览检查连接步骤。
- Windows：89 项常规 Rust 测试通过；另行运行 Java 手机服务与 Rust 客户端的 USB 签名元数据互通测试通过（真实本机套接字，无 USB 驱动操作）。硬件挂载测试保持忽略。
- 电脑前端：19 项测试通过；最后收紧 Android USB 就绪判定后，6 项在线/连接状态测试再次通过，缺失 usbReady 不能授权连接。预览已确认手机型号、USB ADB 范围说明和单设备连接入口出现。
- 默认手机源集另行编译并通过 52 项组网检查，没有纳入实验 USB 类；保留原默认 TCP 构建。
- 实验 ZIP 完整性、DEX 类、管理器入口、WebUI、许可证、脚本 LF、实验说明和 SHA256 已检查；EasyTier 文件在构建时按上游校验值核对。
- Windows 安装包构建成功。未执行安装程序，未改变已有安装或配对配置。

独立 K40 试验的驱动绑定、认证、64 MiB 双向传输与清理结果见 `userspace-usb-first-trial.md`。它不能代替本次整合 ZIP 的真机验收；MTP、APK 安装及 Android Studio 实际操作尚未作为本版本验收。

制品 SHA256：

```text
4736238d3ae68bfe9dc6e8b9a505d163449755ac92f0dbcf2056ca2c5a750ad8  USBLink-Mobile-0.3.0-usb-adb-experimental-arm64.zip
2163d76927a84bc3b3a072fa3d34ef4c9de943c918609530e571a7d7eca5a589  USBLink-0.3.11-x64-Setup.exe
```
