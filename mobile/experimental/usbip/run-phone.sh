#!/system/bin/sh
# Run only on the identified, authorized test phone. No boot hook or module replacement.
set -eu
PROBE=/data/adb/usblink-usb-probe
CTL=/data/adb/modules/usblink-mobile/bin/usblinkctl
[ "$(id -u)" = 0 ] || exit 1
[ "$#" = 1 ] || exit 1
export CLASSPATH="$PROBE/usblink-usb-probe.jar"
/system/bin/app_process / --nice-name=usblink-usb-probe io.usblink.mobile.UsbProbeMain "$1" >"$PROBE/probe.log" 2>&1 &
PROBE_PID=$!
echo "$PROBE_PID" >"$PROBE/probe.pid"
wait "$PROBE_PID" || true
# Also recover after an unexpected process exit. Keep the record on failed restoration.
if [ -f "$PROBE/restore-sharing" ]; then
  if [ "$(cat "$PROBE/restore-sharing")" = off ]; then
    "$CTL" set-sharing eyJlbmFibGVkIjpmYWxzZX0= >"$PROBE/recovery.json"
    case "$(cat "$PROBE/recovery.json")" in *'"ok":true'*) rm -f "$PROBE/restore-sharing";; esac
  fi
fi
if [ -f "$PROBE/restore-whitelist" ]; then
  PROBE_CLI=/data/adb/modules/usblink-mobile/bin/easytier-cli
  PROBE_RULES=$("$PROBE_CLI" -p 127.0.0.1:15891 whitelist show)
  case "$PROBE_RULES" in
    *'TCP Whitelist: 3240-3242'*) "$PROBE_CLI" -p 127.0.0.1:15891 whitelist set-tcp 3241-3242 && rm -f "$PROBE/restore-whitelist";;
    *'TCP Whitelist: 3241-3242'*) rm -f "$PROBE/restore-whitelist";;
  esac
fi
for PROBE_IPTABLES in /system/bin/iptables /system/bin/ip6tables; do
  if "$PROBE_IPTABLES" -w 2 -C INPUT ! -i usblink0 -p tcp --dport 3240 -m comment --comment usblink-usb-probe -j DROP 2>/dev/null; then
    "$PROBE_IPTABLES" -w 2 -D INPUT ! -i usblink0 -p tcp --dport 3240 -m comment --comment usblink-usb-probe -j DROP
  fi
done
rm -f "$PROBE/ready" "$PROBE/probe.pid"
