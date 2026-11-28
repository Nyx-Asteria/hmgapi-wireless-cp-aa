# WCP RC7 — Development handoff
Updated 2026-11-27. Consolidated checkpoint on rc7-final.

## Current milestone
RC7: isolate UpdateData UUID provider and socket negotiation unknowns
Record remote-control creation options and CoreMedia dependency; no speculative transport.
Original chronological snapshot: 1d910248bd0a72457ae2bf1eaaa02bd9e2fbd348 (source tree preserved except this handoff). Previous consolidated parent: 46129616fef1223853c56769d708d8d30ae46f6e. Original green development branch k5-log2-cleanup at 3c3d2489b5bd4b8f4b8d37a5a451341701f78bce remains untouched; all historical handoffs remain there. Baseline e07dbe1c185067571e4dbee51c69cb0865753c8f, tree-identical to RC6 4885b9fd7c05c2755aad6b32d8fdc15b5bddb0c6. No intermediate fresh CI claimed. Next: final RC7 metadata and reusable full regression release gate. No release authorized by consolidation alone.

## Ownership
WCP owns wireless and native iAP2 identification; CPUL owns display/INFO/SETUP/type-130 dispatch; STBT sole navigation publisher when installed (WCP integrated TBT off). HMG-API RC10+, preserve IDs, keys and stock/EV fallback. No speculative FileTransfer activation or Waze claims. Update this handoff in every commit.
