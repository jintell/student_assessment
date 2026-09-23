# Expand/Contract Migration Discipline

Status: normative for every module-owned schema change

Feature: `FEAT-PLAT-005`

Architecture baseline: `arch-v1.4`, section 9.8 and `ADR-019`

Schema changes must preserve compatibility with the currently deployed code
and with the retained rollback image. A logical change therefore crosses three
releases. A release declares exactly one migration phase; phases are never
combined to shorten the sequence.

## Required Sequence

| Release | Phase | Permitted intent | Compatibility obligation |
|---|---|---|---|
| N | `EXPAND` | Add a nullable column, a new table, or an index created `CONCURRENTLY` | Version N-1 continues to read and write successfully. |
| N | Backfill | Populate the new representation in bounded, throttled, resumable batches | The worker may stop and resume without duplicate effects or table-level locks. |
| N+1 | `MIGRATE` | Deploy code that writes both representations and reads the new representation | The old representation remains available to the retained rollback image. |
| N+2 | `CONTRACT` | Remove the obsolete column, constraint, index, or table after N+1 is fully rolled out and observed | The release is a separate, forward-only schema change and cannot be rolled back. |

Backfill is operational work performed by the `worker` role. It is not a
migration phase, does not run in the migration Job, and never blocks release
completion.

## Phase Rules

### EXPAND

`EXPAND` may introduce only additive structures accepted by the closed DDL
allowlist. Additions must not require the old application to supply new data or
stop existing reads and writes. Indexes are created `CONCURRENTLY` in a
non-transactional migration.

An `EXPAND` release must not rename or remove a representation still used by
N-1, combine expansion with contraction, or perform an unbounded data rewrite.

### MIGRATE

`MIGRATE` moves application behavior. New code writes both old and new
representations and reads the new representation. Constraint validation and a
constant default may be applied only through shapes admitted for this phase.

The old representation remains intact until the N+1 deployment is complete,
observed, and no longer needed by the rollback target.

### CONTRACT

`CONTRACT` removes only representations proven obsolete after N+1 has fully
rolled out. It is a dedicated N+2 release, cannot share a release manifest with
`EXPAND` or `MIGRATE`, and is never schema-rolled back. A failed contract
release is halted and corrected forward.

## Release Gates

Before deployment, CI stage 12 must:

- accept every statement through the parsed closed allowlist;
- reject forbidden and cross-schema operations;
- keep measured relation lock holds within the approved thresholds;
- run the retained N-1 image against the migrated N schema for every touched
  relation; and
- retain the release manifest and migration lock-duration report.

The migration Job completes before any new pod serves traffic. A failed Job
aborts the release without replacing a pod. Code rollback is permitted only
when the retained N-1 compatibility evidence is green; schema rollback is not
part of the recovery path.

## Planning Example

To replace `delivery.answer.response` with `response_json`:

1. Release N adds nullable `response_json` and starts the resumable backfill.
2. Release N+1 writes both columns and reads `response_json`.
3. Release N+2, after observation and rollback retirement, drops `response` in
   a separately classified `CONTRACT` release.

See `docs/migration-authoring.md` for script mechanics and
`docs/migration-release-planning.md` for delivery planning.
