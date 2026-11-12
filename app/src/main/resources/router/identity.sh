#!/bin/sh
# Reports which router this is, so the app can remember it. Changes nothing.
SYS=${PH_SYS:-/sys}
PROC=${PH_PROC:-/proc}
board=$(ubus call system board 2>/dev/null | tr -d '\n')
field() { echo "$board" | sed -n "s/.*\"$1\": *\"\\([^\"]*\\)\".*/\\1/p"; }
model=$(field model)
[ -n "$model" ] || model=$(cat /tmp/sysinfo/model 2>/dev/null)
mac=$(cat "$SYS/class/net/br-lan/address" 2>/dev/null || cat "$SYS/class/net/eth0/address" 2>/dev/null)
serial=""
for f in "$PROC/gl-hw-info/device_sn" "$PROC/gl-hw-info/device_ddns"; do
  [ -s "$f" ] && { serial=$(head -1 "$f" | tr -d '\r'); break; }
done
echo "PHONEHOP_MODEL=$model"
echo "PHONEHOP_BOARD=$(field board_name)"
echo "PHONEHOP_FIRMWARE=$(field description)"
echo "PHONEHOP_MAC=$mac"
echo "PHONEHOP_SERIAL=$serial"
