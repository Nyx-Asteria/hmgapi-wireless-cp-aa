# K5 US 260507 offline integration tests

The default WCP 3.x installation/network regression suite uses DL322 and DL3 US
260507 selector fixtures and the unchanged US KMC patch set. It exercises
the actual HMG-API engine, OEM hotspot path, firewall ownership, settings
changes, unsupported K5 firmware refusal and byte-identical file restore.
No fake stock hashes are injected.

Run via `wireless-carplay-aa/test.sh` or
`HMGAPI_SDK=/path/to/sdk python3 wireless-carplay-aa/tools/k5/integration.py`.
The synthetic product strings and onebin name are fixture values, not
confirmed full OEM property dumps. Override the private onebin using
K5_TEST_ONEBIN (Python) or the third argument to test.sh. Optional K5_STOCK
points to a private system tree; the KMC ODEX fingerprints are checked without
publishing OEM files. Real stock must be supplied before claiming firmware
fingerprint coverage. Neither suite exercises an actual phone or head unit.

The Ioniq 6 suite remains manually runnable in tools/ioniq6; CEEV recipe tests
are opt-in (WCP_LEGACY_CEEV_TESTS=1). Shared generic identity/codec and logging
tests remain; EV identity vectors are protocol regression fixtures, not
supported-vehicle targets. This is test focus only; installer manifests and
runtime selectors are unchanged.
