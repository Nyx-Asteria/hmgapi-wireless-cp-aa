#!/usr/bin/env python3
"""Ensure the source-floor CI adaptation matches the pinned HMG-API SDK."""
import json, pathlib, re
pin=json.load(open('hmgapi-sdk.json'))['ref']
local=pathlib.Path('.github/workflows/mod-source-floor.yml').read_text()
assert 'Adapted from Open-HMG/HMG-API mod.yml at '+pin+'.' in local
assert re.findall(r'^\s*ref: ([0-9a-f]{40})\s*$', local, re.M) == [pin], 'immutable floor checkout must match SDK pin'
ci = pathlib.Path('.github/workflows/ci.yml').read_text()
release = pathlib.Path('.github/workflows/release.yml').read_text()
assert re.search(r'^  workflow_call:[ \t]*$', ci, re.M), 'CI must be reusable'
assert re.search(r'^    uses: \./\.github/workflows/mod-source-floor\.yml[ \t]*$', ci, re.M), 'CI must use pinned floor'
assert re.search(r'^  ci:\n    needs: version\n    uses: \./\.github/workflows/ci\.yml[ \t]*$', release, re.M), 'release must use full CI after version gate'
assert re.search(r'^  release:\n    needs: ci[ \t]*$', release, re.M), 'signing/publication must wait for full CI'
print('ok HMG-API pin',pin)
