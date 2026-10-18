# Compliance audit query

REQ-AUD-002 separates the tenant audit trail from operational logs. Logs,
traces and metrics support diagnosis; they are not the immutable audit record
and cannot substitute for missing events or verification evidence.

## Availability and authority

The implemented slice contract is `GET /api/v1/audit-events`, operation
`audit.getComplianceAuditEvents`, on the `api` role. **It is not a published,
production-wired endpoint yet.** The authoritative capability provider,
approved reader grants, secured shared transaction and bean graph remain
blocked by [TASK-AUD1-BLOCKER-002](defects/TASK-AUD1-BLOCKER-002.md).
Do not bypass that boundary with a database owner connection or public route.

The approved policy is workforce-only, tenant-scoped `AUDIT_COMPLIANCE_READ`.
Tenant comes from authenticated `ActorContext`, never a query parameter.
Candidate or cross-tenant access must be denied. Privileged reads themselves
emit `audit.COMPLIANCE_AUDIT_EVENTS_READ.v1` in the same secured caller
transaction. Failure to append means no page is returned. Successful responses
use `Cache-Control: no-store`; minimise and protect any authorised export too.

## Filters and pagination

| Parameter | Contract |
|---|---|
| `occurredFrom` | Optional inclusive instant |
| `occurredTo` | Optional exclusive instant; when both bounds are supplied, start must precede end and span cannot exceed 366 days |
| `entityType`, `entityId` | Supply together for entity history; registered type and nonblank identifier |
| `eventType` | Registered versioned type for event timeline; cannot combine with entity history |
| `pageSize` | Default 100; range 1–500 |
| `cursor` | Opaque authenticated keyset cursor; reuse only with the same tenant and filters |

Without entity/event filters the mode is tenant timeline. Missing time bounds
are not silently replaced by a documented default range; callers should use
explicit bounded windows. The first page fixes an `asOf` cutoff, retained in
subsequent cursors. Do not edit/decode cursors, reuse them across tenants or
treat them as offset/page numbers. A cutoff is not a long-running database
snapshot or a guarantee against later backdated inserts.

The response contains required `items`, `nextCursor` and `hasMore`.
`nextCursor` is an empty string exactly when `hasMore` is false. Items expose
audit-event ID, event/entity identity, actor type/id, occurrence time,
correlation, retention class, shard and sequence. They do **not** expose raw
payload, secrets, signatures or signing material. A query page is not itself a
cryptographic verification report; use the [verification model](audit-verification.md)
for integrity attestations.

## Operator and consumer checks

Before enabling access, prove capability denial, tenant isolation, cursor
tamper/filter rejection, append failure rollback and no-store behavior against
the real secured slice. Preserve correlation and protected evidence references
when investigating failures; never copy sensitive records into ordinary logs.

The [OpenAPI draft](../contracts/openapi/audit-compliance.yaml) currently defines
the successful response but lacks security/error contracts. Stage 9 validates
integration-event JSON Schemas, **not** this OpenAPI document or an HTTP
breaking-change diff. The [publication blocker](evidence/FEAT-AUD-001/P8.3-compliance-contract-publication.md)
requires an approved secured contract, baseline and blocking compatibility
check before consumer release. This guide documents the intended/implemented
component boundary without claiming those release gates have passed.
