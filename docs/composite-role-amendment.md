# Composite Role Amendment Procedure

Status: normative governance procedure for changing the closed `ADR-023`
atomic-flow enumeration.

The MVP enumeration contains exactly one flow:
`examaccess.verifyPinAndStartAttempt` using `app_txn_examentry`. Adding,
removing, or materially changing a flow is an ADR amendment, not a
configuration or ordinary grant change.

A second composite role is a meaningful architectural event. It expands the
set of transactions allowed to write across module schemas and therefore
changes module ownership, failure atomicity, least privilege, and the blast
radius of a compromised workload.

## Required Proposal

The proposing feature must provide:

1. The initiating handler, participating modules, and business invariant that
   must commit atomically.
2. Evidence that asynchronous propagation through the outbox cannot satisfy
   the invariant.
3. The exact table-level privilege set, including required reads, writes,
   shared audit/outbox inserts, and every privilege deliberately excluded.
4. The permitted pool login roles and workloads, with justification for each
   membership.
5. Tenant-context behavior, lock order, timeout behavior, rollback semantics,
   idempotency, and failure-injection scenarios.
6. A comparison proving the proposed grant set is strictly narrower than the
   union of the participating module roles.
7. Security and operational impact, including audit coverage, observability,
   migration order, rollback compatibility, and pooled-connection reset risk.

## Mandatory Approval

Before implementation, one amendment record bound to the same immutable
proposal digest must be approved by three distinct accountable roles:

- Solution Architect;
- Engineering Lead; and
- Security.

The amendment updates `ADR-023` and the ratified architecture's enumerated-flow
table. Missing, expired, digest-mismatched, or same-person approvals block the
change. A pull-request approval alone is not a substitute for the ADR record.

## Implementation Checklist

After approval, update these artifacts as one reviewed change:

- `AtomicCrossModuleFlow` and its composite-role mapping;
- the closed `AssumableDatabaseRole` policy;
- `grant-matrix.json` memberships, explicit object grants, and denials;
- generated repeatable grant SQL and any forward role-creation migration;
- the handler's `@SynchronousAtomicFlow` declaration and transaction boundary;
- R7/R10 conformance fixtures and the live `pg_roles` grant audit;
- composite-role narrowness, atomicity, fault-injection, tenant-isolation, and
  pool-reuse tests; and
- the grant, ownership, traceability, and operational documentation.

Role names follow `app_txn_<flow>`. They are fixed compile-time identifiers,
never request data, properties, feature flags, or database-driven selection.
The role must not inherit a module role or be generated from the union of
module grants.

## Release and Closure

Provision the new role and grants through a forward migration before deploying
code that can select it. Code rollback leaves the additive role in place;
revocation is a later reviewed migration after all callers are retired.

Close the amendment only when CI stage 4 and PostgreSQL integration gates are
green, the new atomic-flow fault-injection proof is retained, security confirms
the live grant set equals the approved proposal, and the architecture baseline
references the evidence. Until then, the flow remains unavailable.
