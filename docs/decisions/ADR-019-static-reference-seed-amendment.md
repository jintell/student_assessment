# ADR-019 Amendment: Bounded Static Reference Seed

Status: APPROVED

Date: 2026-10-11

Scope: `FEAT-PLAT-006` P3.2 only

## Decision

ADR-019 admits one additional `EXPAND` shape: the six-row literal seed of
`platform.sweep_registry` in `V10__seed_sweep_registry.sql`. The analyser
accepts it only when the table, ordered columns, ordered rows, lock keys,
owners, features, intervals, disabled flags, actors, and
`ON CONFLICT (sweep_name) DO NOTHING` action exactly match the approved
registry design. Any variation remains ordinary data modification and fails.

## Compatibility And Locking

V9 creates an empty table in the same release, so the insert has no N-1
reader or writer contract. Six literal rows are bounded release-time work.
The unique-key conflict path is rerunnable and deliberately does not update
an existing row, preserving later feature-owned enablement and configuration.
The statement takes only the normal row and index locks needed for six inserts
into the new control table; it does not lock an exam-critical relation.

The production seed leaves all six business sweeps disabled. The enabled
reference sweep is test-only and is introduced by the P4.26 fixture, never by
a deployed migration.

## Guardrails

- No general Flyway DML permission is created.
- No expression, query, `RETURNING`, `DO UPDATE`, or additional row is allowed.
- Later features enable their reserved sweep by changing one existing row;
  they do not allocate another name or lock key.
- Positive and mutation-negative analyser tests enforce the closed shape.
- Platform Ops and Engineering Lead approval is recorded in
  `ci/dor/P3.2-static-reference-seed-allowlist-approval.json`.
