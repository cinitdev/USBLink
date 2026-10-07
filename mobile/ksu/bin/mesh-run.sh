#!/system/bin/sh
MODDIR=$1
[ "$MODDIR" = /data/adb/modules/usblink-mobile ] || exit 1
. "$MODDIR/bin/common.sh"
find_flock || exit 1
exec 8>"$STATE/control.flock" || exit 1
lock_control || exit 1
PARENT_PID=$PPID
owned_record "$STATE/daemon.pid" daemon || exit 1
[ "$RECORD_PID" = "$PARENT_PID" ] || exit 1
PARENT_STAMP=$RECORD_STAMP
parent_still_owned() {
  [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$STATE/stopping" ] || return 1
  owned_record "$STATE/daemon.pid" daemon || return 1
  [ "$RECORD_PID" = "$PARENT_PID" ] && [ "$RECORD_STAMP" = "$PARENT_STAMP" ]
}
parent_still_owned || exit 1
# Register before any TUN/firewall work. Stop can now identify this shell too.
write_record "$STATE/mesh.pid" $$ || exit 1
# EasyTier's management RPC has no Android UID authentication. Restrict loopback
# clients before starting it; the Java control socket separately checks UID 0.
if ! /system/bin/iptables -w 3 -C OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT 2>/dev/null; then
  /system/bin/iptables -w 3 -I OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT || exit 1
fi
if [ ! -e /dev/net/tun ] && [ -c /dev/tun ]; then
  mkdir -p /dev/net
  ln -s /dev/tun /dev/net/tun
fi
# exec retains the PID and start time; secrets exist only in this root-only file.
parent_still_owned || exit 1
# Do not let the persistent EasyTier process inherit the start/stop operation lock.
exec 8>&-
exec "$MODDIR/bin/easytier-core" -c "$STATE/mesh.toml" --rpc-portal 127.0.0.1:15891 --rpc-portal-whitelist 127.0.0.1/32 --dev-name usblink0 --encryption-algorithm aes-256-gcm --disable-ipv6 true --latency-first true --console-log-level off --file-log-level off
