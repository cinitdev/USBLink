#!/system/bin/sh
MODDIR=$1
[ "$MODDIR" = /data/adb/modules/usblink-mobile ] || exit 1
. "$MODDIR/bin/common.sh"
mkdir -p "$STATE" || exit 1
chmod 0700 "$STATE" || exit 1
find_flock || exit 1
exec 8>"$STATE/control.flock" || exit 1
lock_control || { echo '启动操作仍在进行，停止尚未完成，请重试。' >&2; exit 1; }
: > "$STATE/stopping" || exit 1
failed=0
stop_record "$STATE/supervisor.lock/pid" supervisor || failed=1
stop_record "$STATE/daemon.pid" daemon || failed=1
stop_record "$STATE/mesh.pid" mesh || failed=1
restore_adb || failed=1
if [ "$failed" -eq 0 ]; then
  clear_route || failed=1
  clear_rpc_rule || failed=1
fi
if [ "$failed" -ne 0 ]; then
  echo '无法确认所有共享进程或网络规则已清理，请重试停止。' >&2
  exit 1
fi
rm -f "$STATE/startup.error"
