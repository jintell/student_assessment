# TASK-PLAT5-DEFECT-004 Backfill Missing from Release Sequence

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-005`

## Baseline defect

Architecture section 9.8 places a batched, throttled, resumable backfill in
release N, but section 17.6 has no backfill step. Its migration Job must finish
before pods serve traffic, which is incompatible with a deliberately
throttled, potentially long-running data backfill.

## Resolution adopted by this feature

The migration Job runs DDL only. Backfill operations run separately in the
`worker` role through `ResumableBackfillHarness`, use persisted key-range
checkpoints, and do not block release completion or hold table-level locks.
The `MIGRATE` application tolerates partial completion until the worker reports
that no eligible rows remain.

## Next-baseline action

Add the asynchronous worker backfill between `EXPAND` and `MIGRATE` in the
release sequence. State its pause/resume and completion evidence and explicitly
exclude it from the migration Job.
