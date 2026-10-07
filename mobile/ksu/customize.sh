#!/system/bin/sh
ui_print "USBLink Mobile · APatch / KernelSU"
[ "$ARCH" = "arm64" ] || abort "仅支持 arm64 手机"
case "$API" in ''|*[!0-9]*) abort "无法识别 Android 版本，需要 Android 12 或更高版本";; esac
[ "$API" -ge 31 ] || abort "需要 Android 12 或更高版本，当前系统无法安装此模块"
if [ "$APATCH" = "true" ] || [ -n "$APATCH_VER_CODE" ]; then
  ui_print "已识别 APatch"
elif [ "$KSU" = "true" ] || [ -n "$KSU_VER_CODE" ]; then
  ui_print "已识别 KernelSU"
else
  abort "请使用 APatch 或 KernelSU 管理器安装"
fi
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/lib" 0 0 0755 0644
for script in service.sh action.sh uninstall.sh; do
  set_perm "$MODPATH/$script" 0 0 0755
done
ui_print "安装后可点击操作启动；若管理器提示重启，请按提示完成首次安装。"
ui_print "日常共享开关无需重启手机。"
ui_print "首次使用请开启 USB 调试，在 WebUI 配置共享，无需插线。"
ui_print "启停共享可能重启系统 adbd，短暂中断已有调试连接。"
