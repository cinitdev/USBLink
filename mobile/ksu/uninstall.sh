#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/bin/stop.sh" "$MODDIR"
# Preserve pairing and the user's saved disabled state across reinstall.
# This directory is root-only and can be explicitly removed by the owner.
