#!/bin/sh
# The phone link works: cancel the automatic rollback.
rm -f "${PH_PENDING:-/tmp/phonehop-pending}"
echo PHONEHOP_CONFIRMED
