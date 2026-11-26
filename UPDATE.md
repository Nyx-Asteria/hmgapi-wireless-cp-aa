# WCP update guide — 2.2.1-rc.4

Current K5 test baseline: US DL322 260507, HMG-API >=1.1.0-rc.8. This is documentation for the existing version, not a new release or transport change.

## Update
- Keep the known-good ZIP and record installed versions. Download the fork's signed package from its [releases](https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/releases).
- Place the intact ZIP in HMGAPI/updates on USB, check USB updates, apply and restart if required. An update needs the same author key; for a different signer remove and apply removal before installing.
- Preserve the working hotspot configuration. On K5, the existing Wi-Fi enablement prerequisite may still be required; do not blindly remove it or install competing projection patches.
- Only reset pairing if needed to resolve connection problems.

## With Standalone TBT
- Set Turn-by-turn on the cluster and HUD OFF (rebuild).
- Keep Wireless CarPlay (Bluetooth bridge and Wi-Fi sessions) ON.
- Keep Song, artist and album art on wireless CarPlay ON if desired.
- Automatically open wireless CarPlay remains optional, default ON.
- Save, apply and restart. Use only one navigation sender. WCP's Now Playing refresh is independent of its TBT setting.
- Follow [Standalone TBT's update and test guide](https://github.com/Nyx-Asteria/Standalone_TBT/blob/main/UPDATE.md).

## Verification and rollback
Confirm wireless pairing/connect, auto-open, music metadata after track changes, disconnect/reconnect and return to CarPlay. Apple Maps and Google Maps provide navigation positive controls through Standalone TBT. Waze is not yet verified. No changes to WCP's identification payload are proposed without runtime evidence. Initial connection latency work is deferred.

If regression occurs, disable the new standalone experiment first; if necessary remove the affected mod and restore the known-good signed package. Do not enable duplicate TBT senders.
