# Mobile 0.3.9：Magisk / APatch / KernelSU 兼容验证

日期：2026-10-18。本次修改模块、执行主机测试并生成本地 ZIP，没有连接或刷入真实手机，没有推送或发布 GitHub 版本。

## 修改范围

- 同一 `usblink-mobile` 模块 ZIP 支持 Magisk、APatch 和 KernelSU 安装。优先识别 APatch／KSU 的环境标志，避免它们提供的 Magisk 兼容变量导致误判；Magisk 版本码必须为数值且至少 20400。Android 12+（API 31+）、ARM64 门槛保持。
- 增加 `/data/adb/magisk/busybox`，保留 APatch／KSU 的既有路径。只使用这些管理器自带的可执行文件并验证 `flock` applet，缺失时拒绝无锁启动。没有通过 WebUI 传入路径，没有添加锁绕过、后台自动重挂载或自动安装依赖。
- 安装时显式为 `webroot` 设置目录 0755、文件 0644；后台私有状态目录仍为 0700，控制套接字仍核验 Root UID。
- KsuWebUI 与现有管理器注入同样的 `window.ksu.exec(command, optionsJson, callbackName)`，回调 `(errno, stdout, stderr)`。保持固定入口、动作白名单和 Base64 JSON，不按宿主名称猜测接口，不退回同步命令，不在失败后重发操作。
- 无桥接、未授权 Root 或后台未启动的提示包含 Magisk + KsuWebUI 用法；设置页改为三类管理器说明。现有蓝白／藏蓝布局保持。
- Magisk 用户在 Magisk 中安装模块，完成首次重启后，在自行安装并已授权 Root 的 KsuWebUI 中选择 USBLink。APatch／KernelSU 可以使用内置 WebUI。模块“操作”用于启动／检查后台，不代替 KsuWebUI 应用。
- 不下载或安装 APK，不创建 HTTP Root 控制接口，不更改 Android RSA、SELinux、系统调试开关或桌面 ADB。保留 0.3.8 的 Root 偏好、历史、传输和省电逻辑；管理器兼容不证明 ROM 支持 Root adbd。

## 主机验证

- `node mobile/tests/root-managers.mjs`：22 个安装场景、6 个 BusyBox 发现场景通过。覆盖三管理器、兼容变量优先级、Magisk 最低版本、非法版本、Android 11／未知 API／ARM32 拒绝、安装后返回管理器继续收尾、WebUI 权限、各管理器独立路径、缺失 applet 与备用路径。
- 原有脚本测试：66 项生命周期检查、5 个 mesh wrapper 场景、10 个管理器操作场景通过，检查进程身份、互斥锁、清理与就绪确认。
- `build.ps1 -ExperimentalUsb`：核心 71、组网 63、名称 22、在线认证 50、TCP 防护 124、开机准备 50、ADB 历史 24、ADB Root 75、USB/IP 协议 719、实验模块集成 53 项通过；WebUI 22 项通过。新增 KsuWebUI 三参数桥接拒绝授权测试，确认只提交一次且不泄露原始 stderr。
- USB 实验 0.3.9 完成 DEX、WebUI 与 ZIP 构建。默认 TCP 0.2.6 复用已通过的公共测试，以 `-SkipTests` 完成独立编译打包。
- 沙箱阻止 Git Bash 夹具路径、Java 回环网络及 Vite 读取依赖；获准在沙箱外完成相应本地验证，没有访问真实手机服务。
- 普通浏览器不带预览参数时，明确显示 KsuWebUI 打开及 Root 授权提示，不能切换共享。显式示例预览设置页显示 0.3.9 和三类管理器说明；这不代表真实管理器或 ART 验收。

## 产物与限制

- `release/USBLink-Mobile-0.3.9-usb-adb-experimental-arm64.zip`：USB ADB 实验模块，适用于当前 Windows 点击连接流程。
- `release/USBLink-Mobile-0.2.6-arm64.zip`：旧 TCP 开发兼容路径，不是完整虚拟 USB。
- 两个 ZIP 均附 SHA-256 校验文件。Windows 0.3.11／0.3.12 无需为此次管理器兼容升级。

尚未刷入 Magisk／KernelSU 手机验证实际授权、SELinux 限制、首次安装／重启、长时间共享与卸载；此前 K40/APatch 实验成功不能替代这些验收。KsuWebUI 不同分支版本与 ROM 仍需实测。保持 USB ADB 实验范围，未增加 MTP、Fastboot 或 OTG 支持。

## 核对的上游接口

- [Magisk 开发指南](https://topjohnwu.github.io/Magisk/guides.html)：模块安装布局、`customize.sh`、`service.sh` 与 `/data/adb/magisk/busybox`。
- [Magisk 官方安装入口](https://github.com/topjohnwu/Magisk/blob/master/scripts/module_installer.sh)：最低 20400 的版本门槛。当前包走管理器安装，不添加 recovery 刷入支持。
- [KsuWebUI 原版](https://github.com/5ec1cff/KsuWebUIStandalone) 与 [KOWX712 分支](https://github.com/KOWX712/KsuWebUIStandalone)：面向 Magisk／APatch／KernelSU 的独立 WebUI 宿主。
- 两者的 `app/src/main/java/io/github/a13e300/ksuwebui/WebViewInterface.kt`：三参数 `exec` 和 errno/stdout/stderr 回调一致。仅核对接口，没有复制上游代码或捆绑其 APK。
