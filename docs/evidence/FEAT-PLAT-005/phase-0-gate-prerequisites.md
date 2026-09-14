# FEAT-PLAT-005 Phase 0 Gate-Prerequisite Record

Date assessed: 2026-09-14

## P0.1 - Architecture Authorization

The primary ratification path from baseline task `P0.5` is in force:
`ci/architecture-ratification.json` is `RATIFIED`, no
`temporaryArchitectureGate` is present, and `ci/stage-4a` passes. Production
implementation is authorized, subject to feature-specific dependencies and
gates; `implementationAllowed: false` does not apply, so this feature's Phase
1 and later tasks may proceed when their own prerequisites are satisfied.

## P0.2 - FEAT-PLAT-002 Dependency

Persistence tasks `P3.4`, `P3.5`, and `P3.12` are complete. Their deliverables
are present as follows:

| Dependency | Repository evidence |
|---|---|
| `app_migrator` role | `src/main/resources/db/provisioning/V1__create_migration_role.sql` provisions the dedicated DDL-capable login role |
| Fifteen schemas | `src/main/resources/db/grants/grant-matrix.json` declares twelve module schemas plus `audit`, `outbox`, and `platform`, all owned by `app_migrator` |
| Migration entrypoint | `MigrationApplication` recognizes `--migrate-only`, requires the `app_migrator` identity, and runs Flyway without starting a web server |
| Per-module Flyway locations | `MigrationSchema` enumerates all fifteen schemas and maps each to its own `classpath:db/migration/<schema>` location and history table |

The migration pipeline may build on these capabilities without reimplementing
the persistence foundation.

## P0.3 - Blocked: Lock-Duration Threshold Approval

Status: BLOCKED

The task list proposes a `100 ms` warning and `250 ms` failure threshold for
locks touching exam-critical relations, plus a `2 s` failure threshold for
other relations. These values are a `PLAN-RECOMMENDATION`; they are not
approved by the ratified architecture baseline. No signed record approving all
three values by both Platform Ops and the Engineering Lead exists in the
repository.

Resolution requires a signed approval record that names the three threshold
values, both distinct approvers and their roles, and the approval time. Until
that evidence exists, `P0.3` remains open and strict-sequential execution must
not advance to `P0.4` or any Phase 3 implementation task.
