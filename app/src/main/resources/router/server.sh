#!/bin/sh
# Points the existing PhoneHop tunnel at a different VPN server (a new WireGuard public key).
PUB=@@SERVER_PUBLIC_KEY@@

uci -q get network.mullvad_peer.public_key >/dev/null 2>&1 || { echo "PHONEHOP_ERROR: the tunnel is not set up on this router" >&2; exit 1; }
uci set "network.mullvad_peer.public_key=$PUB" || { echo "PHONEHOP_ERROR: uci set failed" >&2; exit 1; }
uci commit network || { echo "PHONEHOP_ERROR: uci commit failed" >&2; exit 1; }
( sleep 1; ifdown mullvad; ifup mullvad ) </dev/null >/dev/null 2>&1 &
echo PHONEHOP_SERVER_UPDATED
