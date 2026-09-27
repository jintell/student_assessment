# TASK-PLAT2-DEFECT-005 PostgreSQL Version Missing from Baseline

Status: **OPEN - RAISED FOR NEXT BASELINE; LOCAL DECISION APPROVED**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Baseline Defect

The approved requirements, architecture, and delivery plan depend on version-
sensitive PostgreSQL behavior but name no supported major version. The
contracts include strict `current_setting`, forced row-level security,
transaction-local settings, concurrent index behavior, and advisory locks.
Repeatable integration and operational evidence require one explicit baseline.

## Resolution Adopted by This Feature

The Architecture Owner approved PostgreSQL 17 in
`docs/decisions/P0.5-postgresql-version-approval.md`. Local development, CI,
staging, Testcontainers, and rehearsals use the immutable image declared by
`postgresqlImage` in `gradle.properties`. `verifyPostgresqlBaseline` checks the
major version, digest, configured consumers, and required isolation behavior.

## Next-Baseline Action

Add PostgreSQL 17 as the supported persistence baseline, reference the approved
immutable-image policy, and define major-version changes as an Architecture
Owner decision requiring compatibility, migration, RLS, pooling, and advisory-
lock verification before adoption.
