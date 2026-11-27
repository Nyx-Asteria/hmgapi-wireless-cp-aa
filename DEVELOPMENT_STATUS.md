# RC10 development status — audit A26

Scope: WCP 2.2.1-rc.6 and Standalone TBT 1.0.0-rc.3. HMG-API SDK RC10 pinned to daa083717f07565ba16dc8a4d26291b7d68d3bf4. Vehicle testing is separate from off-car readiness. CPUL is actively developed independently: do not edit it or assume a stable consumer revision. No publication until explicitly authorized.

## Audit baseline

66 historical commits reviewed (39 WCP, 27 STBT; STBT historical baseline provisional). Prior to A24, 18 corrective commits across both repositories. Latest inspected heads: WCP a66e22c, STBT 7b7df0f. Last confirmed green: WCP 2ff8b3a (45929828645), STBT 01caa45 (45929831498). A66e22c corrects unsupported shell utilities found in 8cc6106 CI; both latest CI runs were pending at A24 start.

## Findings ledger

| ID | Subject | State |
|---|---|---|
| A-001 | RC10 source floor | Host validated |
| A-002 | Java service classpath isolation | Source verified |
| A-003 | Redundant destination digest | Host validated; repeated polling retained intentionally |
| A-004 | Stale completion manifests | Host validated; source retention limits detection |
| A-005 | Mount removal during copy | Host validated with simulated mounts |
| A-006 | Directory metadata power-loss durability | Open; file fsync is insufficient |
| A-007 | Native collector retry backoff | Host validated |
| A-008 | Native collector shutdown escalation | Host validated; uninterruptible processes remain possible |
| A-009 | Orphan collector | Partial; readlink detector CI 45930330776 passed; device verification and abandoned-process termination pending |
| A-010 | Native retention accounting | Host validated |
| A-011 | Journal filesystem exceptions | Host validated against existing suite |
| A-012 | Journal retention accounting | Host validated |
| A-013 | Native marker retirement | Host validated; total filesystem failure remains possible |
| A-014 | Lifecycle task backlog | Host validated |
| A-015 | Stale packet producers | Host validated for sampled generations |
| A-016 | Connection callback race | Host validated for explicit generations; OEM callbacks lack session IDs |
| A-017 | Binder replacement | Host validated for queue isolation; old listener identity and in-flight transactions not fully addressed |
| A-018 | Bluetooth broadcast race | Final pre-send generation check added in A24; residual check/send race remains |
| A-019 | Aggregate RX diagnostics | Host validated |
| A-020 | Saved integrated TBT=true | Configuration prerequisite; explicitly disable WCP integrated TBT when using STBT; preserve owner settings |
| A-021 | Hidden STBT settings | Host validated |
| A-022 | Removed AA firewall rule | Policy documented and historical network regressions reviewed; AA behavior not vehicle verified |
| A-023 | CPUL identity consumer | Deferred until independent development stabilizes |
| A-024 | Release regression coverage | Workflow source corrected; actual signed release not executed |
| A-025 | RC3 changelog | Changelog written in 7b7df0f; CI 45930377058 passed |
| A-026 | SDK pin checker | Host validated |
| A-027 | Native recipe coexistence | Source validated for WCP integrated TBT off: separate iAP2 verify regions, identical AA recipes and no shared replace hooks. Vehicle validation pending |

## Ownership and constraints

WCP owns wireless connectivity, Wi-Fi audio, Now Playing and shared identity. STBT owns cluster guidance and junction/toll enlargement when installed together. Explicitly turn WCP integrated TBT off (saved values survive defaults), leave the wireless master on, preserve known-good hotspot configuration. No CPUL edits, no history rewrite, no release publication. Apple Maps and Google Maps are prior positive controls; Waze previously reports unsupported guidance and is not verified fixed. Do not fabricate navigation metadata. Native raw logs may contain private data.

## Next work

Check CI on exact latest heads; review remaining Bluetooth race without holding locks across Android calls; confirm native recipe coexistence and AA firewall documentation; assess directory sync compatibility and residual orphan writer limitation; finish changelog review and final host regression checklist. Record actual signed release status only after explicit authorization. Keep vehicle validation separate.
