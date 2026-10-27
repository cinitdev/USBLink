# Mobile 0.3.5 共享端口修复验证

日期：2026-10-11。反馈设备：K70，Android 15，模块 0.3.4；用户报告使用期间断线并出现 `bind failed: EADDRINUSE (Address already in use)`。

## 定位与修改

`UsbIpServer` 之前显式设置 `setReuseAddress(false)`。USB/IP 导出查询完成后由服务端主动关闭连接；关闭共享或网络观察失效也会撤销活动会话。这些套接字在 Linux/Android 上可能保留 TIME_WAIT，即使监听已关闭，再绑定原 TCP 3240 仍可能失败。控制器收到绑定异常后会暂停共享，因此正常的网络恢复也可能停在错误状态。

现在在绑定前开启 SO_REUSEADDR，失败时立即关闭未交付的套接字与工作线程池。占用错误包含 USB 共享角色与具体 TCP 端口。未启用 SO_REUSEPORT，未添加绑定重试、自动 USB 挂载或进程终止。轮询使用原监听，真实网络失效仍会撤销共享会话。

此改动修复已确认的重新绑定缺陷；没有取得 K70 断线时的完整状态日志，不能由这一条报错确定首次网络中断的来源，也不能据此宣布所有掉线问题已解决。

## 验证

1. Windows Java 主机测试使用真实回环套接字：导出查询后同端口连续启停 5 次；活动 USB 会话关闭后同端口启停 3 次；重复监听被拒绝，原服务仍可响应。检查观察轮询保持原会话和导出身份；网络恢复后仅恢复监听，不产生客户端挂载、不再次启动 adbd。测试通过。
2. 已有 Debian WSL 的 Linux 套接字探针 `python3 mobile/experimental/usbip/test/linux-port-reuse.py`：旧策略复现 EADDRINUSE；新策略立即复用端口 5 次成功，已有监听仍会拒绝第二次绑定。此项是 Linux 内核行为验证，不是 Android ART 或真机 Java 测试。
3. 完整模块构建：`powershell -NoProfile -ExecutionPolicy Bypass -File mobile/scripts/build.ps1 -ExperimentalUsb`。核心 71、组网 63、设备名称 22、认证在线 50、TCP 防护 113、开机恢复 50、USB/IP 协议 676、实验模块生命周期 31、WebUI 18 项检查通过。协议测试包含 4 MiB 校验传输、认证字节透传、分片、取消及输入边界。
4. 脚本检查：`node mobile/tests/script-lifecycle.mjs`（62）、`node mobile/tests/wrapper-lifecycle.mjs`（5）、`node mobile/tests/action-lifecycle.mjs`（10）通过。沙箱阻止了部分本地套接字和 Git Bash 目录访问，完整构建与脚本测试在获准的沙箱外环境完成。
5. DEX、WebUI、模块 ZIP 构建成功，保留 0.3.4 的关闭共享断网与配对码保留行为，未改 Windows 程序或桌面 ADB。

## 产物与待验证项

- `release/USBLink-Mobile-0.3.5-usb-adb-experimental-arm64.zip`
- 大小：8,489,199 字节。
- SHA256：`760f575e2bb7c848ff9e89ffae21e8063705db4ae6758ae6a1240ed6e633878c`
- 尚未在 K70 安装、执行 USB 挂载或进行 Android 15 持续传输／切网验收。主机检查不能代替真机确认。
- 覆盖更新沿用 APatch 的安装与重启流程，保存的配对与共享偏好不变。日常启停仍无需重启手机；真正掉线后，电脑仍由用户手动连接。
