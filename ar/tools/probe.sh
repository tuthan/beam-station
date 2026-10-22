#!/usr/bin/env bash
# Read-only checks of what Station AR needs on one device: tools/probe.sh -s <serial>
# Changes nothing: brand and model, whether Rokid glasses are on the USB bus, the display, the sensors, the GPU,
# rokid_display, the decoders' instance limits, listening ports, and the accessibility services.
set -euo pipefail
SERIAL=""
while getopts "s:" o; do case "$o" in s) SERIAL="$OPTARG" ;; *) exit 2 ;; esac; done
[ -n "$SERIAL" ] || { echo "usage: $0 -s <serial>   (always name the device)" >&2; exit 2; }
adb -s "$SERIAL" shell '
echo "== device"; getprop ro.product.brand; getprop ro.product.model; getprop ro.build.version.release
echo "== glasses on USB (Rokid vendor 04d2)"
found=0
for d in /sys/bus/usb/devices/*; do
  [ -f $d/idVendor ] || continue
  if [ "$(cat $d/idVendor)" = 04d2 ]; then echo "$(cat $d/idVendor):$(cat $d/idProduct) $(cat $d/product 2>/dev/null)"; found=1; fi
done
[ $found = 1 ] || echo "none (an HDMI screen, or nothing)"
echo "== display"; dumpsys display | grep -m1 -oE "deviceProductInfo=DeviceProductInfo\{name=[^,]*"; dumpsys display | grep -m1 -E "mDefaultModeId|fps=" | head -1
echo "== sensors"; dumpsys sensorservice | grep -E "^0x0000000[1-7]\)" | cut -c1-80
echo "== gpu"; dumpsys SurfaceFlinger | grep -m1 -i "GLES:"
echo "== rokid_display"; dumpsys rokid_display 2>/dev/null | grep -E "mConnected|mUsbConnected|mMode3d|mode=" | head -5
echo "== decoders (OMX/c2, hevc and avc)"; dumpsys media.player 2>/dev/null | grep -ciE "hevc|avc" | sed "s/^/codec lines: /"
echo "== listening TCP"; (netstat -tln 2>/dev/null || cat /proc/net/tcp) | head -20
echo "== accessibility"; settings get secure accessibility_enabled; settings get secure enabled_accessibility_services
echo "== station ar"; pm list packages io.github.tuthan.stationar; ps -A | grep -E "stationar|app_process" | grep -v grep || true
'
