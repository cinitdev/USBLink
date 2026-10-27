# Windows 0.3.12 / Mobile 0.3.12 发布验证

日期：2026-10-27。GitHub v0.3.12 正式发布，设为 Latest。汇总上一公开版本 Windows 0.3.11 / Mobile 0.3.3 之后的本地改动。

## 本次发布检查

- `npm run tauri:build`：前端、Rust Release 与 Windows x64 中文 NSIS 安装程序成功构建，安装包产品版本为 0.3.12。
- `mobile/scripts/build.ps1 -ExperimentalUsb -SkipTests`：Java、DEX JAR、WebUI 和模块 ZIP 成功构建，跳过全量测试。
- 构建在沙箱内遇到目录访问／Java 编译异常，经授权在沙箱外执行上述构建后成功；未修改构建源代码以绕过权限。
- ZIP 核对版本 0.3.12、versionCode 312、作者“一只小柒夏”、DEX、WebUI 与第三方许可证。启动恢复的三个脚本、安装脚本及模块说明与当前源码一致，脚本使用 LF。
- 两个安装文件已生成 SHA-256；`git diff --check` 通过。发布说明覆盖全部未发布功能，标明验证边界。
- 没有重复运行全量测试，没有安装程序、刷模块、启动桌面 ADB、连接真实 USB 或更改手机状态。

## 复用的既有验证

- Windows 多来源：23 项前端测试、90 项 Rust 测试通过，4 项忽略；浏览器验证来源切换、搜索、跨来源同号设备及单设备断开。详见 [多设备验证](multi-network-0.3.12-validation.md)。
- Android 端口复用、双向传输、历史记录、Root、管理器和网络观察的主机验证见 [手机文档目录](../mobile/docs/)。这些是各次实现时的结果，本次未重新运行。
- 0.3.11 路由修复：87 项网络观察定向检查通过；0.3.12 启动恢复：75 项脚本生命周期检查通过。
- K40 / Android 12 的 0.3.11 安装已应用启动脚本补丁，服务恢复，sharing active、mesh running、presence ready 均确认；配对、共享偏好及历史文件前后 SHA-256 一致。损坏的进程记录与快照已归档，数据损坏的最初原因未确认。

## 发布产物

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `USBLink-0.3.12-x64-Setup.exe` | 12028379 | `d6cdcd6f87ad60377bfb2249ad5b8562bf282cbec6b0d9836bf8e95e909ce568` |
| `USBLink-Mobile-0.3.12-usb-adb-experimental-arm64.zip` | 8515269 | `71c45d8561381d0c265c8feb5ce595ce2f18c9289dee64ae2ab9ed38bb309be1` |

手机 ZIP 因重新构建和更新随包文档，校验值不同于 2026-10-21 的本地包；请使用本发布附带的 `SHA256SUMS-v0.3.12.txt`。

## 验证边界

完整 0.3.12 ZIP 尚未刷入真机。K70 / Android 15 网络修复、Magisk／KernelSU 真机、ROM Root 切换、Android Studio 安装 APK、多手机同时传输及长期稳定性仍待验收；不能将历史独立 K40 试验等同于本版本完整包验收。

手机模块仍仅提供实验 USB ADB，不支持 MTP、Fastboot、OTG U 盘或外接手机共享；不保证所有品牌、天玑机型或 ROM 兼容，不宣称所有掉线已解决或已测得省电比例。正式发布渠道不改变上述实验范围。
