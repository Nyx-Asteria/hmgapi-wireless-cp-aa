#!/bin/sh
# Public structural check; private-stock integration is explicitly opt-in.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "${WCP_LEGACY_CEEV_TESTS:-0}" != 1 ] && [ -z "${CEEV_STOCK_LIBRARY:-}" ]; then
    echo "CEEV recipe tests are archival/opt-in on K5-focused 3.x (set WCP_LEGACY_CEEV_TESTS=1)"
    exit 0
fi
python3 "$HERE/check.py"
if [ -z "${CEEV_STOCK_LIBRARY:-}" ]; then
    echo 'CEEV private-stock ARM and MemRecipe checks skipped (set CEEV_STOCK_LIBRARY)'
    exit 0
fi
: "${HMGAPI_SDK:?set HMGAPI_SDK to the released mod SDK}"
[ -f "$CEEV_STOCK_LIBRARY" ] || { echo 'CEEV_STOCK_LIBRARY does not exist' >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" -d "$TMP" "$HERE/CeevRecipeTest.java"
java -cp "$TMP:$HMGAPI_SDK/lib/hmgapi-engine.jar" CeevRecipeTest "$CEEV_STOCK_LIBRARY" "$HERE/../../recipes/aa-tbt-dbfe7bd2.recipe"
python3 "$HERE/emulate.py"
