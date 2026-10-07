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

Status: **SATISFIED**

The signed audit Definition of Ready is
`ci/dor/FEAT-AUD-001/P0.8-audit-contract-dor.json`. It fixes:

- `N = 64`, owned as a contract by `FEAT-AUD-001` and provisioned by
  `FEAT-TENANT-001` before tenant writes are enabled;
- UTC monthly `(retention_class, period)` epochs;
- ascending `(period, retention_class)` close order with the signed retention
  class order and one seal per transaction; and
- versioned `<bounded-context>.<EVENT_CODE>.v<major>` event types, registered
  by each owning capability before emission and aligned exactly with the
  `FEAT-OBS-001` `eventCode` token.

The Architecture Owner approved the contracts and the `FEAT-TENANT-001`
owner, acting as Engineering Lead, acknowledged the provisioning boundary.

Verification on 2026-10-07:

```text
./ci/verify-audit-contract-dor
AUDIT CONTRACT DOR: PASS
Shard, epoch, close-ordering, catalogue, provisioning ownership, and both signatures are verified.
```

`TASK-AUD1-DEFECT-007` is resolved and `P0.9` may now assess universal
readiness against this signed feature-specific gate.

## P0.9 - Universal Definition of Ready

Status: **SATISFIED**

All seven plan section 8.0 readiness criteria assess as satisfied:

| Criterion | Evidence | Result |
|---|---|---|
| Upstream requirements are approved and unchanged | Requirements v3.7 is the approved source baseline referenced by the plan and existing signed universal-DoR records | SATISFIED |
| Acceptance criteria are stated and testable | The `FEAT-AUD-001` feature card names `AC-AUD-001-01...02` and `AC-AUD-002-01...06`; task-list Appendix A maps them to executable work | SATISFIED |
| Architecture references resolve | Ratified architecture v1.4 contains `ADR-011`, `ARC-AUD-001...007`, `ARC-DATA-030/031`, and section 9.5 | SATISFIED |
| Hard dependencies are delivered or scheduled ahead | `P0.2` verifies `FEAT-PLAT-002`; `P0.3` verifies `FEAT-PLAT-003`; later retention and operational consumers are explicitly deferred to their owning features | SATISFIED |
| No open blocking question applies | The grant and codec decisions are signed (`P0.4`, `P0.5`); the secret conflict and baseline gaps have adopted resolutions (`P0.6`, `P0.7`); the feature-specific DoR is signed (`P0.8`) | SATISFIED |
| Security expectations are identified | Per-table least privilege, append-only trigger backstop, tenant-scoped reads, privileged-read emission, payload secret rejection, and evidence-preservation rules are assigned to tasks | SATISFIED |
| Consumed interfaces are defined sufficiently | Persistence schema/default grants and kernel actor, tenant, clock, correlation, and secret-field contracts have published handovers | SATISFIED |

Verification on 2026-10-07:

```text
./ci/stage-4a
STAGE 4a: PASS

./ci/verify-audit-grant-amendment-approval
AUDIT GRANT AMENDMENT APPROVAL: PASS

./ci/verify-audit-canonical-codec-approval
AUDIT CANONICAL CODEC APPROVAL: PASS

./ci/verify-audit-contract-dor
AUDIT CONTRACT DOR: PASS
```

The approved record is
`ci/dor/FEAT-AUD-001/P0.9-universal-dor.json`. It records no unmet readiness
item and uses no waiver. Phase 6 A6/A7 evidence and audit metric work remain
explicit production-release conditions rather than being misclassified as
readiness failures.

The Solution Architect and Engineering Lead signed the exact record with
distinct trusted identities.

Verification on 2026-10-07:

```text
./ci/verify-audit-universal-dor
AUDIT UNIVERSAL DOR: PASS
All seven readiness criteria, the signed feature DoR, and both detached signatures are verified.
```

`FEAT-AUD-001` is ready for discovery, design, and implementation. This DoR
does not approve production release or discharge any Definition-of-Done item.
