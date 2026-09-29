# TASK-PLAT3-DEFECT-005 Production Redis Ownership Gap

Status: **OPEN - RAISED FOR NEXT BASELINE; PRODUCTION RELEASE BLOCKED**

Architecture documentation owner: Architecture Owner

Resolution owner: Engineering Lead

Implementation feature: Unassigned

Raised by: `FEAT-PLAT-003`

## Baseline Defect

`ARC-PLAT-010` requires Redis for generic non-durable `POST` idempotency, and
CI stage 8 requires a Redis Testcontainer, but the delivery plan assigns no
feature to production Redis provisioning, topology, sizing, high availability,
failover, network policy, secret mounting, or operations.

## Resolution Adopted by This Feature

The Engineering Lead approved an explicit Phase 6 deferral.
`FEAT-PLAT-003` owns only the framework-free port, Redis adapter, integration-
test substrate, pinned local service, and fail-closed degradation contract.
Every behavior remains correct with Redis empty or unavailable, and no
candidate-path request depends on it. Production deployment remains blocked
while the implementation owner is unassigned.

## Next-Baseline Action

Add a named feature that owns the production service, capacity, workload
identity, secret delivery, network policy, high availability, failover drill,
monitoring, and runbook. Record that Redis is non-authoritative and that an
unmanaged local instance is not a production substitute.

## Evidence

- `ci/dor/FEAT-PLAT-003/P0.5-production-redis-gap.json`
- `ci/dor/FEAT-PLAT-003/P0.5-production-redis-gap.engineering-lead.sig`
- `docs/evidence/FEAT-PLAT-003/P8.7-deferral-register.md`
