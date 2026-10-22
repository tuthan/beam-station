#!/usr/bin/env bash
# Station AR's log on one device: tools/logcat.sh -s <serial> [-d]   (-d: dump and exit)
set -euo pipefail
SERIAL=""
DUMP=""
while getopts "s:d" o; do case "$o" in s) SERIAL="$OPTARG" ;; d) DUMP="-d" ;; *) exit 2 ;; esac; done
[ -n "$SERIAL" ] || { echo "usage: $0 -s <serial> [-d]   (always name the device)" >&2; exit 2; }
exec adb -s "$SERIAL" logcat $DUMP -v time -s StationAR StationARHelper AndroidRuntime:E
