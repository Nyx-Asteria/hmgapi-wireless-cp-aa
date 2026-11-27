# Wireless CarPlay + Android Auto for HMG-API

This fork's RC6 development focuses on wireless CarPlay on compatible Gen5W head units through the car's own Wi-Fi hotspot. Existing Android Auto code is retained but is not newly validated. The mod also brings experimental phone navigation (turn-by-turn) to the instrument
cluster and head-up display (HUD) on compatible hardware and firmware.

[Download the mod](https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/releases) ·
[Confirmed cars](#confirmed-cars) ·
[Install and connect](#install-and-connect) ·
[Get help](#troubleshooting)

## Confirmed cars

These are the model, region and firmware combinations with documented on-car
results. Check the six-digit firmware suffix under **Settings → General →
Software info**. The mod requires a Gen5W head unit with Wi-Fi hardware and an
enabled HMG-API hook map. [HMG-API supported cars and hook maps](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)

| Car | Region | Firmware |
| --- | --- | --- |
| Kia EV6 (`CVEV`) | USA | `260507` |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`) | USA | `260507` |
| Hyundai Ioniq 6 (`CEEV`) | USA | `260507`, `260903` |
| Hyundai Ioniq 6 (`CEEV`) | Canada | `260206`, `260903` |
| Hyundai Elantra N (`CN7NPE`) | USA | `260903` |
| Kia K5 | USA | `260507` |

For the full list of model codes and firmware the installer accepts, see
[`system` in the mod manifest](wireless-carplay-aa/mod.json#L21).

## Install and connect

1. Install or update HMG-API. The unpublished fork candidate **2.2.1-rc.6** requires **HMG-API
   1.1.0-rc.10 or newer**. For released packages, consult their release notes. [HMG-API installation guide](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)
2. Download `wireless-carplay-aa-<version>.zip` and copy it, still zipped, into
   `HMGAPI/updates/` on a USB stick. [Mod releases](https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/releases)
3. Plug in the stick. In HMG-API, select **Check USB updates**, then **Wireless
   CarPlay + Android Auto**. On the main screen, tap **Apply changes**. Restart the
   head unit if prompted.
4. Remove the installation stick. If the phone was already paired, delete it
   from **Device Connections** on the head unit. On the phone, forget the car in
   Bluetooth and remove it from CarPlay or Android Auto settings.
5. With Bluetooth and Wi-Fi on, open **Phone Projection → Device Connections**
   on the head unit and pair the phone.

**Alternative NEMESIS installation path**
Users can extract `wireless-carplay-aa-<version>-nemesis.zip` to the
root of a USB stick and confirm the install in NEMESIS Manager. HMG-API must
already be installed. The guide also covers adb, updates and removal.
[Installing and removing mods](https://github.com/Open-HMG/HMG-API/wiki/Installing-and-removing-mods)

Updates require the same author key as the installed copy. If you installed a
test build signed with a different key, remove it and apply the removal before
installing the release. To uninstall an app-installed copy, choose **Remove**
in HMG-API and tap **Apply changes** to restore the files the mod changed.

## Features and settings

Open the mod in HMG-API to change its settings. Tap **Save settings** before
**Apply changes**, then restart the head unit if prompted.

- **Enable wireless CarPlay** (default on, rebuild): Bluetooth handover, Wi-Fi audio, Now Playing, Device Connections and hotspot-scoped CarPlay firewall rules. Disabling this does not remove global projection feature flags.
- **Automatically open CarPlay** (default on, restart): open CarPlay once when a wireless session connects.
- **Integrated turn-by-turn** (default off, rebuild): built-in cluster guidance, stock turn formatting and experimental intersection detail. Keep disabled with Standalone TBT.
- **Extended session diagnostics** (default off, restart): optional detailed native logs. Basic Java journals are automatic; finalized recordings are exported to WCP-LOGS on mounted USB. Native logs can contain private device information.

Shared CarPlay identity is always enabled. Legacy individual firewall and audio controls are no longer exposed.

## Troubleshooting

- **Your phone finds the car but won't connect.** Verify that Enable wireless CarPlay is on and the head unit has working Wi-Fi provisioning. Tap **Save settings**, then **Apply changes**, and retry.
  [CarPlay setup notes](docs/cn7npe-usa-260903.md) ·
  [Android Auto setup notes](docs/ioniq6-can-260206.md)
- **HMG-API says the hook map is missing.** Send a Car Report to
  [HMG-API](https://github.com/Open-HMG/HMG-API/issues/new/choose).
- **Need more help?** Use **Add my car** if the car or firmware is missing from
  the mod manifest. For a bug, include your car, firmware, phone and installed
  versions. Attach a Car Report if you can.
  [Open a mod issue](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/issues/new/choose) ·
  [Export a Car Report](https://github.com/Open-HMG/HMG-API/wiki/Car-Report)

## Contributing

Build and test the mod, add hook points, or integrate another mod with the
CarPlay identification API. [Contributor guide](CONTRIBUTING.md)

## License

[AGPL-3.0](LICENSE)

## K5 test fork — 2.2.1-rc.1

Restores direct OEM hotspot startup while retaining upstream navigation fixes and removal of projection arbitration. Optional CPUL theme session-start extension is prepared before Bluetooth iAP2 component 4 connects. Requires verified K5 firmware and receiver metadata; no competing iAP2 patch. Experimental, native dispatch and connection behavior not hardware-verified. Do not replace a working package without a rollback copy.

## Current K5 fork — 2.2.1-rc.4

The US K5 wireless connection, aggressive reconnection and automatic opening have been reported working on firmware 260507. Initial connection latency remains an open issue. For use with Standalone TBT, disable WCP's **Integrated turn-by-turn** (rebuild), retain **Enable wireless CarPlay**, apply changes and restart. See [update guide](UPDATE.md). No new transport or identification patches were made during the Standalone TBT Waze investigation.
