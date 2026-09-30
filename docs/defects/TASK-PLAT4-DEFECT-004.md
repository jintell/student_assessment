# TASK-PLAT4-DEFECT-004 Missing Scheduler-Singleton Dependency

Status: **OPEN - RAISED TO PLAN OWNER; ADOPTION SEAM RECORDED**

Owner: Plan Owner

Raised by: `FEAT-PLAT-004`

## Plan Defect

The `FEAT-PLAT-004` dependency row names `FEAT-PLAT-002` and
`FEAT-PLAT-003`, while architecture section 11.2 requires a worker-hosted,
advisory-locked relay. `FEAT-PLAT-006` owns the background runtime role,
advisory-lock registry, and exactly-one-sweep discipline, and the two features
are scheduled on parallel Phase 0 tracks.

## Resolution Adopted by This Feature

Task `P4.6` may ship a local transaction-scoped advisory-lock acquisition
keyed by the stable outbox-relay sweep name. It is a temporary implementation
of the singleton boundary, not a second registry. Task `P7.17` must replace it
with the `FEAT-PLAT-006` registry after that dependency is delivered, remove
the local acquisition, and rerun both exactly-one-sweep suites.

## Plan Amendment Requested

Add `FEAT-PLAT-006` to the `FEAT-PLAT-004` dependency row and preserve the
parallel-track adoption sequence recorded in
`docs/evidence/FEAT-PLAT-004/P0.6-feat-plat-006-adoption-seam.md`.
