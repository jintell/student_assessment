# Tenant Isolation

Status: normative three-layer isolation model for tenant-scoped behavior.

Source: architecture section 12.3 and `ADR-010`. Tenant isolation is a
zero-tolerance control for `REQ-SEC-003`, `SC-005`, and the Critical-impact
`RISK-TENANT-001`. No single layer is sufficient.

## Independent Layers

| Layer | Owner | Mechanism | Failure mode defeated |
|---|---|---|---|
| 1. Structural query contract | `FEAT-PLAT-001` | Rule R5 requires every `Queries` method accessing a tenant-scoped table to take the distinct `TenantId` type. The conformance gate checks method signatures. | A developer omits or cannot supply the tenant predicate. |
| 2. Database backstop | `FEAT-PLAT-002` | Every table with `tenant_id` has enabled and forced PostgreSQL RLS. The transaction installs `SET LOCAL ROLE` and `SET LOCAL app.tenant_id` before caller SQL. Policies use strict `current_setting('app.tenant_id', false)`. | A query missing its predicate cannot see a foreign tenant; a query with no context fails rather than widening scope; a table owner cannot bypass RLS. |
| 3. Object authorization | `FEAT-IAM-003` | The authorization resolver requires the actor's resolved membership tenant to equal the resource tenant. A foreign resource responds `404`, never `403`. | An actor requests a correctly tenant-scoped object that the actor is not permitted to see, without disclosing its existence. |

Layer 1 prevents the common authoring defect. Layer 2 assumes layer 1 can fail
and restricts the database result anyway. Layer 3 makes the business-level
permission decision independently of query correctness. The per-module grant
matrix adds a separate schema-ownership boundary beneath these controls.

## Database Transaction Protocol

For tenant work, the transaction lifecycle is:

1. Resolve and validate the actor's `TenantId` before connection use.
2. Begin the database transaction.
3. Execute `SET LOCAL ROLE <permitted-role>`.
4. Execute `SET LOCAL app.tenant_id = '<canonical-uuid>'`.
5. Install the role-specific search path.
6. Allow slice SQL only after all context statements succeed.
7. Commit or roll back, then execute `RESET ROLE` and `RESET ALL` before pool
   release.

Caller SQL before the ready state raises `R9_CONTEXT_NOT_FIRST`. A role outside
the login identity's membership is rejected by PostgreSQL. `SET LOCAL` reverts
at transaction completion; explicit reset is an additional pool-hygiene
backstop for cancellation, timeout, error, and abnormal release paths.

## RLS Contract

A tenant table is identified mechanically by a `tenant_id` column. Its owning
migration must apply one permissive `tenant_isolation` policy with both
`USING` and `WITH CHECK` predicates equal to:

```sql
tenant_id = current_setting('app.tenant_id', false)::uuid
```

The table must have both `ENABLE ROW LEVEL SECURITY` and
`FORCE ROW LEVEL SECURITY`. The catalogue gate rejects missing or extra
policies, missing force/enable flags, a non-strict setting lookup, a different
predicate, and policy-role drift.

## Platform Scope

Platform administration, retention sweeps, and reconciliation are closed,
enumerated platform-scope categories. Each requires `@PlatformScope`, an
authorised platform or system actor, and an explicit transaction-local
platform marker. Scope is never inferred from a package, missing `tenant_id`,
configuration flag, or caller request. There is no generic tenant-filter
bypass.

## Verification

- `ARC-VERIFY-005` proves an omitted tenant predicate returns zero foreign
  rows under forced RLS.
- `ARC-VERIFY-024` forces physical connection reuse across tenants, roles, and
  every Reactor termination path.
- The generated isolation matrix covers every existing tenant route across
  read, write, and enumerate operations; an uncovered route blocks CI stage
  10.
- `FEAT-SEC-001` extends matrix coverage as later endpoints are introduced.
- `FEAT-IAM-003` owns implementation and verification of the object-level
  authorization layer; this document does not claim that future feature is
  already delivered.
