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

## P0.5 - Canonical Audit Codec Decision

Status: **SATISFIED**

`TASK-AUD1-DEFECT-001` is resolved by the signed Architecture Owner decision
at `ci/dor/FEAT-AUD-001/P0.5-canonical-audit-codec-approval.json`.

The decision selects `AUDIT_CANONICAL_JSON_V1`: an audit-domain-owned codec
with recursively sorted keys, UTF-8 NFC text, fixed-precision UTC RFC 3339
timestamps, locale-independent canonical numbers, distinct null and absent
values, committed golden vectors, and `hash_algo_version = 1` on every row.
Byte-affecting changes require a new version and existing rows are never
rewritten or re-hashed.

Verification on 2026-10-07:

```text
./ci/verify-audit-canonical-codec-approval
AUDIT CANONICAL CODEC APPROVAL: PASS
Version-1 direction, invariants, golden-vector requirement, and no-rewrite policy are verified.
```

Task `P2.4` is unblocked to define the exact version-1 byte grammar and golden
vectors without relaxing the signed constraints.

## P0.6 - Secret-Pattern policy_key Conflict

Status: **SATISFIED**

`TASK-AUD1-DEFECT-003` and `TASK-OBS1-DEFECT-003` now form the joint correction
record at the `FEAT-PLAT-003` ownership boundary. `SecretFieldPattern` permits
exactly the required `policy_key` leaf while retaining rejection of all other
secret-key forms.

The observability validator was corrected to consume
`SecretFieldPattern.permittedKeyFields()` instead of duplicating the approved
catalogue. A source-wide production check now finds one definition site.

Verification on 2026-10-07:

```text
./gradlew test \
  --tests 'org.meldtech.platform.shared.kernel.security.SecretFieldPatternTest' \
  --tests 'org.meldtech.platform.platform.infra.observability.ObservabilityConfigurationValidatorTest' \
  --tests 'org.meldtech.platform.platform.infra.observability.RedactingJsonSerializerTest' \
  --console=plain

BUILD SUCCESSFUL
```

## P0.7 - Architecture Baseline Gaps

Status: **SATISFIED - THREE GAPS RAISED WITH LOCAL RESOLUTIONS**

The following baseline defects are recorded for the Architecture Owner:

- `TASK-AUD1-DEFECT-002` preserves the real `ARC-VERIFY-010` and
  `ARC-VERIFY-011` ownership, records daily chain verification without
  inventing an identifier, and requests a next-baseline identifier;
- `TASK-AUD1-DEFECT-004` fixes the version-1 empty-shard contribution as
  `47b1714f4cbd8e976e91ea9c96957b99e2047333643d50060f98f07432e62908`,
  paired with count zero and ordered by ascending shard identifier; and
- `TASK-AUD1-DEFECT-006` records the complete five-table audit ownership set,
  including checkpoints, seals, and the root-chain head.

The empty-shard sentinel is settled before task `P2.7`; that task must carry
the exact input bytes and digest into the seal specification.

## P0.8 - Feature-Specific Definition of Ready

Status: **BLOCKED - SIGNED DOR AND PROVISIONING OWNER MISSING**

The ratified architecture supplies `N = 64`, monthly
`(retention_class, period)` epochs, and canonical `(period, retention_class)`
close ordering. The audit task list also requires a stable event-catalogue
convention before later features declare events.

`TASK-AUD1-DEFECT-007` records the proposed ownership boundary in which
`FEAT-AUD-001` owns the shard-count contract and `FEAT-TENANT-001` applies it
during tenant provisioning. No signed audit-specific DoR or acknowledgement
from the tenant-provisioning owner exists, and the catalogue convention has
not been approved as a consumer contract.

Task `P0.8` remains open. Task `P0.9` cannot confirm universal readiness until
this feature-specific gate is signed.
