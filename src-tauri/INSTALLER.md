# 安装器维护

`installer-template.nsi` 和 `installer-zh.nsh` 基于 Tauri CLI `tauri-cli-v2.11.4` 的 [官方 NSIS 源码](https://github.com/tauri-apps/tauri/tree/tauri-cli-v2.11.4/crates/tauri-bundler/src/bundle/windows/nsis)，按 MIT 许可证使用，全文见 `LICENSE-TAURI-TEMPLATE.txt`。升级 Tauri CLI 时应对照官方模板合并变更，不能直接覆盖本项目的安装流程。

## 本地修改

- 升级默认直接覆盖程序文件，不调用旧卸载器；第二项才进入卸载向导。同版本默认保留配置重新安装。
- 维护页显示真实旧版本；静默/被动安装不读取未创建的单选控件。
- `installer-hooks.nsh` 检查当前用户应用进程和共享会话锁。用户点击“确定”后，使用 Tauri 的 `KillProcessCurrentUser` 结束主程序及历史便携版本进程；随后轮询确认进程和锁释放，最多等待约 5 秒。结束失败可重试/取消。
- 安装、卸载及启动旧卸载器前都使用相同检查。只有明确点击才结束进程；静默 `/S` 和被动 `/P` 遇占用以 1618 退出。提示说明强制结束跳过 USB 清理，下次启动处理遗留状态。保存和恢复检查所用寄存器，避免影响升级选项。
- 沿用原目录、快捷方式和 Windows 卸载注册；已启用的开机启动更新到安装后的主程序路径。
- 自定义语言源文件必须为 **UTF-8 无 BOM**；Tauri 会为生成的语言文件添加 BOM。

## 构建与回归

运行 `npm run tauri:build`。生成安装包位于 `target/release/bundle/nsis/`。

`tests/installer-fixture.ps1` 从生成后的 NSIS 脚本派生独立的 `USBLinkInstallerQA` 测试产品，使用专用注册表、配置和 `test-results/installer-flow-<版本>/install space`。测试必须始终在同一 Windows 用户环境执行（沙箱与宿主 HKCU 可能不同）。

按顺序执行 `Prepare`、`Baseline`、`Hold`、`SilentBlocked`；再打开生成的 `update-setup.exe` 检查升级默认项、取消后 `AssertBaseline`。重新打开安装器，确认结束进程，随后用 `AssertKilled`、`AssertUpdated` 核对自动继续更新。可用 `Hold -LockOnly` 验证未知进程持锁时不会误杀；应提示失败并允许取消，再执行 `Release`。最后执行 `Cleanup`。

测试安装器移除了完成页的运行复选框。测试 payload 是改名后的真实主程序，**不可运行**；运行检测仅用无害 holder 模拟。测试在旧程序中添加校验差异，核对新版文件确实替换；卸载标记用于证明直接更新没有调用旧卸载器。模拟配置和安装目录用户文件必须保留，快捷方式及开机启动必须指向原安装目录。

`Prepare` 拒绝覆盖现有测试目录、注册表或快捷方式；`Cleanup` 仅卸载测试产品、验证配置保留后删除测试数据，保留构建产物。测试不会关闭真实 USBLink 或操作 USB 驱动。真实产品全新安装的补充脚本为 `tests/installer-smoke.ps1`，需要没有现有 USBLink 安装的测试账户。
