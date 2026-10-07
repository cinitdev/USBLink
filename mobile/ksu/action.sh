#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/bin/common.sh"
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
  echo "模块已禁用或待卸载，请先在管理器启用。"
  exit 1
fi
find_flock || exit 1
/system/bin/sh "$MODDIR/bin/supervise.sh" "$MODDIR" >/dev/null 2>&1 &
attempt=0
while [ "$attempt" -lt 10 ]; do
  result=$("$BUSYBOX" timeout 3 "$MODDIR/bin/usblinkctl" status 2>/dev/null)
  case "$result" in
    '{"ok":true,'*) echo "USBLink 控制服务已就绪，请打开模块 WebUI。"; exit 0;;
  esac
  sleep 1
  attempt=$((attempt+1))
done
if [ -s /data/adb/usblink/startup.error ]; then
  cat /data/adb/usblink/startup.error
  exit 1
fi
echo "USBLink 控制服务未能就绪，启动未确认，请查看以下模块错误后重试："
printf '%s\n' "$result"
if [ -s "$STATE/daemon.log" ]; then
  # Print only the daemon's fixed JSON error, never arbitrary runtime/subprocess logs.
  grep -F -m 1 '{"ok":false,' "$STATE/daemon.log"
fi
exit 1
