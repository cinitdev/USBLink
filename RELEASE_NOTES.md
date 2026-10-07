# USBLink Windows 0.3.11 + Mobile 0.3.3

发布日期：2026-10-07。此版本为预发行版，包含 Android 用户态 USB ADB 实验模块。汇总上一公开版本 v0.3.8 之后全部本地改动；Windows 与手机模块使用各自的版本号。

## 下载与安装

| 文件 | 用途 |
| --- | --- |
| `USBLink-0.3.11-x64-Setup.exe` | Windows x64 中文安装程序，支持原位更新、快捷方式与卸载注册 |
| `USBLink-Mobile-0.3.3-usb-adb-experimental-arm64.zip` | Android 12+ / ARM64 的 APatch / KernelSU 模块，作者：一只小柒夏 |
| `SHA256SUMS-v0.3.11.txt` | 本次安装程序和模块的 SHA-256 校验 |

Windows 默认直接更新，保留配对与设置。运行占用时，经用户确认可结束 USBLink 进程后继续；强制结束留下的 USB 状态在下次应用启动清理。已安装 Windows 0.3.11 的用户仅更新模块即可。

手机覆盖安装 ZIP 后按管理器提示完成更新。Android 11 及以下或无法识别系统版本时中止安装。普通 USB 调试需由用户开启，首次连接需在手机确认 RSA 授权。无需无线调试，也无需在另一台电脑插入该手机。

## Windows 的新增与修复

- 手机型号通过 Windows 原生只读属性识别，覆盖不同品牌提供的型号或产品名，不使用 K40、VID/PID 或端口机型表。正常系统与 fastboot / fastbootd 提供同一有效唯一序列号时可复用已知名称；缺失、占位、重复或冲突身份不套用历史名称。
- 名称历史在来源电脑使用 DPAPI 加密，仅保存身份摘要，限制容量和有效期；已接收的虚拟 USB 不进入本机型号学习与共享。查询失败保留原名，不启动 ADB / fastboot，也不切换设备模式。
- 手机在线状态通过配对密钥和随机挑战认证，区分 Windows USB/IP、旧 Android TCP 与 Android USB 实验通道；加入网络或端口开放本身不等于手机共享就绪。共享关闭仍可显示手机在线，过期状态不可继续授权连接。
- Android USB 实验模块可在现有连接页点击“连接设备”，支持单设备断开。挂载前核对签名设备名称、编号、VID/PID、共享实例与稳定的 USB/IP 导出记录；每次只提交一次 `--once`，不自动补试或重挂载。
- 保留旧 TCP 模块的状态和命令展示以便兼容。本次 USB 实验模块不要求执行 `adb connect`，USBLink 不启动、接管或捆绑另一套桌面 ADB / scrcpy。
- 保留既有设备分类、虚拟 USB 来源过滤、单设备断开、启动／退出双向清理及中文原位更新安装器。

## 首次发布 Android 模块

- APatch 优先、兼容 KernelSU 的 Root 模块，后端为 DEX JAR + app_process，内置中文手机 WebUI，无独立 APK。修复 Android mksh 对锁描述符的继承，模块操作入口等待管理服务实际就绪。
- 使用原版 EasyTier 2.6.4 建立加密网络，经用户态 USB 模拟向 Windows 提供 Android ADB Interface。复用现有虚拟 USB 驱动、电脑 ADB 和手机系统 adbd，保留 Android RSA 授权、SELinux 与用户调试开关。
- 共享开关持久保存，关闭会撤销监听与已有会话；日常重新启用不要求重启手机。启停会重启系统 adbd，已有调试可能短暂中断。旧 TCP 模块的开启偏好不会自动开启新 USB 通道。
- 启动前建立 IPv4 / IPv6 访问限制；内部端口仅允许本机 Root，外部共享只绑定 mesh 地址并限制 usblink0 入站；控制接口本地校验 Root UID，不提供网络 Root 命令接口。
- 临时调试端口变更前保存恢复日志；停止与异常恢复时验证监听释放，不覆盖其他程序改动，恢复失败保留防护。网络凭据不进入前端持久化、URL 或日志。
- 手机名称优先采用 ROM 市场名称，再回退配置名称和原型号；设备来源采用手机名称。没有硬编码的型号映射表。

## WebUI 与重启恢复

- 蓝白浅色／藏蓝深色，保留设备、连接、设置三页。显示当前连接与单会话断开，首次连接指南默认折叠，支持即时操作反馈和页面切换。
- 共享时每 2 秒、关闭时每 5 秒静默读取状态；后台暂停前端轮询、返回立即同步。状态文件验证 daemon 身份、启动时间和时效，例行状态查询不启动 app_process。
- 查询去重、相同数据减少重绘；迟到响应不能覆盖新操作，自动刷新不覆盖编辑中的表单；失败与过期观察不显示缓存成功，不重复执行共享操作。
- 重启保留开启意愿，等待 Android 启动和防火墙稳定后再启动共享；显示“等待系统启动就绪／共享准备中”，用户关闭可取消。
- 仅在两种 IP 协议的全部防护规则仍完整时维护其优先级；不重启 adbd、不更改端口、不替电脑重挂载。真实规则缺失或持续干扰仍暂停并说明原因。

## 使用步骤

1. 手机与电脑创建／加入同一个 USBLink 网络。
2. 手机 WebUI 开启“共享这台手机”，等待共享就绪。
3. Windows 连接页选中手机及其设备，点击“连接设备”。
4. 手机确认 RSA 调试授权，使用电脑现有 ADB / Android Studio 调试接口。
5. 在电脑单独断开设备，或在手机关闭共享以撤销所有会话。掉线后手动连接。

## 验证与已知范围

- 自动测试覆盖 Windows 名称／身份、在线认证、单次挂载／断开，以及 Android 组网、Root 控制、TCP 恢复、防火墙、重启准备、USB/IP 协议、模块生命周期和 WebUI。
- 独立 K40 / Android 12 / APatch 试验已验证驱动绑定、RSA 认证、64 MiB 双向传输和清理；用户反馈早期整合模块可用。最新重启修复的刷入重启、Android Studio 安装 APK 及不同品牌 ROM 尚未完成真机验收。
- **不支持 MTP 便携设备、资源管理器浏览手机存储、Fastboot、外接 U 盘或外接手机共享。** 不能从 ADB 成功推断完整 USB 手机兼容。天玑等 ARM64 机型尚未实测。
- 不承诺原 Windows USB/IP 大 APK 传输掉线已被此次发布彻底修复；网络、ROM 和第三方驱动仍可能影响连接。USB 枚举可能产生正常设备提示音。
- 实验 USB 标识只在显式实验构建中纳入；此预发行不代表完成正式产品 USB 标识或通用驱动认证。
- 第三方 EasyTier 二进制保持未修改，ZIP 和 EXE 附许可证与对应源代码链接。详细说明见仓库 THIRD_PARTY_NOTICES.md 和 mobile/vendor/easytier/NOTICE.md。

完整迭代记录见 [CHANGELOG.md](https://github.com/cinitdev/USBLink/blob/v0.3.11/CHANGELOG.md)；模块架构、构建和兼容旧 TCP 路径见 [mobile/README.md](https://github.com/cinitdev/USBLink/blob/v0.3.11/mobile/README.md)。本次测试结果与产物校验见 [发布验证记录](https://github.com/cinitdev/USBLink/blob/v0.3.11/mobile/docs/release-v0.3.11-validation.md)。
