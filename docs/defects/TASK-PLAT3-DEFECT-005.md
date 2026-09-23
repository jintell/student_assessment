# TASK-PLAT3-DEFECT-005 - Production Redis Ownership Gap

Status: DEFERRED TO PHASE 6  
Resolution owner: Engineering Lead  
Implementation feature: Unassigned  
Assignment deadline: `FEAT-PLAT-003` `P8.7`

## Gap

Architecture v1.4 requires Redis for non-authoritative caches, generic `POST` idempotency responses and other degradable runtime functions. CI stage 8 also requires a Redis Testcontainer. The delivery plan does not assign production Redis provisioning, topology, sizing, high availability, failover, network policy or operational ownership to a feature.

## Decision

Production provisioning is explicitly deferred to Phase 6. The Engineering Lead owns resolution of the gap and must name the implementation feature before `P8.7`. Production deployment remains blocked while that feature is unassigned; a local or unmanaged Redis instance is not an acceptable production substitute.

`FEAT-PLAT-003` continues to own only the kernel port, Redis adapter, integration-test substrate and pinned local-development service. Every behavior must remain correct with Redis empty or unavailable, and no candidate-path request may be denied because Redis is unavailable.

## Evidence

- `ci/dor/FEAT-PLAT-003/P0.5-production-redis-gap.json`
- `ci/dor/FEAT-PLAT-003/P0.5-production-redis-gap.engineering-lead.sig`
