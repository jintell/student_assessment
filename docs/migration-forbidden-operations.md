# Migration Forbidden Operations and DDL Allowlist

Status: normative closed policy

Feature: `FEAT-PLAT-005`

Source: architecture section 9.8, `ADR-019`, and the approved P2.2 design

The migration analyser parses PostgreSQL syntax and matches typed statement
shapes. Text similarity is not approval. Any statement that is unknown,
partially parsed, in the wrong phase, or outside this list fails CI stage 12.

## Forbidden Operations

| Operation | Failure it prevents |
|---|---|
| Rename or drop a column still read by the previous version | Prevents breaking the retained N-1 image and preserves code-only rollback. |
| Add a `NOT NULL` column without a constant default | Prevents existing rows and old-version writes from violating the new invariant. |
| Run a blocking `ALTER TABLE` on an exam-critical relation | Prevents a migration lock from stalling answer acceptance, attempt progress, or required audit writes. |
| Run `CREATE INDEX` without `CONCURRENTLY` | Prevents a standard index build from blocking writes to its table. |
| Touch a relation outside the module named by the migration header | Preserves schema ownership and prevents one feature from changing another feature's storage contract. |
| Put DML or a backfill in a Flyway script | Prevents unbounded release-time work; data movement belongs to the resumable worker harness. |
| Combine more than one operation in a single `ALTER TABLE` | Preserves statement-level classification, lock attribution, and actionable rejection evidence. |
| Use dynamic SQL, procedural bodies, client meta-commands, transaction control, `SET`, grants, ownership changes, schema changes, extensions, functions, triggers, or `CASCADE` | Prevents the static gate from approving opaque, privilege-changing, or unexpectedly broad behavior. |

The initial exam-critical registry contains `delivery.answer`,
`delivery.answer_operation`, `delivery.attempt`, and `audit.audit_event`.
Every owning feature must register any additional relation whose lock can
interrupt the exam path.

## Permitted Shapes

All relations and indexes must be explicitly schema-qualified and owned by the
module named in the script header.

| Phase | Permitted shape | Required predicates | Failure it prevents |
|---|---|---|---|
| `EXPAND` | `CREATE TABLE` | New module-owned table; each column is nullable or has a constant default; no rewrite expression | Keeps the addition usable by old code and avoids an unbounded table rewrite. |
| `EXPAND` | `ALTER TABLE ... ADD COLUMN` | Exactly one column; nullable or constant default; no inline unique, primary-key, foreign-key, or validated check | Keeps the addition compatible and gives each lock a single attributable operation. |
| `EXPAND` | `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` | Exactly one check or foreign-key constraint on a module-owned table | Defers the existing-row scan so the add step does not hold a long blocking lock. |
| `EXPAND` | `CREATE [UNIQUE] INDEX CONCURRENTLY` | Explicit module-owned index and table; header declares `transactional false` | Keeps writes available while the index is built. |
| `MIGRATE` | `ALTER TABLE ... VALIDATE CONSTRAINT` | Existing named constraint; one operation | Separates the row scan from constraint creation and makes its lock measurable. |
| `MIGRATE` | `ALTER TABLE ... ALTER COLUMN ... SET DEFAULT` | Constant default; one module-owned column | Avoids volatile expressions and unexpected rewrites while dual-write code is active. |
| `CONTRACT` | `ALTER TABLE ... DROP COLUMN` | One explicitly named obsolete column | Limits removal to an observed, separately released contraction. |
| `CONTRACT` | `ALTER TABLE ... DROP CONSTRAINT` | One explicitly named obsolete constraint | Prevents a broad or accidental constraint removal. |
| `CONTRACT` | `ALTER TABLE ... ALTER COLUMN ... DROP DEFAULT` | One explicitly named obsolete default | Removes only the compatibility default after old writers are retired. |
| `CONTRACT` | `DROP INDEX CONCURRENTLY` | Explicit module-owned index; header declares `transactional false` | Avoids blocking ordinary table access while removing an index. |
| `CONTRACT` | `DROP TABLE` | One explicitly named obsolete module-owned table; no `CASCADE` | Prevents implicit deletion of dependent objects. |

`COMMENT ON` is admitted only immediately after an allowed create/add shape and
only for the object that shape created. It is metadata attached to an already
approved operation, not an independent escape from the allowlist.

## Changing the Policy

Configuration cannot widen this list. Adding a statement shape requires:

1. An amendment to `ADR-019` explaining its compatibility and locking model.
2. Platform Ops and Engineering Lead review.
3. Positive and negative analyser cases.
4. A new signed closed-allowlist approval record.

Until all four exist, the new shape remains rejected.
