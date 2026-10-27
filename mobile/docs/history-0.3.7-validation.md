# Mobile 0.3.7：远程 ADB 请求历史验证

日期：2026-10-13。此次没有连接或安装真实手机，没有执行真实 ADB 命令、USB 挂载、adbd 重启或系统设置变更。

## 记录范围

- 被动观察本模块已写给系统 adbd 的数据；按 ADB 的 OPEN / WRTE / CLSE 分帧，不修改字节、不合成授权。
- OPEN 记录 shell / exec 命令、abb / abb_exec 应用管理参数及其他服务名；SYNC 记录 SEND / SND2、RECV / RCV2、STAT / STA2 / LST2、LIST / LIS2 的文件路径。
- SYNC v2 在路径后还有包含重复 ID 的额外头：SND2 为 12 字节、RCV2 为 8 字节。按 AOSP 格式跳过，DATA 只消费长度，不保留内容或解压。参考 [AOSP 协议头的 LineageOS 镜像](https://github.com/LineageOS/android_packages_modules_adb/blob/lineage-22.2/file_sync_protocol.h)。
- 记录表示请求已转发，不表示获授权、执行成功或返回码为零，也不还原完整电脑命令行。ADB 主机本地操作、通过其他通道的调试、交互式 shell 逐行输入不在范围内。
- 不保存 RSA 包、公钥、APK / 文件内容或命令输出；隐藏 USBLINK1 配对码及识别出的常见凭据参数。任意命令参数仍可能含隐私，不能承诺对自定义敏感参数完全脱敏。

## 存储与交互

- 手机 `/data/adb/usblink/adb-history.jsonl`，沿用 Root 0700 状态目录，写入文件设为 0600。最多 200 条，每条命令文本最多 512 个字符；文件大小读取上限 1 MiB。
- 内存更新后在独立线程合并保存，以临时文件原子替换；无请求时不写历史。异常断电/强制结束可能丢失尚未保存的尾部记录，不能把原子替换当作掉电持久性保证。正常服务退出尝试刷新。
- 最新 20 条跟随现有带进程身份和有效期校验的状态快照刷新，无额外 app_process 轮询。手动翻页经 Root 本地套接字使用游标，每次最多 20 条。
- 清空变更代际、清空内存并等待后台保存；保存失败显示错误。清空前的磁盘任务不能在清空后恢复旧记录，迟到的网页轮询不能覆盖清空响应。后续新请求继续正常记录。
- 命令只以 React 文本渲染，不作为 HTML、命令参数或可执行链接；不放入 Web Storage、网络 presence 或常规 daemon 日志。清空不停止共享或重启 adbd。

## 已通过验证

执行 `powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb`，通过核心 71、组网 63、设备名称 22、在线认证 50、TCP 防护 113、重启准备 50、历史记录 24、USB/IP 协议 719、USB 模块集成 53、WebUI 20 项检查。

新增测试覆盖逐字节分片、UTF-8、多 ADB 请求、多 WRTE 分片、SYNC v1/v2 头、64 KiB DATA 内容排除、文件数据内伪造 OPEN 不会产生历史、交互式输入排除、过大协议包不分配其声明大小、200 条轮转、超 64 KiB 文件的重载、凭据隐藏、过期游标、清空后重载、存储失败可见、真实回环 USB/TCP 转发字节保持及清空后会话继续。

WebUI 已在显式演示模式和 390px 窄屏检查：最新记录、较早记录、返回最新、清空确认与空状态。普通浏览器缺少模块桥接时仍不能控制真实服务。预览路径为 `?preview=1&usb=1&history=1`，示例不来自手机。

Windows 沙箱阻断了已有 presence 回环套接字测试；同一构建获准在沙箱外执行后通过。所有套接字测试均为主机 fake adbd，不触碰实际 Windows 虚拟 USB 驱动。尚未验证真实手机 ART 运行、Android Studio 安装请求的完整覆盖或持续运行耗电。
