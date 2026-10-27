# Mobile 0.3.10：NETWORK_UNAVAILABLE 误断开修复

日期：2026-10-20。用户反馈 K70 / Android 15 / 模块 0.3.8 闲置时也断开，最近中断为 `21:26:04 · NETWORK_UNAVAILABLE`。此次仅修改代码、执行主机测试并本地打包，没有连接、刷入或改变真实手机状态，没有推送或发布 GitHub。

## 已确认的代码问题

原 `MeshManager.tick()` 把 EasyTier `peer list` 的超时、读取／解析异常和缺少本机地址都转换为 `ip=null`；随后 `Controller.reconcile()` 调用 `stopAll("network_unavailable")`，关闭 USB 监听与当前会话。因此一次观察失败就足以使仍通畅的 USB 连接断开。没有故障时刻的手机日志，不能断言用户每次断开都是同一个原因。

## 修复边界

- 将中继／成员元数据与本地传输就绪分开判断。首次地址仍须由有效 RPC 返回，并与实际 `usblink0` 的 IPv4 匹配。
- 已建立连接遇到 RPC 超时、格式异常或不完整结果时，只有原受管子进程仍存活、同一接口索引仍启用且持有原 IP、实时路由和本模块所有权规则均确认正常，才保留原监听与会话。使用 Java `NetworkInterface` 读取实际接口，不通过保存的配置或旧 RPC 结果假定网卡还在。
- RPC／路由查询之后再次核对进程和接口，避免查询期间退出／替换后发布成功。保留既有 IPv4／IPv6 adbd 防护、Root UID 与 TCP 所有权检查。
- 不保留失效的中继在线状态或成员列表。降级期间 `running=false`、`peers=[]`、`statusDegraded=true`；WebUI 显示“状态待确认”和具体解释。原 USB 连接可以保持，经过 HMAC 的实时应用响应继续由独立 presence 服务提供，不把本地接口存活当作远程电脑在线。
- 没有延时忽略真实断网、后台重新导入或重启 adbd。进程退出、网卡丢失／重建、IP 移除和路由无法核验，仍在本轮观察撤销会话。网络随后就绪只恢复监听，Windows 必须由用户手动连接。
- 新诊断代码：`NETWORK_PROCESS_EXITED`、`NETWORK_INTERFACE_LOST`、`NETWORK_ROUTE_UNAVAILABLE`、`NETWORK_STATUS_UNCONFIRMED`；保留旧 `NETWORK_UNAVAILABLE` 兼容路径。日志仍只记录固定原因、时间和字节数，不增加命令、配对信息或原始输出。
- 原共享开关、配对码与三管理器兼容不变；关闭后停止网络，既有状态观察不会重新开启。没有修改桌面 ADB 或 Windows 挂载实现。

## 主机验证

- 新增 `MeshObservationTestMain`：78 项通过。模拟 RPC 超时、JSON 损坏、无本机条目、错误结构，分别连续观察 20 次；验证独立接口／路由证据、过期元数据清除、恢复、首次启动、实际进程／接口／地址／路由失效、查询中退出／接口变化、接口索引改变、主动关闭和配对码保留。
- USB 模块集成增至 63 项通过：仅一次回环模拟 USB 导入，闲置时连续 20 次 RPC 失败，原监听端口、导出路径、会话 ID 和已建 TCP 连接不变；之后同一连接仍能完成 USB 配置请求。接口消失时两端关闭并记录 `NETWORK_INTERFACE_LOST`，网络恢复不导入、不重启 adbd。没有真实 Windows 驱动挂载。
- 完整 USB 构建测试：核心 71、原组网 63、网络观察 78、设备名 22、presence 50、TCP 防护 124、开机准备 50、历史 24、ADB Root 75、USB/IP 协议 719、模块集成 63；WebUI 23；管理器安装 22 与 BusyBox 发现 6，全部通过。
- WebUI 新测试确认“共享 active”和“中继 running 待确认”可以分别呈现，关闭共享仍停止网络；预览参数不能使普通浏览器调用真实服务。蓝白／藏蓝布局未变，仅调整状态文字和提示样式。
- 沙箱内 Java 回环网络检查被阻断，经沙箱外执行完成回归。编译 Android API 31+ DEX 与 WebUI 并生成 0.3.10 ZIP；默认 TCP 0.2.7 复用公共测试、独立编译打包。

## 真机限制与后续核对

该测试没有证明 K70 的 ROM 网络策略、Android Java 网络接口读取、熄屏／Doze、Wi-Fi／移动数据切换、后台进程存活和异地中继链路已经通过验收，也没有测量耗电。实际网络断开仍可能中断 USB；本次解决的是已定位的状态查询误撤销。

更新 `USBLink-Mobile-0.3.10-usb-adb-experimental-arm64.zip` 后按模块管理器要求完成更新／重启，确认 WebUI 版本为 0.3.10，再由用户在 Windows 点击一次连接。先观察闲置，再按原使用方式调试／传输。如仍断开，应读取新的具体中断代码与手机网络状态，不以自动重挂载掩盖问题。Windows 0.3.11／0.3.12 无需随本次修复升级。
