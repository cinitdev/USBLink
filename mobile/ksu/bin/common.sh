#!/system/bin/sh
STATE=/data/adb/usblink
umask 077

find_flock() {
  for BUSYBOX in /data/adb/ap/bin/busybox /data/adb/ksu/bin/busybox; do
    [ -x "$BUSYBOX" ] && "$BUSYBOX" --list 2>/dev/null | grep -Fxq flock && return 0
  done
  echo '管理器缺少 BusyBox flock，无法安全启动或停止守护服务。请更新 APatch / KernelSU。' >&2
  return 1
}

lock_control() {
  local count=0
  # BusyBox flock has no portable -w option; bound retries of its nonblocking mode.
  # Android mksh marks exec-opened descriptors close-on-exec. Explicitly pass fd 8
  # to flock; the parent keeps the same open file description and therefore the lock.
  while ! "$BUSYBOX" flock -n 8 8>&8; do
    [ "$count" -ge 150 ] && return 1
    sleep 0.1
    count=$((count+1))
  done
}

start_time() {
  local fields
  case "$1" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$1/stat" ] || return 1
  # comm may contain spaces: starttime is field 20 after the closing parenthesis.
  fields=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
  case "$fields" in *') '*) fields=${fields##*) };; *) return 1;; esac
  set -- $fields
  [ "$#" -ge 20 ] || return 1
  shift 19
  printf '%s\n' "$1"
}

write_record() {
  local stamp
  stamp=$(start_time "$2") || return 1
  case "$stamp" in ''|*[!0-9]*) return 1;; esac
  printf '%s %s\n' "$2" "$stamp" > "$1.new" && mv -f "$1.new" "$1"
}

# 0: same living process; 1: absent, exited, or PID reused; 2: cannot verify.
record_current() {
  local extra stamp state
  [ -f "$1" ] || return 1
  read -r RECORD_PID RECORD_STAMP extra < "$1" || return 2
  case "$RECORD_PID:$RECORD_STAMP" in *[!0-9:]*|:*|*:) return 2;; esac
  [ -z "$extra" ] && [ "$RECORD_PID" -gt 1 ] || return 2
  [ -d "/proc/$RECORD_PID" ] || return 1
  stamp=$(start_time "$RECORD_PID") || return 2
  [ -n "$stamp" ] || return 2
  [ "$stamp" = "$RECORD_STAMP" ] || return 1
  state=$(cat "/proc/$RECORD_PID/stat" 2>/dev/null) || return 2
  case "$state" in *') '*) state=${state##*) }; state=${state%% *};; *) return 2;; esac
  [ "$state" = Z ] && return 1
  [ -n "$state" ] || return 2
  return 0
}

has_arg() { tr '\000' '\n' < "/proc/$RECORD_PID/cmdline" | grep -Fxq -- "$1"; }

owned_record() {
  local executable
  record_current "$1" || return 1
  [ -r "/proc/$RECORD_PID/cmdline" ] || return 1
  executable=$(readlink "/proc/$RECORD_PID/exe") || return 1
  case "$2" in
    mesh)
      case "$executable" in
        "$MODDIR/bin/easytier-core"|"$MODDIR/bin/easytier-core (deleted)") has_arg "$STATE/mesh.toml" || return 1;;
        /system/bin/sh|/system/bin/mksh) has_arg "$MODDIR/bin/mesh-run.sh" && has_arg "$MODDIR" || return 1;;
        *) return 1;;
      esac;;
    daemon)
      case "$executable" in /system/bin/app_process|/system/bin/app_process32|/system/bin/app_process64) ;; *) return 1;; esac
      # Accept both app_process startup argv and its later rewritten process title.
      has_arg usblink-mobile || { has_arg --nice-name=usblink-mobile && has_arg io.usblink.mobile.Main && has_arg serve && has_arg "$MODDIR" && has_arg "$STATE"; } || return 1;;
    supervisor)
      case "$executable" in /system/bin/sh|/system/bin/mksh) ;; *) return 1;; esac
      has_arg "$MODDIR/bin/supervise.sh" && has_arg "$MODDIR" || return 1;;
    *) return 1;;
  esac
  kill -0 "$RECORD_PID" 2>/dev/null
}

remove_same_record() {
  [ "$(cat "$1" 2>/dev/null)" = "$2" ] && rm -f "$1"
  return 0
}

