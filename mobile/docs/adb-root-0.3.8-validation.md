# Mobile 0.3.8：ADB Root 开关验证

日期：2026-10-13。本次只修改项目、执行主机测试与浏览器示例预览，没有连接手机、安装模块、执行真实 ADB 命令或更改真实 adbd 权限。

## 行为与边界

- 设置页保存独立的 ADB Root 偏好，默认开启。系统启动完成且 adbd 已运行后应用，关闭等待中的偏好可取消启用；不开启普通 USB 调试时不会强行启动 adbd。
- 只写 `service.adb.root` 并请求 init 重启系统 adbd。ROM 的编译选项和实现决定是否支持；`ro.debuggable=1` 或已核验过 Root adbd 仅作为可尝试的能力信号，真正成功必须等待新进程身份与目标 UID。
- 同时核验系统可执行文件、进程名称、SELinux 上下文、PID／启动时间及 UID。不修改 `ro.secure`、`ro.adb.secure`、`ro.debuggable`、USB 配置、系统二进制或 SELinux。保留 Android RSA 授权。
- 切换需要重启 adbd，已有有线与远程调试会中断。模块先撤销自己的远程会话，受管理 TCP 端口还需核验防护与配置；最多等待 10 秒确认，不在当前进程内自动补试。电脑仍须手动连接，不执行 USB/IP 重挂载。
- 偏好和实际权限分开显示：不支持 Root 的 ROM 保持普通调试能力；关闭 Root 后无法确认 UID 2000 时立即暂停共享，即便失败发生在属性写入前。未知／失效进程不是已降权。
- Root 切换不改变共享偏好，不会开启已关闭的网络。共享关闭后本地 Root 设置仍可使用，配对不变。

## 恢复

- 手机 Root 私有状态目录保存 `adb-root-preference.json` 和 `adb-root-recovery.json`。恢复记录先于属性修改落盘，包含原属性、原 UID、boot ID 和变更前进程身份。
- 模块停止时尝试恢复原临时 Root 配置与实际权限；外部配置冲突不覆盖，恢复失败保留记录。原 adbd 已停时只恢复临时属性，不启动被用户关闭的调试服务。
- 旧 boot ID 的非持久属性记录不跨重启重放；新启动按持久偏好应用。仅有 Root 恢复记录而没有 TCP 记录时，shell 清理入口也必须运行恢复。
- 守护进程在重启控制服务前尝试恢复；新控制服务按保存的偏好重新应用。这可能再次中断 adbd，不能把类级别的“接管既有记录不重放”测试理解为整个监督程序从不再次应用偏好。

## 自动与界面验证

实验构建通过核心 71、组网 63、名称 22、在线认证 50、TCP 防护 124、开机准备 50、历史 24、ADB Root 75、USB/IP 协议 719、模块集成 53 和 WebUI 21 项检查，随后编译 DEX 与网页并打包。默认 TCP 0.2.5 构建复用已通过的公共测试，以 `-SkipTests` 完成独立编译打包。shell 生命周期 66 项、mesh wrapper 5 个场景、管理器操作 10 个场景均通过。

Root 回归覆盖默认开启、保存关闭、等待开机期间关闭、反复手动开关、原有 Root ROM 在降权后重新开启、属性拒绝／权限拒绝／防护丢失／重启不生效、超时无补试、外部配置冲突、停止恢复、旧启动记录、进程缺失与身份不明，以及控制器在降权失败时撤销正在运行的共享。

TCP 回归额外覆盖权限切换前的端口保护、异源监听拒绝、外部持久属性冲突、独立的监听准备期限；准备函数不提交重启。模块脚本夹具覆盖只有 Root journal 的恢复与失败保留。

WebUI 在显式示例模式验证默认开、关闭、再次开启、处理中禁用按钮、390px 窄屏与不支持 Root 的展示。预览路径为 `?preview=1&usb=1` 和 `?preview=1&usb=1&root=unsupported`；示例 UID 不是手机测量。普通浏览器无管理器桥接时无法控制手机。

Windows 沙箱阻断既有 Java 回环套接字测试、Vite 路径访问和 Git Bash 测试夹具创建；获准在沙箱外运行相关本地命令后验证。所有测试网络端点均为本机夹具，不操作真实手机或 Windows 虚拟 USB 挂载。

尚未验证 K40／K70 的实际 ROM 是否支持 Root adbd，也未验证真实 ART 环境、手机重启恢复、系统权限限制和 Android Studio 重连。不能据主机测试承诺量产 ROM 通用兼容。

## 参考实现

AOSP ADB 的 LineageOS 镜像用于核对标准权限行为：[daemon/main.cpp](https://github.com/LineageOS/android_packages_modules_adb/blob/lineage-22.2/daemon/main.cpp)、[daemon/restart_service.cpp](https://github.com/LineageOS/android_packages_modules_adb/blob/lineage-22.2/daemon/restart_service.cpp) 和 [init.usb.rc](https://github.com/LineageOS/android_system_core/blob/lineage-22.2/rootdir/init.usb.rc)。这些实现不是目标手机 ROM 的兼容性证明。
