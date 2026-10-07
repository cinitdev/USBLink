# K40 完整 USB 网络导出：真机只读检查

检查日期：2026-10-07。目标是 Windows 枚举真正的 MTP 便携设备和 USB ADB 接口，而非 ADB TCP 连接或伪造设备条目。

后续进展：同日的[用户态 USB 试验](userspace-usb-first-trial.md)已在不加载手机 USB/IP 内核模块的情况下，验证真实 Windows USB ADB 枚举和 64 MiB 双向传输。该路线自行实现设备协议；本报告讨论的仍是直接导出系统原有 Gadget 的内核路线。MTP 尚未实现。

## 结论

当前手机不具备可直接使用的 USB/IP Gadget 导出能力。它具备尝试添加专用内核模块的部分前提，但尚未证明模块能够匹配当前内核、正常加载，或成功导出 MTP + ADB。不能据此承诺免刷内核，也不能宣称已实现完整 USB 共享。

本次仅通过已有电脑 SDK 的 ADB，对已确认的 K40 执行读取命令。没有加载内核模块、刷写启动镜像、修改 USB 模式或 Gadget 绑定、重启 adbd，亦没有关闭 SELinux/认证。

## 真机证据

| 项目 | 读取结果 | 含义 |
| --- | --- | --- |
| 机型 | Redmi K40 / M2012K11AC / alioth | 与用户指定目标一致 |
| 系统 | Android 12，MIUI V13.0.3.0.SKHCNXM | 不可直接假定公开 Android 11 源码与此构建兼容 |
| 内核 | `4.19.113-perf-g42cc20a57a7b` | 内核模块必须匹配该构建的 ABI |
| 编译器 | Clang 10.0.6 for Android NDK | `/proc/version` 与配置均确认 |
| Gadget/ConfigFS | `CONFIG_USB_GADGET=y`、`CONFIG_USB_LIBCOMPOSITE=y`、`CONFIG_USB_CONFIGFS=y` | 手机具备设备侧 USB 框架 |
| 手机功能 | `CONFIG_USB_F_MTP=y`、`CONFIG_USB_F_FS=y`、`CONFIG_USB_CONFIGFS_F_MTP=y`、`CONFIG_USB_CONFIGFS_F_FS=y` | 存在 MTP 和 ADB 所依赖的功能 |
| USB/IP | `# CONFIG_USBIP_CORE is not set`；未发现已加载的 usbip_core / usbip_vudc 或检查目录中的相应 .ko | 不能直接启用虚拟 UDC 网络导出 |
| 动态模块 | `CONFIG_MODULES=y`、`modules_disabled=0` | 允许加载模块，但不等于任意模块可兼容 |
| 签名 | `# CONFIG_MODULE_SIG is not set`、运行时 `sig_enforce=N` | 签名不是本次观察到的首要障碍；未修改这些设置 |
| ABI 校验 | `CONFIG_MODVERSIONS=y`，原厂 exfat.ko 的 vermagic 含 `modversions` | 仅修改版本字符串不够，必须匹配符号 CRC 与结构布局 |
| 导出符号 | 未裁剪未使用符号；观察到 usb_add_gadget_udc、usb_del_gadget_udc、usb_gadget_giveback_request、usb_gadget_udc_reset、内核 socket 等部分关键导出 | 有离线适配价值，尚未核对完整依赖和符号 CRC |
| 当前 USB | `g1/UDC=a600000.dwc3`，`sys.usb.config=adb`，活动配置只链接 ffs.adb | 本次物理连接处于 ADB 模式，未切换至 MTP |
| 现有 MTP | g1/functions/mtp.gs0 及 `/dev/mtp_usb` 存在 | 功能存在与远程 Windows 成功使用是两件事 |

从 `/proc/config.gz` 读取的完整配置保存在本地构建目录 `mobile/build/k40-kernel-config.gz` / `k40-kernel.config`，原压缩文件 SHA256：`23b181c05d3c551770be1b15afbda6ebffe9f8dccab033ae149f31d525478dc1`。构建目录是临时资料，不作为通用机型配置发布。

在 `/vendor/lib/modules/Module.symvers` 和该版本常用的 `/lib/modules/.../build/Module.symvers` 路径未找到匹配构建资料；这不是全文件系统搜索，也不能证明资料在所有外部来源都不存在。

## 需要解决的实际问题

1. 为当前内核提供 ABI 匹配的 `usbip-core`、`usbip-vudc` 及设备模式 usbipd。优先验证独立 .ko 是否可行；无法可靠匹配时，才评估带相应配置的定制内核。不得修改 vermagic/CRC 绕过兼容检查。
2. 对接 Android 原有 MTP 服务和 ADB FunctionFS。手机目前由高通 USB init/HAL 管理物理 UDC；直接写绑定会与系统状态管理发生冲突。也不能假定同一份 MTP/ADB 端点可以同时供物理 USB 和虚拟 UDC 使用。
3. 在切换前设计本机 WebUI 控制、失败恢复与物理 USB 还原。切换 UDC 可能使诊断用 USB ADB 断开，不能靠这根数据线作为唯一恢复通道。
4. 扩展 USBLink 的经过认证的手机 USB 导出能力和设备记录。当前 Android presence 只表示 ADB TCP 就绪；Windows 现有路径也会拒绝 Android USB/IP，bus ID 校验只接受数字拓扑，不接受 `usbip-vudc.0`。不能只放宽字符串校验而跳过来源、状态和挂载确认。
5. 在 Windows 验证复合设备枚举、MTP 文件读写、Android Studio 安装 APK、单设备断开以及关闭共享后的恢复。保留单次手动挂载、禁止自动补试的约束。本机客户端文件版本为 usbip-win2 0.9.8.1，本次未进行实际挂载或宣称驱动与 vUDC 互通已验证。

APatch 可以承载控制脚本和匹配的模块文件，但不能凭 Root 权限补齐缺失的内核实现。即使 K40 适配成功，内核模块也不因此成为所有品牌、所有 ROM 通用的包。

## 参考与限制

- [Linux USB/IP 官方 README](https://github.com/torvalds/linux/blob/master/tools/usb/usbip/README)：描述通过 usbip-vudc 绑定 Gadget、由设备模式 usbipd 导出的机制。
- [小米官方内核源码清单](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/README/README.md)：K40 列为 alioth-r-oss / Android R；本次未找到已证明匹配真机上述 Android 12 构建的完整源码、配置与 Module.symvers 组合。
- [usbip-win2 官方项目](https://github.com/vadimgrn/usbip-win2)：Windows UDE USB/IP 客户端。通用协议兼容说明不能替代这台 K40 的 MTP/ADB 真机测试。

刷内核、加载新内核代码或更改 Gadget 绑定属于后续独立的真机验证步骤；本次“接入手机并只读检查”的授权不涵盖这些操作。
