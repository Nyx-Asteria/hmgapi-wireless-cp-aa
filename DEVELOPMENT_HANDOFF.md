# WCP RC7 — Development handoff
Updated 2026-11-27. Consolidated checkpoint on rc7-final.

## Current milestone
RC7: bound capability serialization and enforce callback ownership
Bounded deduplicated StartSession capability sizing, stock fallback on gate failure, coherent JNI snapshot, reject ambiguous callback ownership.
Original chronological snapshot: efef05fc666dd05f2fb074ae3dfd794196b6e0fc (source tree preserved except this handoff). Previous consolidated parent: 08af03b9c70f62dce589ba22779a3322450f7357. Original green development branch k5-log2-cleanup at 3c3d2489b5bd4b8f4b8d37a5a451341701f78bce remains untouched; all historical handoffs remain there. Baseline e07dbe1c185067571e4dbee51c69cb0865753c8f, tree-identical to RC6 4885b9fd7c05c2755aad6b32d8fdc15b5bddb0c6. No intermediate fresh CI claimed. Next: continue original chronological checkpoints, not feature development. Final matched candidate needs fresh CI. No release authorized by consolidation alone.

## Ownership
WCP owns wireless and native iAP2 identification; CPUL owns display/INFO/SETUP/type-130 dispatch; STBT sole navigation publisher when installed (WCP integrated TBT off). HMG-API RC10+, preserve IDs, keys and stock/EV fallback. No speculative FileTransfer activation or Waze claims. Update this handoff in every commit.
