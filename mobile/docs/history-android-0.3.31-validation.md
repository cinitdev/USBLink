# 0.3.31 Android SQLite 初始化修复

日期：2026-11-14。目标为本次已连接的真我 GT5 240W（RMX3823、Android 16），运行 USB ADB 模块 0.3.30。

## 现场与根因

共享及 adbd 正常，历史页显示“历史数据库无法打开；请检查模块数据目录，已有数据未被覆盖”。`/data/adb/usblink/history/adb-history.db` 为 0 字节，root:root、0600；所在目录为 0700。未修改该文件。

在独立的 `/data/local/tmp/usblink-history-probe-20261114` 目录，以相同 app_process 方式调用旧版 SQLite 打开接口，新数据库同样失败。实际异常：

```
java.lang.NullPointerException: Attempt to read from null array
    at android.database.sqlite.SQLiteGlobal.getDefaultSyncMode(SQLiteGlobal.java:157)
    at android.database.sqlite.SQLiteDatabaseConfiguration.resolveSyncMode(SQLiteDatabaseConfiguration.java:305)
    at android.database.sqlite.SQLiteConnection.setSyncModeFromConfiguration(SQLiteConnection.java:387)
```

失败发生在 openDatabase 返回之前，因此创建表后设置 PRAGMA 或删除空文件不能解决根因。零字节只是未完成初始化的结果，不足以判断数据库损坏。排查时编写的空文件／无效文件头归档方案已撤销，最终源码没有加入此逻辑。

## 最终修改

- `AndroidHistoryDatabase` 使用 `SQLiteDatabase.OpenParams`，打开时指定 WAL 和 FULL 同步模式，绕过失败的默认同步配置读取。继续使用系统内置 SQLite，不新增数据库二进制或桌面 ADB。
- 保留自定义损坏处理：不删除有效或损坏非空数据库，不导入 JSON，不重建／归档用户数据。打开失败的 IOException 保留原始 cause，关闭失败不会覆盖最初的异常。
- SQL 表、保留 200 条／分页 50 条、命令去重、返回采集、后台写入方式均不变；不改变 adbd、网络或 USB 挂载逻辑。

## 定向验证

`mobile/daemon/android-test/io/usblink/mobile/AndroidHistoryTestMain.java` 通过 D8 编译，以临时 JAR 在同机 app_process 下运行真实生产 `AndroidHistoryDatabase`、`HistorySqlStore`、`AdbHistory`。仅访问指定的临时目录，检查 8 项：

1. 零字节文件直接初始化为 SQLite，无删除／归档。
2. 实际 journal_mode 为 WAL。
3. 实际 synchronous 为 FULL。
4. AdbHistory 打开后 problem 为空。
5. 两次相同请求只记录第一条。
6. 请求与关联返回可查询。
7. 关闭并重开后记录仍在。
8. 非空损坏文件被拒绝且字节保持原样。

结果：`PASS: 8 Android SQLite checks`。测试请求只传给隔离的观察器，没有执行手机命令来获取结果。测试入口不在生产源码目录，模块 ZIP 不包含测试类。此前主机 JDBC 检查不能覆盖 ROM 的 Android SQLite 实现，所以以同机检查作为本次修复依据。

没有关闭手机共享、改动配对／恢复记录、重启 adbd、重启手机、USB 自动重挂载或安装完整新版模块。正式 WebUI 更新后的端到端观察尚未执行；其他 ROM 不声称已经实测。临时检查文件结束后清理。

## 打包

使用 `mobile/scripts/build.ps1 -UsbAdb -SkipTests` 编译 DEX／WebUI 并打包，保留先前 0.3.30 adbd 启动修复；不重复全量测试、不发布 GitHub。版本为 USB 0.3.31，TCP 共享源码元数据为 0.2.21。

- 产物：`release/USBLink-Mobile-0.3.31-usb-adb-arm64.zip`，8,536,132 字节。
- SHA256：`b0e9fc355435047c2dbaa6c1dee6bd340dc86e5f01a25540efe57c181c8cf51d`。
- ZIP CRC、版本、生产 JAR 一致性、DEX 包含明确 SQLite 打开配置且不含临时恢复／测试类、SHA256 文件均已核对。
- 检查结束读回 GT5：仍为 0.3.30，sharing.enabled／active 均为 true，旧历史错误仍在；新版运行代码需安装后才会生效。手机临时 JAR 和三份测试库已删除，临时目录已移除。

先前 0.3.30 本地 ZIP 在排查中被一次临时构建覆盖，已用手机上读取的原始 daemon JAR 恢复其旧实现并重新打包；归档校验和已重新生成，与先前验证记录中的原始 ZIP 字节校验和不同。0.3.31 使用新的独立文件名，不应把临时构建当作历史恢复修复版安装。
