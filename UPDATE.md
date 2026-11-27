# WCP update guide — 2.2.1-rc.6 (development candidate)

Current K5 test baseline: US DL322 260507, HMG-API  >=1.1.0-rc.10 for this candidate. RC6 is not yet published.

## Update
- Keep the known-good ZIP and record installed versions. Download the fork's signed package from its [releases](https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/releases).
- Place the intact ZIP in HMGAPI/updates on USB, check USB updates, apply and restart if required. An update needs the same author key; for a different signer remove and apply removal before installing.
- Preserve the working hotspot configuration. On K5, the existing Wi-Fi enablement prerequisite may still be required; do not blindly remove it or install competing projection patches.
- Only reset pairing if needed to resolve connection problems.

## With Standalone TBT
- Explicitly set Integrated turn-by-turn OFF (rebuild); saved true values survive default changes.
- Keep Enable wireless CarPlay ON; this includes wireless audio and Now Playing.
- Keep only one cluster guidance sender. Shared identity remains independent of integrated TBT.
- Automatically open wireless CarPlay remains optional, default ON.
- Save, apply and restart. Use only one navigation sender. WCP's Now Playing refresh is independent of its TBT setting.
- Follow [Standalone TBT's update and test guide](https://github.com/Nyx-Asteria/Standalone_TBT/blob/main/UPDATE.md).

## Verification and rollback
Confirm wireless pairing/connect, auto-open, music metadata after track changes, disconnect/reconnect and return to CarPlay. Apple Maps and Google Maps provide navigation positive controls through Standalone TBT. Waze is not yet verified. No changes to WCP's identification payload are proposed without runtime evidence. Aggregate receive and transmit timing diagnostics are included; actual latency improvements require vehicle measurement.

If regression occurs, disable the new standalone experiment first; if necessary remove the affected mod and restore the known-good signed package. Do not enable duplicate TBT senders.

## Upcoming session logging (development source)

CarPlay session journals are written asynchronously in DiPO's private storage. Each session has a unique filename, wall-clock and monotonic timestamps, and a start/end marker. Completed files are exported automatically to `WCP-LOGS/sessions/` on a mounted USB drive; a USB drive is not required during recording. The latest 12 completed files are retained locally, each approximately 2 MiB maximum; older files and earlier events in oversized sessions may be discarded. A process restart recovers leftover active files as interrupted sessions. The journal captures this mod's DiPO diagnostics, not all system/native logs. Existing diagnostic mechanisms remain available. Java journal MAC and credential patterns are redacted on a best-effort basis. When native CarPlay logging is explicitly enabled, the separate native logcat files are exported raw (the head unit does not provide sed); these may contain device identifiers, network details, and private navigation information. Review and redact before sharing. USB export and full package build remain unverified on the vehicle.
