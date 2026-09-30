# FEAT-PLAT-004 Phase 0 Gate-Prerequisite Record

Date assessed: 2026-09-29

## P0.1 - Architecture Authorization

The primary ratification path from `FEAT-PLAT-001` task `P0.5` is in force:
`ci/architecture-ratification.json` is `RATIFIED`, no
`temporaryArchitectureGate` is present, and `ci/stage-4a` passes. Production
implementation is authorized subject to the remaining feature-specific
dependencies and gates. The `implementationAllowed: false` restriction does
not apply, so this record unblocks `FEAT-PLAT-004` tasks beyond Phase 2 once
their own dependencies are satisfied.

Verification on 2026-09-29:

```text
./ci/stage-4a
STAGE 4a: PASS
Step 12: architecture baseline arch-v1.4 is ratified and verified.
```

## P0.2 - FEAT-PLAT-002 Dependency

`FEAT-PLAT-002` tasks `P3.7` and `P3.9` are complete. Migration
`db/migration/outbox/V1__create_schema.sql` creates `outbox` with
`AUTHORIZATION app_migrator`; `V2__grant_module_default_privileges.sql`
grants `USAGE` on the schema and configures
`ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA outbox` with
`INSERT` on future tables for all twelve module roles.

The `P7.15` integration proof creates a later shared-infrastructure table as
`app_migrator` without an explicit grant and verifies inherited `INSERT` plus
denied `UPDATE` and `DELETE`. The live grant-diff gate independently verifies
the declared default privileges for both `audit` and `outbox` against real
PostgreSQL.

Verification on 2026-09-29:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix' \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL
```

Status: SATISFIED. `FEAT-PLAT-004` may create `outbox.outbox_event` as
`app_migrator` and rely on the existing future-table `INSERT` bootstrap.

## P0.3 - FEAT-PLAT-003 Dependency

`FEAT-PLAT-003` tasks `P4.1` through `P4.5` are complete. The repository
contains the framework-free `shared.kernel.outbox.OutboxWriter` port and its
`OutboxMessage`/`IntegrationEvent` values, `shared.kernel.identity.TenantId`,
`shared.kernel.context.ActorContext`, the `shared.kernel.time.Clock` port with
the `platform.infra.time.SystemClock` adapter and fixed test clock, and the
strict `CorrelationId` value with `UlidCorrelationIdGenerator`.

Verification on 2026-09-29:

```text
./gradlew test \
  --tests 'org.meldtech.platform.shared.kernel.outbox.OutboxMessageTest' \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  --tests 'org.meldtech.platform.shared.kernel.identity.TypedIdentifierTest' \
  --tests 'org.meldtech.platform.platform.infra.time.SystemClockTest' \
  --tests 'org.meldtech.platform.platform.infra.context.UlidCorrelationIdGeneratorTest' \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL
```

Status: SATISFIED. `FEAT-PLAT-004` consumes these contracts and does not
redefine them.

## P0.4 - Feature-Specific Definition of Ready

The Architecture Owner and Engineering Lead approved the two additional
readiness artifacts from architecture v1.4:

- section 11.3 event contracts and evolution rules, including versioned type
  names, the compatibility matrix, dual publication during transitions, and
  distinct dead-letter handling for unhandled versions;
- section 14.6's complete durable idempotency inventory, including the outbox
  consumer key of `outbox_event_id` plus a consumer-side business key.

Signed evidence:

- `ci/dor/FEAT-PLAT-004/P0.4-outbox-contract-dor.json`
- `ci/dor/FEAT-PLAT-004/P0.4-outbox-contract-dor.architecture-owner.sig`
- `ci/dor/FEAT-PLAT-004/P0.4-outbox-contract-dor.engineering-lead.sig`

Both detached signatures were verified with GnuPG on 2026-09-29. Status:
APPROVED FOR IMPLEMENTATION.

## P0.5 - Grant-Matrix Defect Escalation

`TASK-PLAT4-DEFECT-001` and `TASK-PLAT4-DEFECT-002` are recorded and raised to
the Architecture Owner and the `FEAT-PLAT-002` owner before any outbox table or
role migration is authored. The approved local resolutions add:

- `app_outbox_relay`, limited to `USAGE` on `outbox` and `SELECT, UPDATE` on
  `outbox.outbox_event`, assumable by `app_worker` only;
- `app_outbox_maintenance`, limited to guarded owner-controlled partition
  routines and no row-data or arbitrary-DDL privilege, assumable by
  `app_worker` only.

Evidence:

- `docs/defects/TASK-PLAT4-DEFECT-001.md`
- `docs/defects/TASK-PLAT4-DEFECT-002.md`
- `ci/dor/FEAT-PLAT-004/P0.5-role-amendment-acknowledgement.json`
- `ci/dor/FEAT-PLAT-004/P0.5-role-amendment-acknowledgement.architecture-owner.sig`
- `ci/dor/FEAT-PLAT-004/P0.5-role-amendment-acknowledgement.persistence-owner.sig`

Both detached signatures were verified with GnuPG on 2026-09-29. Status:
APPROVED AS ADDITIVE, NARROW ROLE AMENDMENTS.

## P0.6 - FEAT-PLAT-006 Adoption Seam

`TASK-PLAT4-DEFECT-004` is raised to the Plan Owner because the feature card
omits `FEAT-PLAT-006` even though the relay depends on its background role and
scheduler singleton registry. The ordered seam is explicit: `P4.6` supplies a
temporary outbox-internal `pg_advisory_xact_lock` wrapper, and `P7.17` replaces
and deletes it after the shared registry is delivered, with both sibling
exactly-one-sweep suites rerun.

Evidence:

- `docs/defects/TASK-PLAT4-DEFECT-004.md`
- `docs/evidence/FEAT-PLAT-004/P0.6-feat-plat-006-adoption-seam.md`

Status: RECORDED; CLOSURE REMAINS OWNED BY `P7.17`.

## P0.7 - PostgreSQL Version Decision

`TASK-PLAT4-OBS-002` is raised to the Architecture Owner and the
`FEAT-PLAT-002` owner because architecture v1.4 sections 9.1 and 12.1 name
major 16 while the later approved persistence decision selects major 17.
`FEAT-PLAT-004` consumes PostgreSQL 17 through the repository-wide immutable
`postgresqlImage` property and pins no version or image of its own.

Evidence:

- `docs/defects/TASK-PLAT4-OBS-002.md`
- `docs/decisions/P0.5-postgresql-version-approval.md`
- `docs/evidence/FEAT-PLAT-002/P0.5-postgresql-version-recommendation.md`

Verification on 2026-09-29:

```text
./ci/verify-postgresql-baseline
POSTGRESQL BASELINE: PASS
Approved PostgreSQL 17 image and runtime isolation behavior verified.
```

Status: AUTHORITATIVE VERSION RECORDED; BASELINE TEXT CORRECTION REMAINS OPEN.
