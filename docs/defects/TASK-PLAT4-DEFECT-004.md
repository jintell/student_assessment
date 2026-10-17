# TASK-PLAT4-DEFECT-004 Missing Scheduler-Singleton Dependency

Status: **CLOSED - SHARED LOCK ADOPTED AND CONTAINMENT VERIFIED (2026-10-14)**

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

## Implementation Closure

`RelaySingleton` now consumes `SweepLock`; the local SQL and key derivation
have been removed. Runtime P4.25's blocking stage-4 gate rejects another
acquisition mechanism or interval source, including a helper in `platform.infra`.
Existing database-integrity locks are preserved only under an immutable
four-file migration fingerprint baseline.

The real-PostgreSQL relay contention/lock-loss tests and four-runner repeated
contention tests for both adapters passed. Runtime P7.19 and outbox P7.17 are
complete. The upstream plan dependency amendment remains recorded above;
this closure describes the implemented adoption, not an edit to that plan.
See [closure evidence](../evidence/FEAT-PLAT-006/P4.25-containment-closure.md).

## P10.10 Handoff — 2026-10-16

Republished to the Architecture Owner and Plan Owner. The implementation seam is closed, but acknowledgement of the missing dependency correction remains pending; the feature-card dependency row is not silently changed.

See the [nine-record acknowledgement ledger](../evidence/FEAT-PLAT-004/P10.10-baseline-defect-handoff.md).