stop_record() {
  local record kind saved count result
  record=$1; kind=$2
  [ -f "$record" ] || return 0
  saved=$(cat "$record") || return 1
  # A newly forked child can be between exec and its final process title.
  count=0
  while ! owned_record "$record" "$kind"; do
    record_current "$record"; result=$?
    [ "$result" -eq 1 ] && { remove_same_record "$record" "$saved"; return 0; }
    [ "$result" -eq 2 ] && return 1
    [ "$count" -ge 10 ] && return 1
    sleep 0.1
    count=$((count+1))
  done
  kill -TERM "$RECORD_PID" 2>/dev/null
  count=0
  while [ "$count" -lt 50 ]; do
    record_current "$record"; result=$?
    [ "$result" -eq 1 ] && { remove_same_record "$record" "$saved"; return 0; }
    [ "$result" -eq 2 ] && return 1
    [ "$(cat "$record" 2>/dev/null)" = "$saved" ] || return 1
    if [ "$count" -eq 30 ]; then
      owned_record "$record" "$kind" || return 1
      kill -KILL "$RECORD_PID" 2>/dev/null
    fi
    sleep 0.1
    count=$((count+1))
  done
  # Never erase the only cleanup record while the process is still present.
  echo "无法确认 USBLink $kind 进程已退出，请重试停止。" >&2
  return 1
}

clear_route() {
  local prefix rules address mask octet a b c d
  [ -f "$STATE/mesh.route" ] || return 0
  prefix=$(cat "$STATE/mesh.route") || return 1
  case "$prefix" in ''|*[!0-9./]*|*/*/*) return 1;; */*) ;; *) return 1;; esac
  address=${prefix%/*}; mask=${prefix##*/}
  case "$mask" in ''|*[!0-9]*) return 1;; esac
  [ "$mask" -ge 16 ] && [ "$mask" -le 30 ] || return 1
  local IFS=.
  set -- $address
  [ "$#" -eq 4 ] || return 1
  a=$1; b=$2; c=$3; d=$4
  for octet in "$a" "$b" "$c" "$d"; do
    case "$octet" in ''|*[!0-9]*) return 1;; esac
    [ "$octet" -le 255 ] || return 1
  done
  [ "$a" -eq 10 ] || { [ "$a" -eq 172 ] && [ "$b" -ge 16 ] && [ "$b" -le 31 ]; } || { [ "$a" -eq 192 ] && [ "$b" -eq 168 ]; } || return 1
  # Match the complete selector; never flush a rule table or remove another priority.
  /system/bin/ip -4 rule del priority 17891 to "$prefix" lookup main >/dev/null 2>&1 || {
    rules=$(/system/bin/ip -4 rule show 2>/dev/null) || return 1
    printf '%s\n' "$rules" | grep -F "17891:" | grep -F "to $prefix lookup main" >/dev/null && return 1
  }
  rm -f "$STATE/mesh.route"
}

clear_rpc_rule() {
  # The caller must first confirm EasyTier has stopped; never expose a live RPC port.
  [ ! -f "$STATE/mesh.pid" ] || return 1
  /system/bin/iptables -w 3 -C OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT >/dev/null 2>&1 || {
    /system/bin/iptables -w 3 -S OUTPUT >/dev/null 2>&1 || return 1
    return 0
  }
  /system/bin/iptables -w 3 -D OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT
}

restore_adb() {
  [ -f "$STATE/adbd-tcp.json" ] || return 0
  CLASSPATH="$MODDIR/lib/usblink-daemon.jar" /system/bin/app_process / --nice-name=usblink-restore io.usblink.mobile.Main restore-adb "$STATE" 8>&- 9>&-
}

# Only read one atomic snapshot, and confirm its author is the current living daemon.
# This path does not launch app_process or wait for the controller's operation lock.
read_status() {
  local snapshot_pid snapshot_stamp snapshot_boot snapshot_time extra json current_boot uptime ignored
  [ ! -f "$STATE/stopping" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 1
  [ -r "$STATE/status.snapshot" ] || return 1
  {
    read -r snapshot_pid snapshot_stamp snapshot_boot snapshot_time extra && read -r json
  } < "$STATE/status.snapshot" || return 1
  [ -z "$extra" ] || return 1
  case "$snapshot_pid:$snapshot_stamp:$snapshot_time" in *[!0-9:]*|:*|*::*|*:) return 1;; esac
  [ "${#snapshot_time}" -le 10 ] || return 1
  read -r current_boot < /proc/sys/kernel/random/boot_id || return 1
  [ "$current_boot" = "$snapshot_boot" ] || return 1
  read -r uptime ignored < /proc/uptime || return 1
  uptime=${uptime%%.*}
  case "$uptime" in ''|*[!0-9]*) return 1;; esac
  # Suspend counts toward expiry; a stale worker never looks like a responsive service.
  [ "$snapshot_time" -le "$uptime" ] && [ "$((uptime-snapshot_time))" -le 10 ] || return 1
  owned_record "$STATE/daemon.pid" daemon || return 1
  [ "$RECORD_PID" = "$snapshot_pid" ] && [ "$RECORD_STAMP" = "$snapshot_stamp" ] || return 1
  case "$json" in '{"ok":true,"data":{'*'}}') ;; *) return 1;; esac
  printf '%s\n' "$json"
}
