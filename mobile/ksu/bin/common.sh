#!/system/bin/sh
STATE=/data/adb/usblink
umask 077

# Module data lives in config/history/runtime/logs when it can be moved there.
# Resolution needs no layout marker: an existing file stays usable in place, and
# a new file is created in its group only when that directory exists. Nothing
# here may block startup; a failed tidy-up only leaves files where they were.
state_group() {
  case "$1" in
    mesh.json|sharing.json) STATE_GROUP=config;;
    adb-history.db|adb-history.db-wal|adb-history.db-shm|adb-history.db-journal) STATE_GROUP=history;;
    adbd-tcp.json|mesh.toml|mesh.route|daemon.pid|mesh.pid|status.snapshot|stopping|supervisor.lock/pid) STATE_GROUP=runtime;;
    daemon.log|daemon.previous.log|daemon.stderr.log|daemon.previous.stderr.log|startup.error) STATE_GROUP=logs;;
    *) STATE_GROUP=;;
  esac
}

# live: current runtime state, so a newer organized copy wins over a stale one.
# data: persistent data, so a legacy flat file keeps winning until it is moved.
state_path() {
  local name=$1 order=$2 group canonical legacy
  state_group "$name"
  group=$STATE_GROUP
  [ -n "$group" ] || { printf '%s/%s\n' "$STATE" "$name"; return; }
  canonical=$STATE/$group/$name
  legacy=$STATE/$name
  if [ "$order" = live ]; then
    if [ -e "$canonical" ]; then printf '%s\n' "$canonical"; return; fi
    if [ -e "$legacy" ]; then printf '%s\n' "$legacy"; return; fi
  else
    if [ -e "$legacy" ]; then printf '%s\n' "$legacy"; return; fi
    if [ -e "$canonical" ]; then printf '%s\n' "$canonical"; return; fi
  fi
  if [ -d "$STATE/$group" ]; then printf '%s\n' "$canonical"; else printf '%s\n' "$legacy"; fi
}
state_live() { state_path "$1" live; }
state_file() { state_path "$1" data; }

find_flock() {
  # These are the managers' own binaries; never use a WebUI-provided path.
  for BUSYBOX in /data/adb/ap/bin/busybox /data/adb/ksu/bin/busybox /data/adb/magisk/busybox; do
    [ -x "$BUSYBOX" ] && "$BUSYBOX" --list 2>/dev/null | grep -Fxq flock && return 0
  done
  BUSYBOX=
  echo '管理器缺少 BusyBox flock，无法安全启动或停止守护服务。请更新 Magisk / APatch / KernelSU。' >&2
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
  IFS= read -r fields < "/proc/$1/stat" || return 1
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

record_well_formed() {
  local pid stamp extra
  read -r pid stamp extra < "$1" || return 1
  case "$pid:$stamp" in *[!0-9:]*|:*|*::*|*:) return 1;; esac
  [ -z "$extra" ] && [ "$pid" -gt 1 ]
}

# Called only while holding both startup locks. Never infer that an unknown PID
# is safe to kill. A malformed record can be retired only with no module workers.
runtime_workers_absent() {
  local proc args executable
  for proc in /proc/[0-9]*; do
    [ "${proc##*/}" = "$$" ] && continue
    args=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null) || {
      [ ! -d "$proc" ] && continue
      return 1
    }
    [ -n "$args" ] || continue
    executable=$(readlink "$proc/exe" 2>/dev/null) || {
      [ ! -d "$proc" ] && continue
      return 1
    }
    case "$executable" in "$MODDIR/bin/easytier-core"|"$MODDIR/bin/easytier-core (deleted)") return 1;; esac
    printf '%s\n' "$args" | grep -Fxq -e usblink-mobile -e --nice-name=usblink-mobile \
      -e "$MODDIR/bin/mesh-run.sh" -e "$MODDIR/bin/supervise.sh" && return 1
  done
  return 0
}

