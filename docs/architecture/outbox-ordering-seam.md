# Outbox Ordering: Unused Consistent-Hash Seam

Status: documented option, **not adopted or deployed**. Owner: Architecture Owner with outbox, consuming-feature and Platform Ops owners. Tracks FEAT-PLAT-004 P10.9 and TASK-PLAT4-DEFECT-006.

## Current Contract

The [delivery contract](../../contracts/events/DELIVERY-CONTRACT.md) promises at-least-once delivery with **no ordering guarantee**, idempotent apply and version guards. Oldest-first relay selection and sequential batch publication do not establish per-aggregate consumer completion order. A shared quorum queue at prefetch 32 and an aggregate partition-key header do not create one consumer per key. Retries, redelivery and consumer concurrency can reorder effects.

No MVP requirement currently justifies replacing order-insensitive consumers. REQ-IAM-018 precedence is resolved by the owning transaction rather than broker arrival order. Do not add a plugin, partition queues, single-consumer settings or a routing migration as part of publishing this seam.

## When to Reconsider

Reopen only for an accepted business requirement that cannot safely be met by an atomic aggregate version/state predicate. Obtain an architecture amendment specifying exactly which keys require order, what order means (publication, delivery or committed effect), whether poison events may block a key, and the latency/availability tradeoff. No change may silently weaken durable delivery, tenant isolation or the consumer business-key guard.

## Candidate Design and Costs

A consistent-hash exchange could route a stable aggregate key to a fixed set of partition queues. Before adoption, resolve whether the key must include context and tenant to avoid collisions; preserve its meaning across retries and releases. One active processing lane per partition would be needed for the intended serial behavior. This still does not itself prove ordered effects: retries, failover, dead letters and concurrent upstream producers need explicit sequencing and recovery rules.

Costs include broker-plugin lifecycle/compatibility and HA testing, more quorum queues and replicas, bounded consumer/connection allocation, hot-key skew and head-of-line blocking, partition-level monitoring and recovery, and an audited rebalance procedure. Fixing a partition count simplifies routing continuity but constrains scaling; changing it can move a key while old work remains queued. Existing worker/database budgets may not be increased without capacity approval. Do not quote a throughput benefit without workload measurements.

## Required Adoption Evidence

1. Approved requirement, revised delivery contract and owner-assigned architecture decision; never a silent widening of the current contract.
2. Version-pinned broker/plugin provisioning and tested quorum durability, failover and single-active processing semantics.
3. Stable key and partition-count contract, tenant-safe routing, bounded concurrency and measured hot-key/capacity behavior.
4. Migration/rebalance protocol that drains or fences old routing before the new route admits work; explicit rollback and mixed-version producer/consumer rules. Dual-publishing alone is not an ordering migration.
5. PostgreSQL/RabbitMQ tests for duplicates, out-of-order upstream publication, poison messages, retry/DLQ replay, crashes during commit/ack, leader failover and partition movement. Retain business-key/version guards even if stronger ordering is approved.
6. Updated dashboards, alerts, runbooks, release readiness and traceability evidence, with an owned staging drill.

Until all conditions are satisfied, consumers remain order-insensitive and version-guarded. Even an approved future design must not claim global ordering or exactly-once delivery.
