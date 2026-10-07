# Windows 0.3.11 / Mobile 0.3.3 发布验证

日期：2026-10-07。发布渠道：GitHub v0.3.11 正式发布。转为正式发布时仅更新发布状态和文档，安装包与校验值保持不变。

## 构建与检查

| 检查 | 结果 |
| --- | --- |
| `npm test` | 19 项通过 |
| `cargo test --manifest-path src-tauri/Cargo.toml` | 89 项通过，4 项按原配置忽略 |
| 单独运行 `android_usb_metadata_java_interoperability -- --ignored` | 使用本次 Java 构建夹具，1 项通过 |
| `npm run tauri:build` | Windows x64 Release 与简体中文 NSIS 安装包构建成功 |
| `mobile/scripts/build.ps1 -ExperimentalUsb` | 后端、DEX、WebUI 与实验模块 ZIP 构建成功 |
| Android 核心／组网／名称／认证 | 分别 56 / 52 / 22 / 50 项通过 |
| adbd 与防火墙／重启准备 | 分别 113 / 50 项通过 |
| USB/IP 协议／实验模块生命周期及认证 | 分别 575 / 27 项通过 |
| WebUI 自动测试 | 17 项通过 |
| `script-lifecycle.mjs` | 62 项检查通过 |
| `wrapper-lifecycle.mjs` / `action-lifecycle.mjs` | 分别 5 / 10 个场景通过 |
| 安装版本检查 | API 31、32、33、35 接受；29、30、空值和无效版本拒绝 |
| 桌面浏览器预览 | 显式 Android USB 示例下，行内单次连接、单设备断开反馈正常；未记录浏览器错误 |
| 独立试验脚本 | 3 个脚本的 `node --check` 通过；本次没有运行真机试验 |

## 产物

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `USBLink-0.3.11-x64-Setup.exe` | 12020514 | `37e705e49154ced227c63adcb214a27792c0e828cf4bb27fd8c196e2efe9a615` |
| `USBLink-Mobile-0.3.3-usb-adb-experimental-arm64.zip` | 8486702 | `61ba6228245778c92ef2dc7921f45288d7e272a9401ef666d02325de0de65458` |

检查 ZIP 中的模块信息、安装脚本、DEX JAR、WebUI 和第三方许可证：作者为“一只小柒夏”，版本 0.3.3，安装最低要求为 Android 12 / API 31，架构为 ARM64。Windows 安装包文件版本为 0.3.11。

## 验证边界

本轮没有安装 Windows 程序、刷入或重启手机，没有启动桌面 ADB，也没有操作真实 USB 挂载。浏览器示例检查只验证界面反馈；主机测试使用夹具，不能代替真实驱动、ROM 或网络验证。

先前独立 K40 试验的驱动绑定、RSA、64 MiB 双向传输和清理结果见 [试验记录](userspace-usb-first-trial.md)。最新模块的刷入重启、Android Studio APK 安装和其他品牌／天玑真机兼容尚未验收。本发布不支持 MTP、Fastboot 或 OTG 共享，也不将原 Windows USB/IP 的大 APK 掉线问题标记为彻底解决。
