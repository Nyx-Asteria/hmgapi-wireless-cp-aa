# Development handoff — WCP RC7 / STBT RC4
Updated 2026-11-27. Active branch in both repositories: k5-log2-cleanup.
Update this file in every development commit, together with the code; preserve the full roadmap and distinguish test results from estimates. Do not publish until explicit approval. Vehicle tests are post-RC and excluded from progress.

## Recovered checkpoint
WCP parent fcf00a245edb0168ff74780234b4d142edbe83f5 (CI passed: https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/actions/runs/46055754533).
STBT checkpoint 6233b8f3582966b15c35926630a370cff2442082 (snapshot publication/interrupted USB recovery tests; CI not checked here).
Logging complete off-car per prior tracker, preserve persistent session-end USB exports, categorized diagnostics, SHA-256 fallback, recovery, bounded retention.
Shared identity 55% carried forward from last chat: vehicle/K5 profile 100% implementation (verified existing opt-in registration and native gasoline/EV fixtures; cross-project audit pending), shared API/CPUL readiness 85%, StartSession 50%, theme identity 50%, FileTransfer v2 0%, diagnostics/validation 60%. Estimates, not test coverage. Integration/audit, final consolidation and signed publication 0%.
API v3 native construction evidence is present; advertisement means builder evidence, not phone acceptance. Reconfiguration invalidates evidence.

## Current WCP change (previous cycle c134bf2)
Separate Bluetooth pre-connect session preparation from optional stock vehicle group activation. Remove unconditional success log; refreshStart already reports actual outcome without logging payload. CI for this change pending; no vehicle validation. Do not inflate progress before review.
Next: finish native offer/answer lifecycle and prerequisite handling; finish readiness contract and theme tuple tests; independent opt-in firmware-gated FileTransfer v2 experiments.

## Previous cycle f7dca93 — native replacement invalidation
Malformed native StartSession replacement now clears the existing payload under the identity mutex before returning -12. ARM production-source harness checks malformed header, null payload and explicit clear, including buffer erasure. CI pending; prior cycle CI was in progress when inspected. StartSession 45% implementation estimate, shared identity stays 55% pending validation. Remaining limitation at that checkpoint: JNI rejected oversize arrays before start_configure; in-flight sends not generation-bound. No vehicle testing or release.

## Previous cycle 0e7e0ac — JNI invalidation and K5 checkpoint reconciliation
Existing Wireless.java registers an explicit opt-in gasoline Kia K5 profile (default false, no connectors); API and native serializer validate combustion vs EV, with ARM gas and gas/theme fixtures in previously green fcf00a2. Mark K5 implementation complete, retain full integration/regression gate. JNI null, oversized and failed array reads now enter the native rejection/invalidation path, preserving pending JNI exceptions. Added native harness checks for null JNI input and oversize native rejection. CI pending, previous two runs still in progress at inspection. Shared identity overall held at 55% conservatively; StartSession 45% implementation estimate. Next: generation-aware native offer/answer lifecycle, without holding identity mutex across unverified OEM send calls. No vehicle testing or release.

## Current cycle — response generation recheck
Parent 0e7e0ac. Native payload invalidation increments a mutex-protected generation; offer handler snapshots it and rechecks after OEM packet creation, before allocating/copying payload. Obsolete responses at that checkpoint destroy the empty packet and record -4; added production ARM harness fixture withdrawing payload during packet creation. No mutex held across OEM allocation/send. Important limitation: a concurrent withdrawal after this recheck (during buffer allocation or dispatch) is not yet excluded; do not claim complete cancellation. Need verify OEM buffer ownership/destruction and safe dispatch semantics before tightening final boundary. CI c134bf2 passed https://github.com/Nyx-Asteria/hmgapi-wireless-cp-aa/actions/runs/46056876349; f7dca93 and 0e7e0ac were running at inspection. New CI pending. StartSession implementation estimate 50%, overall 55% held. Next: review buffer ownership and offer diagnostics, then theme compatibility and opt-in FileTransfer v2. No vehicle tests or releases.

## Full seven-stage roadmap
1. Repository cleanup: preserve release branches/tags, review history, meaningful architectural commits, obsolete infrastructure cleanup, exact baseline tree equivalence and CI; verify historical cleanup evidence before final consolidation.
2. Logging: CPUL architecture, provider-independent SHA-256, USB mount validation/retry, historical recording recovery, categorized diagnostics/session summaries.
3. Functional corrections: preserve working wireless projection and Apple/Google Maps guidance, investigate transport latency, improve Waze evidence collection. Do not promise Waze support.
4. Shared identity: groups 20/21 independent of EV registration; stock identity default, explicit gasoline K5 profile without fabricated EV connectors; native combustion serializer; preserve EV support and legacy full theme tuple; early StartSession preparation with stock fallback; native readiness; independent FileTransfer v2 experiments.
5. Coordinated integration: WCP/CPUL readiness contract, native recipe compatibility, display/identity ownership, single navigation publisher, cross-project negotiation diagnostics.
6. Regression/audit: production implementation tests, Android API 19, upgrade/recovery, cross-project source audit.
7. Coordinated release: align WCP 2.2.1-rc.7 and STBT 1.0.0-rc.4, permanent package IDs/signing keys, full changelogs, verified combined bundle, explicit publication approval, non-draft prereleases.

## Ownership and safeguards
WCP owns wireless projection, native iAP2 identification, vehicle information/status, theme asset identity, StartSession and FileTransfer negotiation. CPUL owns virtual cluster/ClusterSink/display and contributes via API; STBT owns selected navigation publishing (disable WCP integrated TBT when STBT selected). Readiness registered/prepared/armed/advertised/failed must reflect native evidence. Group activation failure cannot prevent bridge initialization. HMG-API rc10+, Nemesis 1.17 compatibility, K5 US 260507; retain stock and EV behavior. No speculative AirPlay or Waze patches. Do not disclose private captures, firmware, signing secrets or session credentials.

## Research inventory
User holds K5 firmware, Iap2ServerApp APK/ODEX, RC9/RC10 logs, CPUL report, original iOS 27 metadata and two-part incremental IPSW archive with checksums. Reuse existing dumps; collect only missing files. Archive integrity not verified in this cycle.
