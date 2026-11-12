#!/bin/sh
# Reports the facts the app needs to show whether the tunnel is working.
echo "PHONEHOP_NOW=$(date +%s)"
if command -v wg >/dev/null 2>&1; then
  wg show mullvad latest-handshakes 2>/dev/null | awk 'NR==1 {print "PHONEHOP_HANDSHAKE=" $2}'
else
  echo "PHONEHOP_WARN=wg command not found"
fi
echo "PHONEHOP_DEFAULT_ROUTE=$(ip -4 route show default 2>/dev/null | head -1)"
