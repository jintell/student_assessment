# Audit-event catalogue convention

Normative authoring contract for FEAT-AUD-001 and every later capability.
Authority: the signed [P0.8 contract](../ci/dor/FEAT-AUD-001/P0.8-audit-contract-dor.json)
and [P2.3 design](evidence/FEAT-AUD-001/phase-2-architecture-design.md#p23-audit-event-catalogue-convention).
Publication does not assert that catalogue generation or runtime registration exists.

## Stable identity

Use `<bounded-context>.<EVENT_CODE>.v<major>`. The owning module token matches
`[a-z][a-z0-9]*`, the event code matches `[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*`,
and the major is a positive integer without leading zeros. Prefer a completed
fact in past tense. The middle token is exactly FEAT-OBS-001's `eventCode`:
no aliases or case conversion. Never rename, reuse or repurpose an issued
identifier. Breaking meaning or payload compatibility allocates a new major;
old declarations and schemas remain available for retained evidence.

## Required declaration

Each feature must include a reviewed declaration with its implementation,
before first emission. Use a feature-owned catalogue artifact and link it
from that feature's evidence; no central generated registry currently exists.

| Field | Required meaning |
|---|---|
| `event_type` | Immutable identifier in the grammar above |
| `owner` | Owning bounded-context/module and accountable feature |
| `purpose` | Business fact, including why this is auditable |
| `retention_class` | Fixed class or explicit rule selecting from approved candidates |
| `payload_schema_version` | Version and retained schema reference; no secrets |
| `eventCode` | Exact middle token, aligned with the business-metric contract |
| `entity_type` | Stable entity-reference type and identifier semantics |
| `tenant_scope` | Tenant-required, or explicitly approved platform-scope exception |
| `privileged_read` | Whether the event attests a privileged read |

Declarations must cover every business-significant mutation and privileged read,
not merely externally published integration events. Audit and outbox catalogues
are different contracts. Add payload/retention tests and commit/rollback tests;
an event declaration never grants authority to perform the underlying action.
Reject duplicate identifiers, token mismatch and incompatible schema reuse in
review. Do not invent a tenant to bypass platform-scope restrictions.

## Current enforcement and limits

The kernel `AuditEvent` validates identifier shape, required fields and a
non-empty immutable retention-candidate set; it does **not** check membership.
The blocking stage-4 `R8AuditCoverageTests` inspects non-read-only
`@Transactional` methods on classes named `Handler` in `.slice.` packages and
requires a direct call to the real `AuditEmitter.emit` port. It is a structural
guard, not proof of subscription, all branches, actual commit, catalogue
completeness or privileged-read coverage. Those obligations require tests and
review. The stronger startup/generation checks in P2.3 remain unimplemented;
this publication must not be cited as evidence that they run.

The current emitter rejects tenantless actors, even though the broader kernel
actor model can express platform actors. Production consumer wiring remains a
separate prerequisite. Current audit-owned identifiers include
`audit.AUDIT_SHARD_POLICY_CHANGED.v1`, `audit.AUDIT_EPOCH_DISPOSED.v1`,
`audit.AUDIT_EPOCH_DISPOSITION_SUPPRESSED.v1`,
`audit.AUDIT_EPOCH_HOLD_RELEASED.v1` and
`audit.COMPLIANCE_AUDIT_EVENTS_READ.v1`; this inventory is not a substitute for
each event's complete declaration or a claim that every workflow is live.

See [interface publication](evidence/FEAT-AUD-001/P8.1-interface-publication.md)
for the consuming kernel API and [acceptance status](evidence/FEAT-AUD-001/P7.28-acceptance-verification.md)
for the remaining coverage gaps. Convention changes require the P0.8 approval
and acknowledgement process, not silent edits to historical declarations.
