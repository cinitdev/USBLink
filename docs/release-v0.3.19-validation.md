# Windows 0.3.19 / Mobile 0.3.25 发布验证

日期：2026-11-09。发布标签 `v0.3.19` 沿用 Windows 版本，手机模块独立为 0.3.25。发布渠道为正式版并设为 Latest，汇总上一公开版本 v0.3.12 之后的全部本地改动。

## 本次完成的检查

- 联网确认发布前 GitHub 最新为 v0.3.12，目标 v0.3.19 标签未使用。发布前功能与文档提交已推送至 main；本次补齐完整发布说明、下载入口与升级指引。
- `npm run tauri:build` 成功完成前端、Rust Release 和 Windows x64 中文 NSIS 安装包构建。安装器 ProductVersion / FileVersion 均为 0.3.19，发布副本与此次 NSIS 输出逐字节一致。
- 手机复用本日已从相同功能源码构建的 0.3.25 ZIP，没有修改模块功能或重复构建。此次更新的 mobile/README.md 是仓库说明，USB ZIP 使用的 MODULE.md 未变。
- 重新核对 ZIP CRC、版本 0.3.25 / versionCode 325、作者“一只小柒夏”、无实验名称、DEX JAR 存在；随包 README、module.prop、WebUI 资源和模块 shell 脚本与当前源码／构建输出一致，脚本使用 LF。
- 确认 ZIP 不含 EXPERIMENTAL.md 或测试页面；生成并重新计算两个发行文件的 SHA-256，校验清单一致；差异空白检查通过。
- 没有安装 Windows 包、刷模块、启动桌面 ADB、连接真实 USB、更改手机状态或重复运行全量测试。

## 发行文件

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `USBLink-0.3.19-x64-Setup.exe` | 12038333 | `aed40c9e009f4fc2cf4c44c053ad7ba5284d297ccb5d01cba781a4c830fa20ad` |
| `USBLink-Mobile-0.3.25-usb-adb-arm64.zip` | 8523490 | `d13ba19d8fdc9304a4ec60fdbb409d68ee082ac1e72082439cfbd02b35aa63d3` |

发布附带 `SHA256SUMS-v0.3.19.txt`。Windows 安装包已包含最新手机通道更名；手机 ZIP 校验值与本日 [模块命名检查](../mobile/docs/module-name-0.3.25-validation.md) 相同。

## 复用的定向验证

以下是在各次改动时完成的记录，本次发布不重复执行：

- [桌面更新会话交接](upgrade-session-0.3.19.md)：认证进程身份、旧会话确认与导入清理边界。
- [连接页重写](UI-0.3.17.md)、[设备发现与配对码](UI-0.3.18.md)：来源列表、挂载管理、配对信息和浏览器布局检查。
- [历史请求与返回](../mobile/docs/history-results-0.3.22-validation.md)、[100 条显示页](../mobile/docs/history-page-0.3.23-validation.md)：输出解析、存储、分页及保留 1000 条的纠正。
- [后台切页](../mobile/docs/webui-response-0.3.24-validation.md)：30 项前端定向测试；真实 App 配合受控原生桥接，检查共享／状态未返回时四页切换、仅一次操作提交、分批拼成 100 条和长输出详情更新。
- [模块命名与打包](../mobile/docs/module-name-0.3.25-validation.md)：Java、DEX、WebUI 构建和发行内容检查。

## 真机与兼容性边界

用户反馈骁龙和天玑手机可用；此前 K40 / Android 12 / APatch 的独立试验验证过驱动绑定、RSA、64 MiB 双向传输及清理。上述用户反馈、历史真机记录和本次主机构建检查分开记录，新发行文件没有再次刷入真机；不同 ROM／管理器、跨网络安装和长期多设备传输仍以实际设备验证为准。

手机端只提供 USB ADB，不支持 MTP、Fastboot、外接 U 盘或外接手机共享。不管理 adbd Root 权限，不自动补试或 USB 重挂载，不调整系统提示音。旧 exec-only WebUI 宿主的同步阻塞仍需升级宿主解决；没有实际电量测量结论。

GitHub 发布完成以远端回读为准：main 与标签指向本次提交，三个附件名称／大小／SHA-256 与本地一致，发布正文与 RELEASE_NOTES.md 一致，latest 指向 v0.3.19，draft=false、prerelease=false。
