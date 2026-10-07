#!/system/bin/sh
MODDIR=$1
[ "$MODDIR" = /data/adb/modules/usblink-mobile ] || exit 1
. "$MODDIR/bin/common.sh"
mkdir -p "$STATE" || exit 1
chmod 0700 "$STATE" || exit 1
startup_error() { printf '%s\n' "$1" > "$STATE/startup.error"; echo "$1" >&2; }
find_flock || { startup_error '管理器缺少 BusyBox flock，请更新 APatch / KernelSU。'; exit 1; }
# Do not unlink these lock files: the kernel releases the locks even after SIGKILL/reboot.
# The short control lock serializes start/stop; the lifetime lock excludes other supervisors.
exec 8>"$STATE/control.flock" || exit 1
lock_control || { startup_error '另一项启动或停止操作尚未完成，请稍后重试。'; exit 1; }
exec 9>"$STATE/supervisor.flock" || exit 1
# mksh otherwise closes descriptor 9 before executing the external BusyBox command.
"$BUSYBOX" flock -n 9 9>&9 || exit 0
LOCK=$STATE/supervisor.lock
mkdir -p "$LOCK" || exit 1
# A supervisor from the old mkdir-lock implementation must not be disturbed.
owned_record "$LOCK/pid" supervisor && exit 0
write_record "$LOCK/pid" $$ || { startup_error '无法保存守护进程状态，启动已取消。'; exit 1; }
rm -f "$STATE/stopping" "$STATE/startup.error"
child=
finish() {
  trap '' TERM INT HUP
  # Capture our direct child even if a signal interrupted the short spawn/record window.
  local candidate failed
  candidate=$child
  [ -n "$candidate" ] || candidate=$!
  if [ ! -f "$STATE/daemon.pid" ] && [ -n "$candidate" ]; then
    write_record "$STATE/daemon.pid" "$candidate" 2>/dev/null
  fi
  failed=0
  stop_record "$STATE/daemon.pid" daemon || failed=1
  stop_record "$STATE/mesh.pid" mesh || failed=1
  restore_adb || failed=1
  if [ "$failed" -eq 0 ]; then
    clear_route || failed=1
    clear_rpc_rule || failed=1
  fi
  [ "$failed" -eq 0 ] || startup_error '共享服务清理尚未完成，请重新停止后再启动。'
  rm -f "$LOCK/pid"
  return "$failed"
}
trap 'finish; exit $?' TERM INT HUP
delay=2
while [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$STATE/stopping" ]; do
  stop_record "$STATE/daemon.pid" daemon && stop_record "$STATE/mesh.pid" mesh && clear_route || { finish; exit 1; }
  # Java also checks this journal before allowing sharing. Keep management available on failure.
  restore_adb || startup_error '系统调试配置尚未恢复，请在 WebUI 关闭共享重试。'
  if [ -f "$STATE/daemon.log" ]; then mv -f "$STATE/daemon.log" "$STATE/daemon.previous.log"; fi
  CLASSPATH="$MODDIR/lib/usblink-daemon.jar" /system/bin/app_process / --nice-name=usblink-mobile io.usblink.mobile.Main serve "$MODDIR" "$STATE" > "$STATE/daemon.log" 2>&1 8>&- 9>&- &
  child=$!
  write_record "$STATE/daemon.pid" "$child" || { startup_error '无法保存后台进程状态，启动已取消。'; finish; exit 1; }
  exec 8>&-
  uptime=0
  # Start time, not kill -0 alone, prevents a recycled PID keeping this loop alive.
  while true; do
    record_current "$STATE/daemon.pid"; current=$?
    [ "$current" -eq 1 ] && break
    [ "$current" -eq 2 ] && { startup_error '无法确认后台进程身份，自动启动已暂停。'; finish; exit 1; }
    if [ ! -d "$MODDIR" ] || [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ -f "$STATE/stopping" ]; then finish; exit $?; fi
    sleep 2 9>&-
    uptime=$((uptime+2))
    if [ -f "$STATE/daemon.log" ] && [ "$(wc -c < "$STATE/daemon.log")" -gt 65536 ]; then : > "$STATE/daemon.log"; fi
  done
  wait "$child" 2>/dev/null
  child=
  stop_record "$STATE/daemon.pid" daemon && stop_record "$STATE/mesh.pid" mesh && clear_route || { finish; exit 1; }
  [ "$uptime" -ge 60 ] && delay=2
  sleep "$delay" 9>&-
  [ "$delay" -lt 60 ] && delay=$((delay*2))
  [ "$delay" -gt 60 ] && delay=60
done
finish
