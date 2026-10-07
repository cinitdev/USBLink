# USBLink 手机 WebUI

APatch / KernelSU 模块内的中文手机界面。依赖根项目现有 React、Vite 与 Fluent 图标，不另安装依赖。

在仓库根目录执行：

```powershell
node node_modules/vite/bin/vite.js --config mobile/webui/vite.config.mjs
node node_modules/vite/bin/vite.js build --config mobile/webui/vite.config.mjs
node --test mobile/webui/tests/*.test.mjs
```

本地交互预览：`http://127.0.0.1:5174/?preview=1`。预览状态只在内存中，始终显示预览横幅。没有 `preview=1` 的普通浏览器显示模块环境不可用，不模拟成功。

构建输出在 `mobile/webui/dist`，打包时放入模块 `webroot`。

0.2.1 起，普通查询经 `usblinkctl status` 读取后台的原子状态文件，核对进程身份、启动周期及 10 秒时效，不再启动 Java 客户端。变更仍走 Root 本地控制套接字，成功响应直接用于页面，旧轮询结果不得覆盖它。稳定状态 8 秒轮询，过渡状态 2 秒，失败逐步退避；隐藏页面停止查询。未变化的状态复用已有 React 数据，不重绘整页。

原生桥接使用 APatch / KernelSU 注入的 `window.ksu.exec(command, optionsJson, callbackName)`。只允许固定操作名；载荷以 UTF-8 JSON 的 Base64 字符串传入固定 `usblinkctl`，没有原始用户内容拼接到 shell。返回格式为 `{ "ok": true, "data": ... }` 或 `{ "ok": false, "error": "..." }`。

网络配对码不写入 Web Storage，离开连接页即清空已显示的码与输入。调试使用系统 adbd TCP 和 Android RSA 电脑授权，已移除无线调试的配对窗口、端口发现和手动配对端口。界面区分保存的共享意愿与实际就绪状态；关闭共享需等待后端恢复原调试配置，失败时提供“关闭共享并重试恢复”。切换前可见提示说明会重启 adbd，可能中断已有调试连接。
