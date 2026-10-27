# USBLink 手机 WebUI

Magisk / APatch / KernelSU 模块内的中文手机界面；Magisk 通过独立 KsuWebUI 应用打开，需授予它 Root 权限。依赖根项目现有 React、Vite 与 Fluent 图标，不另安装依赖。

在仓库根目录执行：

```powershell
node node_modules/vite/bin/vite.js --config mobile/webui/vite.config.mjs
node node_modules/vite/bin/vite.js build --config mobile/webui/vite.config.mjs
node --test mobile/webui/tests/*.test.mjs
```

本地交互预览：`http://127.0.0.1:5174/?preview=1`。预览状态只在内存中，始终显示预览横幅。没有 `preview=1` 的普通浏览器显示模块环境不可用，不模拟成功。

构建输出在 `mobile/webui/dist`，打包时放入模块 `webroot`。

0.3.9 保持现有蓝白／藏蓝布局，界面标识改为通用 Root 模块。KsuWebUI 与 APatch／KernelSU 共用三参数 `ksu.exec(command, optionsJson, callbackName)`，回调为 `(errno, stdout, stderr)`。不按宿主名称猜测能力，不退回同步执行，不在失败／超时后重发操作；没有桥接时说明三种打开方式。授权被拒绝或模块未启用时不展示成功，不泄露原始 shell 输出。源码核对与主机测试不替代 Android WebView 真机验证。

0.3.8 在设置页新增“ADB Root 权限”，默认偏好开启，实际 Root / 普通 UID / 未确认分别展示。`set-adb-root` 仅经现有串行 Root 本地控制接口变更，切换期间禁用再次提交并保持快速只读观察。失败不自动重试；普通浏览器没有控制能力。Root 和共享偏好相互独立，说明中明确切换会重启系统 adbd 并中断调试，不能保证所有 ROM 支持。`?preview=1&usb=1&root=unsupported` 可查看不支持 Root 的示例；所有预览都不是手机实测结果。

0.3.7 在连接页新增远程 ADB 历史，保留三页导航。最新 20 条复用现有轻量状态快照自动同步，不新增 app_process 轮询；手动“查看更早记录”才调用 Root 控制接口 `history`，以记录 ID 与后台代际分页。`clear-history` 经确认、串行执行并等待保存，返回当前状态；旧轮询不能恢复已清空的记录。历史内容只作为文本显示，不写浏览器存储，不作为 shell 参数或可执行链接。示例预览：`?preview=1&usb=1&history=1`，普通浏览器不能生成真实历史或控制手机。

0.3.6 的设备／连接页显示“最近一次连接中断”。这是当前后台进程保留的历史诊断，不代表此刻断开；当前状态仍以会话列表与新鲜观察为准。原生数据包含固定原因、时间、诊断码和已完成字节数，不包含调试命令、载荷或配对秘密。带 `preview=1&usb=1&interruption=1` 的显式预览可查看诊断展示；没有 `preview=1` 不会生成模拟状态。

普通查询经 `usblinkctl status` 读取后台的原子状态文件，核对进程身份、启动周期及 10 秒时效，不再启动 Java 客户端。变更仍走 Root 本地控制套接字，成功响应直接用于页面，旧轮询结果不得覆盖它。共享及准备状态每 2 秒、关闭状态每 5 秒轮询，失败逐步退避；隐藏页面停止查询。未变化的状态复用已有 React 数据，不重绘整页。

0.3.4 起，共享关闭时网络显示“已断开”，保留已保存的配对信息；不会把主动断网显示成一直连接或错误。只有开启共享才连接，创建／加入网络时默认只保存配置；“退出网络”仍需确认并清除配对。空闲后台观察也降为每 5 秒，操作后立即唤起观察。

原生桥接使用 APatch / KernelSU 注入的 `window.ksu.exec(command, optionsJson, callbackName)`。只允许固定操作名；载荷以 UTF-8 JSON 的 Base64 字符串传入固定 `usblinkctl`，没有原始用户内容拼接到 shell。返回格式为 `{ "ok": true, "data": ... }` 或 `{ "ok": false, "error": "..." }`。

网络配对码不写入 Web Storage，离开连接页即清空已显示的码与输入。调试使用系统 adbd TCP 和 Android RSA 电脑授权，已移除无线调试的配对窗口、端口发现和手动配对端口。界面区分保存的共享意愿与实际就绪状态；关闭共享需等待后端恢复原调试配置，失败时提供“关闭共享并重试恢复”。切换前可见提示说明会重启 adbd，可能中断已有调试连接。
