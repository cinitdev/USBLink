#!/system/bin/sh
MODDIR=${0%/*}
if [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; then
  /system/bin/sh "$MODDIR/bin/supervise.sh" "$MODDIR" >/dev/null 2>&1 &
fi
