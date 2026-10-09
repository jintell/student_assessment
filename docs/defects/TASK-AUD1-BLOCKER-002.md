# TASK-AUD1-BLOCKER-002: Compliance Read Runtime Authority

Status: OPEN. Reviewed 2026-10-09. Blocks P4.29 runtime completion and P6.7.

## Verified prerequisites

`R2dbcComplianceAuditQueries` now implements the three approved query modes,
bound parameters, the mixed-direction keyset cursor, occurrence bounds and
bounded pages on the caller's `TransactionalConnection`. Real PostgreSQL tests
exercise it and independently prove tenant RLS with a rolled-back SELECT grant.
These are adapter/database tests, not an authorized HTTP-path certification.

## Remaining dependencies

| Dependency | Evidence | Owner / required result |
| --- | --- | --- |
| Authoritative tenant capability | IAM and tenancy contain package scaffolding and schema/role migrations only; no membership/permission decision implementation exists. `AuditComplianceCapabilityView` has no production binding. Plan FEAT-IAM-003 explicitly forbids token claims as authorization authority. | FEAT-IAM-003, with FEAT-IAM-001/002: authenticated actor binding and an authoritative decision for `(actorId, tenantId, AUDIT_COMPLIANCE_READ)`, denying on missing/inactive state or resolution failure. |
| Reader privileges | Signed P0.4 grants application roles INSERT only on `audit_event`; V10 adds anchor/sealer grants, not compliance SELECT. | Architecture Owner and Security Engineer approval plus FEAT-PLAT-002 owner acknowledgement of the proposed grant amendment below. |
| Secured caller transaction | `AssumableDatabaseRole` has no audit reader; `TransactionalConnection.current()` is installed only by the exam-entry collaboration. `@Transactional` alone neither selects an audit role nor supplies that handle. | FEAT-PLAT-002: reviewed audit transaction entry point that installs the approved role and actor-derived tenant, shares one connection with query/emission, enforces finalization, and cleans up context. |
| Production bean graph | Endpoint, handler, policy, catalogue and cursor codec have no production registration. `R2dbcAuditEmitter` also has no bean registration and requires real retention/shard providers. | FEAT-AUD-001 with the owning provider features: complete mandatory bean graph, external cursor key, approved catalogue and real read-audit emission before returning data. |

## Proposed grant amendment (not approved or applied)

Create a dedicated `app_audit_compliance_reader` NOLOGIN, NOINHERIT,
NOBYPASSRLS, NOCREATEROLE role. Allow the API pool identity to SET this role
without inheriting its privileges. The exact pool membership is part of approval.

| Object | Proposed privilege | Purpose |
| --- | --- | --- |
| `audit` schema | USAGE | Access the two approved relations. |
| `audit.audit_event` | SELECT of query/filter/order columns; INSERT for its own read-audit event | Compliance DTO projection and transactional read attribution. No payload SELECT is required. |
| `audit.audit_chain_head` | SELECT; UPDATE of `seq`, `head_hash`, `updated_at` | ADR-011A predecessor locking and conditional advance. |

Do not grant UPDATE/DELETE/TRUNCATE on events, INSERT on heads, ownership,
BYPASSRLS, child-partition access, or access to checkpoints, seals and root heads.
Keep tenant RLS forced. Amend the privilege matrix and effective-grant tests
alongside the new migration; do not rewrite applied migrations.

The signed P0.4 `changePolicy` requires renewed approval for **any wider
privilege or additional role membership**. P2.16 repeats that requirement for
broader grants. No approval or detached signature is asserted by this proposal.

## Wiring and closure sequence

1. Bind `AuditComplianceCapabilityView` to the IAM authority above. Prove removal
   of tenant permission denies an otherwise authenticated actor; never substitute
   token roles, a static allowlist, or an always-allow test lambda as authority.
2. Apply the approved role migration and expose the tenant transaction boundary.
   Keep query and read-audit append on its one `TransactionalConnection`.
3. Register the production endpoint, policy, handler, SQL adapter, catalogue,
   cursor codec and emitter/provider beans. Missing dependencies must fail closed.
4. Exercise the registered HTTP route with two seeded tenants and real policy:
   allowed own-tenant read, denied capability, foreign entity and unknown entity
   with the same non-disclosing 404, foreign/tampered cursor, and enumeration.
   Verify rejected requests expose no other-tenant data and cannot mutate it.
5. Repeat the predicate-free RLS test under the approved reader role. Verify the
   read-audit record commits with the page and emission failure returns no page.
6. Register the route's executable READ/WRITE/ENUMERATE scenarios in the existing
   isolation gate and retain its generated matrix. Only then close P4.29/P6.7.

No HTTP isolation-matrix row is supplied before those executable scenarios exist.
