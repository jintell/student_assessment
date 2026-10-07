# TASK-AUD1-DEFECT-007 Audit Shard Count Has No Provisioning Owner

Status: **RESOLVED - PROVISIONING OWNER ACKNOWLEDGED**

Owners: Architecture Owner, `FEAT-AUD-001`, and `FEAT-TENANT-001`

Raised by: `FEAT-AUD-001`

## Baseline Defect

`ARC-AUD-005` fixes `N = 64` per tenant at provisioning and permits a change
only at an epoch boundary as a recorded policy change. The plan assigns tenant
default provisioning to `FEAT-TENANT-001` but does not assign that feature the
shard-count input. Without an owner, a tenant can start writing before its
chain topology is defined.

## Proposed Ownership Boundary

- `FEAT-AUD-001` owns the shard-count parameter contract, validation, default
  value `64`, epoch-boundary-only transition rule, and audited policy-change
  record.
- `FEAT-TENANT-001` owns invoking that contract while provisioning each tenant
  and must complete it before enabling tenant writes.
- A later change is never a configuration edit in place. It closes the current
  epochs, records the policy transition, and applies the new count only to the
  next epoch.

## Required Definition-of-Ready Decision

The signed audit DoR must name the `FEAT-TENANT-001` owner and approve this
handoff together with the remaining audit readiness contracts:

- epoch identity `(retention_class, monthly period)` and monthly close policy;
- canonical `(period, retention_class)` close ordering, one seal per
  transaction; and
- a stable, versioned audit-event catalogue namespace aligned with the
  observability `eventCode`, including the registration rule later features
  must follow.

The Architecture Owner approved the feature-specific contracts and the
`FEAT-TENANT-001` owner, acting as Engineering Lead, acknowledged ownership of
per-tenant shard-count provisioning in:

- `ci/dor/FEAT-AUD-001/P0.8-audit-contract-dor.json`;
- `P0.8-audit-contract-dor.architecture-owner.sig`; and
- `P0.8-audit-contract-dor.tenant-owner.sig`.

`ci/verify-audit-contract-dor` verifies the complete contract, trusted signer
identities, and signer separation. The Phase 8 handover remains an
implementation deliverable, but its receiving owner and acceptance boundary
are now settled.
