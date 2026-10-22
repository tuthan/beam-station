#!/usr/bin/env bash
# Builds the Phase 00 probe (Probe.java) into a dex: javac against the platform, then d8.
# Usage: ar/tools/feasibility/build.sh <out-dir>   ->  <out-dir>/probe.dex
# Run it on the Station as the shell, from /data/local/tmp, and delete it afterwards:
#   adb -s <serial> push <out-dir>/probe.dex /data/local/tmp/stationar-probe.dex
#   adb -s <serial> shell CLASSPATH=/data/local/tmp/stationar-probe.dex app_process / feasibility.Probe codecs
#   adb -s <serial> shell rm /data/local/tmp/stationar-probe.dex
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <out-dir>" >&2; exit 2; }
OUT="$1"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$JAVAC" --release 8 -nowarn -cp "$AJ" -d "$OUT/classes" "$(dirname "$0")/Probe.java"
"$BT/d8" --min-api 30 --lib "$AJ" --output "$OUT" $(find "$OUT/classes" -name '*.class')
mv "$OUT/classes.dex" "$OUT/probe.dex"
echo "Built $OUT/probe.dex"