# Tidy-up guard: report a module worker only on positive evidence. Unreadable
# entries of unrelated system processes must not keep the directory untidy.
module_workers_absent() {
  local proc args executable
  for proc in /proc/[0-9]*; do
    [ "${proc##*/}" = "$$" ] && continue
    args=$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null) || continue
    [ -n "$args" ] || continue
    printf '%s\n' "$args" | grep -Fxq -e usblink-mobile -e --nice-name=usblink-mobile \
      -e "$MODDIR/bin/mesh-run.sh" -e "$MODDIR/bin/supervise.sh" && return 1
    executable=$(readlink "$proc/exe" 2>/dev/null) || continue
    case "$executable" in "$MODDIR/bin/easytier-core"|"$MODDIR/bin/easytier-core (deleted)") return 1;; esac
  done
  return 0
}

# One best-effort tidy-up per start: move flat files into their group. It always
# returns 0 and skips everything while a module process is alive, so a failure or
# a running service can never block startup. Existing targets are never replaced.
organize_state() {
  local name group from to stamp
  module_workers_absent || return 0
  for group in config history runtime logs; do
    mkdir -p "$STATE/$group" 2>/dev/null || return 0
  done
  stamp=$(date +%s)
  # The flat database is the copy the previous version used. Keep it as the live
  # one and retire an older organized copy into backups; never overwrite either.
  if [ -f "$STATE/adb-history.db" ]; then
    if [ -f "$STATE/history/adb-history.db" ]; then
      mkdir -p "$STATE/backups/legacy/db-$stamp" 2>/dev/null
      for name in adb-history.db adb-history.db-wal adb-history.db-shm adb-history.db-journal; do
        [ ! -f "$STATE/history/$name" ] || mv "$STATE/history/$name" "$STATE/backups/legacy/db-$stamp/" 2>/dev/null || :
      done
    fi
    if [ ! -e "$STATE/history/adb-history.db" ]; then
      for name in adb-history.db-wal adb-history.db-shm adb-history.db-journal adb-history.db; do
        [ -f "$STATE/$name" ] && [ ! -L "$STATE/$name" ] || continue
        [ ! -e "$STATE/history/$name" ] || continue
        mv "$STATE/$name" "$STATE/history/$name" 2>/dev/null || :
      done
    fi
  fi
  for name in mesh.json sharing.json adbd-tcp.json mesh.toml mesh.route \
              daemon.pid mesh.pid status.snapshot stopping \
              daemon.log daemon.previous.log daemon.stderr.log daemon.previous.stderr.log startup.error; do
    state_group "$name"
    group=$STATE_GROUP
    from=$STATE/$name; to=$STATE/$group/$name
    [ -f "$from" ] && [ ! -L "$from" ] || continue
    if [ -e "$to" ] || [ -L "$to" ]; then
      [ -f "$to" ] || continue
      # Identical leftovers are duplicates. A different organized copy is older
      # (the flat one was in use), so retire it instead of overwriting anything.
      cmp -s "$from" "$to" 2>/dev/null && { rm -f "$from" 2>/dev/null; continue; }
      mkdir -p "$STATE/backups/legacy/conflict-$stamp" 2>/dev/null || continue
      mv "$to" "$STATE/backups/legacy/conflict-$stamp/" 2>/dev/null || continue
    fi
    [ ! -e "$to" ] && [ ! -L "$to" ] || continue
    mv "$from" "$to" 2>/dev/null || :
  done
  if [ -d "$STATE/supervisor.lock" ]; then
    for name in pid pid.new; do
      [ -f "$STATE/supervisor.lock/$name" ] || continue
      [ ! -e "$STATE/runtime/supervisor.lock/$name" ] || continue
      mkdir -p "$STATE/runtime/supervisor.lock" 2>/dev/null || break
      mv "$STATE/supervisor.lock/$name" "$STATE/runtime/supervisor.lock/$name" 2>/dev/null || :
    done
    rmdir "$STATE/supervisor.lock" 2>/dev/null || :
  fi
  for name in "$STATE"/recovery-*; do
    [ -d "$name" ] && [ ! -L "$name" ] || continue
    [ ! -e "$STATE/backups/${name##*/}" ] || continue
    mkdir -p "$STATE/backups" 2>/dev/null || break
    mv "$name" "$STATE/backups/" 2>/dev/null || :
  done
  # Superseded JSON/Root files are archived, never read or imported.
  for name in adb-history.jsonl adb-root-preference.json adb-root-recovery.json; do
    [ -f "$STATE/$name" ] && [ ! -L "$STATE/$name" ] || continue
    [ ! -e "$STATE/backups/legacy/$name" ] || continue
    mkdir -p "$STATE/backups/legacy" 2>/dev/null || continue
    mv "$STATE/$name" "$STATE/backups/legacy/$name" 2>/dev/null || :
  done
  rm -f "$STATE/config/layout.version" "$STATE/config/layout.version.new" 2>/dev/null || :
  return 0
}

