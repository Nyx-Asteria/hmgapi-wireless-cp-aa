# RC10 final validation checklist

Development candidates WCP 2.2.1-rc.6 and STBT 1.0.0-rc.3. No release authorized. CPUL independently changing; defer final three-mod sign-off.

## Source compatibility (A26)
- WCP identity recipe owns [0x129c6,0x129e0) and [0x1aa04,0x1aa08). STBT LX2 PE lane guard owns [0x236a0,0x236c8) and [0x22cd0,0x22cd4). No overlap; this is not a claim that both recipes match the K5 firmware.
- Three AA recipe files share the same verify/patch regions; WCP must not request integrated guidance when STBT owns guidance. Ten shared hook points use change/observe, not duplicate replacement declarations.
- Pinned RC10 NativePatch.java tracks owned regions per mapped library; overlapping recipes are skipped, not composed. It does not ensure that dependent Java hooks are compatible if both senders are enabled.
- Existing saved integrated TBT=true survives new defaults. Explicitly switch it off, apply/rebuild and restart. Preserve user setting rather than force migration.
- WCP native writer detector with readlink passed CI 45930330776. It is best effort, not orphan termination; Android proc permissions, startup race and stale ready markers merit device testing.
- File fsync and mount guards cannot guarantee durable directory entries or eliminate mount check/write races. Preserve original logs under retention policy and verify exported SHA256 before using.

## Host release gates
- [ ] Verify latest exact WCP HEAD CI, including journal, SDK pin, package and head-unit command check.
- [ ] Verify latest exact STBT HEAD CI, including package identity and wrapper.
- [ ] Review all post-baseline corrective commits and confirm changelogs match.
- [ ] Confirm release jobs require policy regression success and correct author UUID; actual secret-backed signing not yet run.
- [ ] Resolve remaining platform risks or record explicit disposition.
- [ ] Obtain explicit publication authorization; no release on ordinary audit commits.

## Vehicle checks (not counted as host CI)
- [ ] Preserve known-good signed packages, working hotspot configuration and EWF prerequisite; record installed versions.
- [ ] Test wireless first connection, reconnection, USB takeover, unplug recovery and repeated ignition cycles.
- [ ] Measure connection latency, audio/Siri, metadata and session lifecycle diagnostics.
- [ ] With WCP integrated TBT off and STBT on, test Apple Maps and Google Maps as positive controls; Waze capability and maneuver delivery remain unverified.
- [ ] Test junction/lanes/toll display, navigation stop, stock navigation return; Android Auto remains unverified.
- [ ] Test USB insertion after session, removal during export, reinsert and verify manifests; inspect logs for private information before sharing.
- [ ] Test abrupt shutdown and collector restart without duplicate writers; do not assume power-loss durability.
- [ ] After CPUL stabilizes, verify shared identity API compatibility and normal CarPlay fallback separately.
