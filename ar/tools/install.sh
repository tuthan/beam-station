#!/usr/bin/env bash
# Builds Station AR and installs it on one device: tools/install.sh -s <serial> [release|debug]
# The release key (ar/stationar.keystore) is created once, here, and never committed: updates need the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL=""
while getopts "s:" o; do case "$o" in s) SERIAL="$OPTARG" ;; *) exit 2 ;; esac; done
shift $((OPTIND - 1))
[ -n "$SERIAL" ] || { echo "usage: $0 -s <serial> [release|debug]   (always name the device)" >&2; exit 2; }
TYPE="${1:-release}"
case "$TYPE" in release|debug) ;; *) echo "build type: release or debug" >&2; exit 2 ;; esac
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
if [ "$TYPE" = release ] && [ ! -f stationar.keystore ]; then
  "${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -genkeypair -keystore stationar.keystore -alias stationar -keyalg RSA \
    -keysize 2048 -validity 20000 -storepass stationar -keypass stationar -dname "CN=Station AR" >/dev/null 2>&1
  echo "Created stationar.keystore (keep it: updates need the same key)."
fi
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<< "${TYPE:0:1}")${TYPE:1}"
./gradlew --offline -q ":app:$TASK"
APK="app/build/outputs/apk/$TYPE/app-$TYPE.apk"
[ -f "$APK" ] || { echo "no $APK (is the release unsigned? see stationar.keystore)" >&2; exit 1; }
adb -s "$SERIAL" install -r "$APK"
echo "Installed $TYPE on $SERIAL ($(sha256sum "$APK" | cut -c1-12))"