recover_runtime_records() {
  local file damaged=0 backup daemon_pid mesh_pid supervisor_pid
  daemon_pid=$(state_live daemon.pid)
  mesh_pid=$(state_live mesh.pid)
  supervisor_pid=$(state_live supervisor.lock/pid)
  for file in "$daemon_pid" "$mesh_pid" "$supervisor_pid"; do
    [ ! -f "$file" ] || record_well_formed "$file" || damaged=1
  done
  [ "$damaged" -eq 1 ] || return 0
  runtime_workers_absent || { echo '运行记录损坏，但仍有模块进程或无法确认进程状态；已保留记录，未终止任何进程。' >&2; return 1; }
  mkdir -p "$STATE/backups" || return 1
  backup="$STATE/backups/recovery-$(date +%s)-$$"
  mkdir "$backup" || return 1
  for file in "$daemon_pid" "$mesh_pid" "$supervisor_pid"; do
    [ -f "$file" ] || continue
    record_well_formed "$file" && continue
    case "$file" in */supervisor.lock/pid) mv "$file" "$backup/supervisor.pid" || return 1;;
      *) mv "$file" "$backup/" || return 1;; esac
  done
  file=$(state_live status.snapshot)
  [ ! -f "$file" ] || mv "$file" "$backup/" || return 1
  file=$(state_live startup.error)
  [ ! -f "$file" ] || mv "$file" "$backup/" || return 1
  # Pairing, preferences, history and adbd recovery journals are never touched.
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
  IFS= read -r state < "/proc/$RECORD_PID/stat" || return 2
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
        "$MODDIR/bin/easytier-core"|"$MODDIR/bin/easytier-core (deleted)") has_arg "$(state_file mesh.toml)" || return 1;;
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
  local prefix rules address mask octet a b c d record
  record=$(state_file mesh.route)
  [ -f "$record" ] || return 0
  prefix=$(cat "$record") || return 1
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
  rm -f "$record"
}

clear_rpc_rule() {
  # The caller must first confirm EasyTier has stopped; never expose a live RPC port.
  [ ! -f "$(state_live mesh.pid)" ] || return 1
  /system/bin/iptables -w 3 -C OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT >/dev/null 2>&1 || {
    /system/bin/iptables -w 3 -S OUTPUT >/dev/null 2>&1 || return 1
    return 0
  }
  /system/bin/iptables -w 3 -D OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT
}

restore_adb() {
  [ -f "$(state_file adbd-tcp.json)" ] || return 0
  CLASSPATH="$MODDIR/lib/usblink-daemon.jar" /system/bin/app_process / --nice-name=usblink-restore io.usblink.mobile.Main restore-adb "$STATE" 8>&- 9>&-
}

# Only read one atomic snapshot, and confirm its author is the current living daemon.
# This path does not launch app_process or wait for the controller's operation lock.
read_status() {
  local snapshot_pid snapshot_stamp snapshot_boot snapshot_time extra json current_boot uptime ignored stop status_file daemon_pid
  stop=$(state_live stopping)
  status_file=$(state_live status.snapshot)
  daemon_pid=$(state_live daemon.pid)
  [ ! -f "$stop" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 1
  [ -r "$status_file" ] || return 1
  {
    read -r snapshot_pid snapshot_stamp snapshot_boot snapshot_time extra && read -r json
  } < "$status_file" || return 1
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
  owned_record "$daemon_pid" daemon || return 1
  [ "$RECORD_PID" = "$snapshot_pid" ] && [ "$RECORD_STAMP" = "$snapshot_stamp" ] || return 1
  case "$json" in '{"ok":true,"data":{'*'}}') ;; *) return 1;; esac
  printf '%s\n' "$json"
}
