# Exclusive USB CarPlay mode: design and investigation

Target: DL322 USA 260507. The owner's USB CarPlay dongle has its own Wi-Fi and Bluetooth, but the head unit sees USB projection. Do not confuse dongle wireless with the OEM projection AP.

## Current source findings

- `wireless-carplay-aa/mod.json` unconditionally requests both wireless projection feature flags and `wifi.hotspot`. Turning off bridge/audio does not withdraw these engine requests. The projection part is unconditional.
- `Wireless.iap2` installs shared identity before the bridge setting check. Identity API publication in zygoteInit is independent of the bridge.
- `Bridge.onSppUp` invokes ensureHotspot before Bluetooth attachment.
- `StartSession.build` is K5-specific and derives the extended start payload from receiver data, OEM AP credentials and wlan0 addresses. This extension is distinct from vehicle/theme identity serialization and is not suitable for a USB-only profile.
- The pinned HMG-API mod reference documents shared feature/hotspot ownership, restoration when no mod requests them, and a distinct projection AP startup facility. Do not assume conditional syntax for feature_flags or wifi without engine support.
- The native identity gate and API package must remain available in both profiles; do not duplicate the service provider or change its installed ID.

## Proposed implementation

Prefer a single installed WCP with a restart/rebuild transport profile, defaulting to today's wireless behavior. If the engine cannot conditionally withdraw global manifest requests, investigate an independent optional wireless provisioning companion or an upstream engine enhancement. Preserve existing AA behavior in the default profile. Do not publish a second package with the same identity provider simultaneously.

USB-only profile: no wireless feature requests, no OEM hotspot request, no wireless bridge or direct AP startup, no wireless-only audio patches or firewall rules, no extended wireless startup payload; retain shared identity, ordinary USB projection, independent STBT and passive session logging. Other owners of hotspot features must be identified; do not force-disable their services.

## EWF dependency investigation

The previous vehicle test required Nemesis Enable Wireless Features (wireless.projection). Exact installed package payload and rollback scripts have not been reverified. Do not remove APKs, properties or XML files based on guesses.

Before transition, retain the working WCP/EWF configuration and obtain the installed EWF manifest and its original backup inventory. Compare stock/current package paths and hashes, relevant feature XML and featurexml.cfg entries, Wi-Fi module feature, driver/firmware presence, HMG-API apply results and hotspot state. Do not collect credentials.

Use the package's own verified uninstall/restore procedure, not manual system-file deletion. Rebuild and reboot; verify no other mod requests projection AP. Confirm USB dongle connects, OEM AP is absent, identity API remains available, and CPUL receives the expected identity. A dongle may not forward extended CarPlay capabilities. Compare parked playback and logs only after USB projection is stable.

Restore the known working wireless profile and EWF if the transition fails. No native capability, AirPlay timeout, or video restrictions changes are justified by this investigation.

This document is a design, not an implemented or vehicle-verified transport mode. Do not make it a blocker for RC5 unless the necessary engine and rollback evidence becomes available.
