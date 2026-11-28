# WCP RC7 — Development handoff
Updated 2026-11-27. Consolidated checkpoint on rc7-final.

## Current milestone
RC7: map theme acquisition and legacy receiver command paths
Research acquisition versus session transfer, receiver libraries, platform commands and generic delegate fallback.
Original chronological snapshot: 09e743698fba8f2f4d6a0518b1d1fd125eb70dd5 (source tree preserved except this handoff). Previous consolidated parent: 8e657ed5936afca0198d2c29aa1454c11ee7cdb5. Original green development branch k5-log2-cleanup at 3c3d2489b5bd4b8f4b8d37a5a451341701f78bce remains untouched; all historical handoffs remain there. Baseline e07dbe1c185067571e4dbee51c69cb0865753c8f, tree-identical to RC6 4885b9fd7c05c2755aad6b32d8fdc15b5bddb0c6. No intermediate fresh CI claimed. Next: continue original chronological checkpoints, not feature development. Final matched candidate needs fresh CI. No release authorized by consolidation alone.

## Ownership
WCP owns wireless and native iAP2 identification; CPUL owns display/INFO/SETUP/type-130 dispatch; STBT sole navigation publisher when installed (WCP integrated TBT off). HMG-API RC10+, preserve IDs, keys and stock/EV fallback. No speculative FileTransfer activation or Waze claims. Update this handoff in every commit.
