# Release procedure — K5 fork

Before every RC, consolidate development history into meaningful commits on an isolated branch as the final development step. Preserve upstream ancestry, existing release branches and tags, and significant architectural and release snapshots. Record original commit IDs and verify exact Git tree equality before and after consolidation. Run fresh CI on the consolidated candidate; any later correction requires revalidation and final consolidation review.

Coordinate WCP/STBT/CPUL immutable pins and full changelogs. Verify permanent signing UUID d515981b-afc8-8230-ae0f-62d32de366bc, signatures and checksums. Publish only after explicit owner approval; releases must be non-draft. Never force-push main or rewrite published tags. Development CI uses disposable keys, never publishes.
