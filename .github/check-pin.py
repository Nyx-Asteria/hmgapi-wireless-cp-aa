#!/usr/bin/env python3
"""Ensure the source-floor CI adaptation matches the pinned HMG-API SDK."""
import json, pathlib
pin=json.load(open('hmgapi-sdk.json'))['ref']
local=pathlib.Path('.github/workflows/mod-source-floor.yml').read_text()
assert 'Adapted from Open-HMG/HMG-API mod.yml at '+pin+'.' in local
for f in ('ci.yml','release.yml'):
    assert 'uses: ./.github/workflows/mod-source-floor.yml' in pathlib.Path('.github/workflows',f).read_text()
print('ok HMG-API pin',pin)
