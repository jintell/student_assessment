# FEAT-AUD-001 Phase 0 Gate-Prerequisite Record

Last assessed: 2026-10-07

## P0.1 - Architecture Authorization

The primary ratification path from `FEAT-PLAT-001` task `P0.5` is in force.
`ci/architecture-ratification.json` is `RATIFIED`, no
`temporaryArchitectureGate` is present, and `./ci/stage-4a` passes. Production
implementation is authorized subject to feature-specific dependencies and
blocking gates; `implementationAllowed: false` does not apply.

Verification on 2026-10-06:

```text
./ci/stage-4a
STAGE 4a: PASS
Step 12: architecture baseline arch-v1.4 is ratified and verified.
```

## P0.2 - FEAT-PLAT-002 Dependency

Status: **SATISFIED**

`FEAT-PLAT-002` tasks `P2.3`, `P3.9`, and `P7.15` are complete:

- `src/main/resources/db/migration/audit/V1__create_schema.sql` creates `audit` with
  `AUTHORIZATION app_migrator`;
- `src/main/resources/db/migration/audit/V2__grant_module_default_privileges.sql` grants schema
  usage and future-table `INSERT` to all twelve module roles without granting
  `UPDATE` or `DELETE`; and
- the `P7.15` integration test creates a later table as `app_migrator`, proves
  inherited `INSERT`, and proves `UPDATE` and `DELETE` are refused.

The trigger dependency is an ownership wording defect, not a missing
`FEAT-PLAT-002` artifact. Architecture §9.5 defines the convention as a
`BEFORE UPDATE OR DELETE` trigger that raises. The plan ownership matrix
assigns "Audit update and delete trigger" to `FEAT-AUD-001`; accordingly,
audit tasks `P2.5` and `P3.8` own its table-scoped design and implementation.
No trigger can be installed before this feature creates `audit.audit_event`.

Verification on 2026-10-06:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.auditDefaultPrivilegesApplyToTablesCreatedByLaterMigrations' \
  --rerun-tasks --console=plain

BUILD SUCCESSFUL
```

Dependency satisfied: `FEAT-AUD-001` can rely on the delivered schema and
future-table grant bootstrap; its existing `P2.5`/`P3.8` tasks retain the
trigger work mandated by the ratified architecture and plan.

## P0.3 - FEAT-PLAT-003 Dependency

Status: **SATISFIED**

`FEAT-PLAT-003` tasks `P4.2`, `P4.3`, `P4.14`, and `P8.5` are complete. The
repository contains the immutable `ActorContext`, typed `TenantId`, controlled
`Clock` port with its sole ambient-time `SystemClock` adapter, and the
kernel-owned `SecretFieldPattern`. The `P8.5` handover publishes these
contracts to `FEAT-AUD-001` and requires audit to consume the canonical actor,
tenant, time, and secret-field decisions without copying them.

Verification on 2026-10-07:

```text
./gradlew test \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  --tests 'org.meldtech.platform.shared.kernel.identity.TypedIdentifierTest' \
  --tests 'org.meldtech.platform.shared.kernel.security.SecretFieldPatternTest' \
  --tests 'org.meldtech.platform.platform.infra.time.SystemClockTest' \
  --console=plain

BUILD SUCCESSFUL
```

Handover evidence:
`docs/evidence/FEAT-PLAT-003/P8.5-interface-handovers.md`.

## P0.4 - Per-Table Audit Grant Decision

Status: **SATISFIED**

`TASK-AUD1-BLOCKER-001` is resolved by the signed decision at
`ci/dor/FEAT-AUD-001/P0.4-audit-grant-amendment-approval.json`. It approves
the table-scoped privileges required by the chain protocol while preserving
all immutable-event and no-application-delete guarantees.

The Architecture Owner and Security Engineer approved the exact matrix with
distinct trusted signers. The `FEAT-PLAT-002` owner, acting as Engineering
Lead, separately acknowledged the three amendments to `P7.8`, `P7.15`, and
the composite-role exclusion assertion.

Verification on 2026-10-07:

```text
./ci/verify-audit-grant-amendment-approval
AUDIT GRANT AMENDMENT APPROVAL: PASS
Per-table grants, persistence-test amendments, and all required signatures are verified.
```

The approval authorizes `P2.5` and `P3.8` to implement only the signed
per-table model. Any wider privilege remains blocked by the decision's change
policy.
