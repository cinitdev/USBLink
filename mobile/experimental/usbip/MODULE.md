# USBLink Mobile 0.3.25 · USB ADB

通过 USBLink 网络连接手机，让电脑已有的 ADB / Android Studio 调试、安装应用和传输文件。作者：**一只小柒夏**。

## 设备与管理器

要求 **Android 12+（API 31+）、ARM64、Root**。同一个 ZIP 支持 Magisk、APatch、KernelSU；Magisk 要求 20.4+，通过自行安装并授予 Root 的 KsuWebUI 打开界面。APatch／KernelSU 可使用管理器内置 WebUI。后端为 DEX JAR + app_process，没有独立 USBLink APK，不自动下载 WebUI APK。

用户于 2026-11-09 反馈：已在**骁龙和天玑手机**上测试可用，因此从 0.3.25 起移除模块及安装包的实验标识。具体 ROM／管理器组合仍以设备实测为准，不限定手机品牌。

## 本版变化

- 名称为 USBLink Mobile · USB ADB，安装包为 `USBLink-Mobile-0.3.25-usb-adb-arm64.zip`，元数据、后台状态与 WebUI 版本一致。
- 保留 0.3.24 后台调用修复：支持的宿主使用异步接口，共享启动／状态同步期间可切页；旧 exec-only 宿主建议更新。历史长输出展开时渲染，刷新保留展开状态。
- 历史保留最新 **1000 条**，每页默认显示 **100 条**。保留 USB 调试检测、首次点击共享修复、多设备连接及桌面更新后旧会话释放。
- adbd Root 运行模式及实现已删除。本机 WebUI Shell Root 检测只反映管理器授权，不代表远程 ADB 权限。

## 使用

1. 在 Magisk／APatch／KernelSU 安装 ZIP，按管理器提示重启。升级保留网络、配对码、共享偏好及历史；从旧 TCP 模块升级不会自动开启 USB 共享。
2. 开启普通 USB 调试，无需无线调试。电脑安装 USBLink 0.3.19 与 usbip-win2 0.9.8.0 或更新版本。旧 Windows 0.3.11 起可连接模块；更新后旧会话释放需要新版两端配合。
3. 手机和电脑加入**同一个 USBLink 网络**，在手机开启“共享这台手机”。
4. 电脑连接页选择手机，点击“连接设备”；首次在手机允许 RSA 调试授权。电脑现有 ADB 经 USB 接口识别手机，无需 adb connect。
5. 电脑可断开单个设备。手机关闭共享会撤销会话、断开网络并恢复原调试端口，保留配对码。再次开启无需重新配对或重启手机；重启遵循保存的开关。电脑掉线后仍需手动连接。

启停共享可能重启手机系统 adbd，短暂中断其他调试。模块保留认证、SELinux、USB 调试设置和原端口，不修改电脑 ADB，不管理其他工具赋予的 adbd Root 权限。从曾注入 adbd Root 的旧模块升级，需要重启一次清除旧进程与临时挂载。

## 历史与隐私

底栏为设备 / 连接 / 历史 / 设置。历史记录模块观察到的请求、时间、来源地址及关联返回，按安装应用、应用管理、Shell、文件、调试、系统和网络分类，支持全量搜索、分页、静默同步与确认清空。

历史位于 `/data/adb/usblink/adb-history.jsonl`，不上传、不写浏览器存储。返回最多 4 KiB，超出标注截断，显示可获得的 stdout、stderr、退出码或文件协议状态。收到请求或通道关闭不等于成功。电脑原始参数和本地路径不可见时明确说明；旧输出不补造，不重放命令。认证材料、文件内容、交互按键及已识别的二进制返回不采集；可识别凭据脱敏，返回文本仍可能含个人信息。关闭共享保留历史，清空不影响连接。

## 支持范围与验证

电脑枚举为 **Android ADB Interface**。目前不提供 **MTP 便携设备、资源管理器浏览手机存储、Fastboot、外接 U 盘或外接手机共享**。USB 枚举可能触发 Windows 正常提示音。

采用现有 `18d1:4ee7` 描述符完成驱动互通；更名不改变设备协议、USB 身份或驱动兼容性，也不表示取得独立 USB 标识或驱动认证。

用户骁龙／天玑的实测反馈与开发检查分别记录。此前 K40 / Android 12 / APatch 已验证驱动、RSA、64 MiB 双向传输及清理。本次更名打包未重新对所有设备刷入验收。后台切页验证见 `mobile/docs/webui-response-0.3.24-validation.md`，历次修改见仓库 `CHANGELOG.md`。省电优化没有实际电量测量结论。

## 构建

`powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -UsbAdb`

输出 `release/USBLink-Mobile-0.3.25-usb-adb-arm64.zip` 及 SHA256 文件。旧参数 `-ExperimentalUsb` 仍作为别名兼容。源码目录和内部协议标识保留原路径以兼容旧电脑、配对及共享偏好。不加 USB 参数时构建 TCP 开发模块；两种包使用同一模块 ID，不能同时安装。
