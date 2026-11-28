# WCP RC7 — Development handoff
Updated 2026-11-27. Consolidated checkpoint on rc7-final.

## Current milestone
RC7: persist and validate bounded quarantine pairs
Abort malformed OPACK, preserve archive metadata, validate reopened pairs and cap admission. Stage 1 CPUL diagnostic checkpoint; no active transport.
Original chronological snapshot: 65abb3d93fc75bf724be60c7c938b4ee8a668072 (source tree preserved except this handoff). Previous consolidated parent: a16e4adf1320304f85b4fd6fe0d24ed89bd25bba. Original green development branch k5-log2-cleanup at 3c3d2489b5bd4b8f4b8d37a5a451341701f78bce remains untouched; all historical handoffs remain there. Baseline e07dbe1c185067571e4dbee51c69cb0865753c8f, tree-identical to RC6 4885b9fd7c05c2755aad6b32d8fdc15b5bddb0c6. No intermediate fresh CI claimed. Next: final RC7 metadata and reusable full regression release gate. No release authorized by consolidation alone.

## Ownership
WCP owns wireless and native iAP2 identification; CPUL owns display/INFO/SETUP/type-130 dispatch; STBT sole navigation publisher when installed (WCP integrated TBT off). HMG-API RC10+, preserve IDs, keys and stock/EV fallback. No speculative FileTransfer activation or Waze claims. Update this handoff in every commit.
