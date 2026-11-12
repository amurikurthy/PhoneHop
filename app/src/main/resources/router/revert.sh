#!/bin/sh
# Restores the router's ENTIRE configuration to exactly what it was before PhoneHop wiped it.
CFG=${PH_CFG:-/etc/config}
INITD=${PH_INITD:-/etc/init.d}
BK=${PH_BK:-/root/phonehop-backup}
PENDING=${PH_PENDING:-/tmp/phonehop-pending}

[ -f "$BK/config.tgz" ] || { echo "PHONEHOP_ERROR: no backup at $BK/config.tgz" >&2; exit 1; }
rm -f "$CFG"/*
tar xzf "$BK/config.tgz" -C "$CFG" || { echo "PHONEHOP_ERROR: restoring the backup failed" >&2; exit 1; }
rm -f "$PENDING"
( sleep 2
  command -v reload_config >/dev/null 2>&1 && reload_config
  "$INITD/dropbear" restart; wifi reload; "$INITD/network" reload; "$INITD/firewall" reload; "$INITD/dnsmasq" restart
) </dev/null >/dev/null 2>&1 &
echo PHONEHOP_REVERTED
