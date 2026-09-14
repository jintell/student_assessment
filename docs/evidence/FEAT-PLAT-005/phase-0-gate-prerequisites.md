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

## P0.3 - Lock-Duration Threshold Approval

Status: APPROVED

Tracking: `TASK-PLAT5-DEFECT-001`

Approved thresholds:

- Exam-critical warning: 100 ms
- Exam-critical failure: 250 ms
- Non-critical failure: 2 s

Approvers:

- Platform Ops - APPROVED
- Engineering Lead - APPROVED

Evidence:

- `ci/dor/P0.3-lock-duration-thresholds.json`
- `ci/dor/P0.3-lock-duration-thresholds.platform-ops.sig`
- `ci/dor/P0.3-lock-duration-thresholds.engineering-lead.sig`

Enforcement: `verifyLockThresholdApproval`

Any threshold change requires renewed approval.

P0.4 and Phase 3 may proceed only while this verification passes.

## P0.4 - Closed Forbidden-Operation List Approval

Status: APPROVED

Architecture authority: ADR-019

Approved semantics:

- ADR-019 forbidden-operation categories remain authoritative.
- Permitted DDL shapes form a closed allowlist.
- Every unrecognized DDL shape SHALL fail.
- Adding a permitted DDL shape requires reviewed change.
- Configuration-only changes SHALL NOT expand the allowlist.

Approvers:

- Platform Ops - APPROVED
- Engineering Lead - APPROVED

Evidence:

- `ci/dor/P0.4-closed-ddl-allowlist.json`
- `ci/dor/P0.4-closed-ddl-allowlist.platform-ops.sig`
- `ci/dor/P0.4-closed-ddl-allowlist.engineering-lead.sig`

CI gate: `verifyClosedDdlAllowlistApproval`

P0.4 is resolved only while this verification passes.

## P0.5 - Dataset-Provenance Approval

Status: APPROVED

Dataset provenance: deterministic synthetic data only.

Scheduled generator tasks:

- `P1.7`
- `P2.8`
- `P3.4`
- `P3.5`
- `P7.10`

Prohibited sources:

- production personal data
- production extracts
- production dumps
- production backups
- production snapshots
- production-derived seed datasets

DPO: ACKNOWLEDGED

Evidence:

- `ci/dor/P0.5-dataset-provenance.json`
- `ci/dor/P0.5-dataset-provenance.dpo.sig`
- `ci/dor/P0.5-dataset-provenance.engineering.sig`

CI gate: `verifyDatasetProvenanceApproval`

Any change permitting production-derived personal data requires renewed
privacy and architecture review.

P0.6 may proceed only while this verification passes.

## P0.6 - Universal Definition of Ready

Status: READY

All universal and feature-specific readiness criteria are met. The assessment
and its signed evidence chain are recorded in
`docs/evidence/FEAT-PLAT-005/P0.6-definition-of-ready.md`.
