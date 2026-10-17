# Outbox Operations

FEAT-PLAT-004 P10.5 operational reference. The adapters and local proofs exist; production relay/reclamation/sampling assembly, staging provisioning and live backlog alert acceptance remain open under P8.5/P8.6/P9.2. This guide describes the required algorithm and implemented adapter behavior, not a running deployment.

## State and Ownership

| Transition | Condition / owner |
|---|---|
| New → PENDING | `OutboxWriter` inserts in the business transaction; rollback removes both |
| PENDING → CLAIMED | Relay claims due rows oldest-first with `FOR UPDATE SKIP LOCKED`, bounded batch, owner and expiry |
| CLAIMED → PUBLISHED | Broker confirm succeeds and the owner-guarded database update succeeds |
| CLAIMED → PENDING | Failed attempt below eight, with bounded retry time; or expired-claim reclamation |
| CLAIMED → FAILED | Eighth failed publication attempt; operator attention required |
| FAILED → PENDING | Authorized audited redrive only, preserving event identity |

The shared runtime `SweepLock` coordinates `outbox-relay` using the registry key. Claims require a reactive transaction and use `created_at, outbox_event_id` ordering. The design lease is 30 seconds; the caller supplies the actual expiry. Publish sequentially within a batch with publisher confirms, not concurrent fire-and-forget. Mark a row published only under its event/partition identity, CLAIMED state and matching owner. A lost ownership update must not overwrite another claim. Claiming and database state transitions do not make broker delivery atomic: a confirm followed by a crash can cause redelivery.

Retries start from 200 ms exponential delay, with deterministic event-ID jitter up to approximately 25%, capped at 30 seconds. Count failed attempts, not reclamation passes. FAILED rows leave the normal drain path and must not block later due rows. Reclamation returns only expired CLAIMED rows to PENDING, clears ownership and makes them due without increasing attempts. Recovery time depends on actual lease expiry and the deployed reclamation cadence; grading's registry sweep is not an outbox reclaimer.

## Configuration and Capacity

| Configuration key | Default | Accepted range |
|---|---:|---:|
| `outbox.relay.batch-size` | 200 | 1–1,000 rows |
| `outbox.relay.tick-interval-ms` | 200 | 50–5,000 ms |

`RelayConfigurationLoader` reads `platform.platform_config`, validates bounds and retains the last valid snapshot on rejection. Production reload wiring is missing; the shared registry currently fixes relay cadence at 200 ms. A configuration-row edit is not evidence of live retiming. Use approved audited controls when delivered and reconcile the runtime contract before promising tuning without deployment. Never add an independent scheduler, increase connections outside the approved envelope, or borrow grading's capacity: worker allocation is grading 4 + lock 2 + background 4.

## Retention

The parent table has UTC Monday-aligned weekly partitions. Migrations create initial partitions; `attach_week_partition(date)` can create future partitions through the restricted maintenance boundary. Ensure future coverage before writes reach a boundary; a production maintenance schedule is not currently wired.

`detach_expired_partition(name)` validates the attached partition identity, serializes maintenance and returns false unless its upper bound is at least seven days old, every row is PUBLISHED and every publication timestamp is non-null and at least seven days old. Any PENDING, CLAIMED, FAILED or recent publication blocks the whole partition. Detach removes parent-table visibility; it is not a row delete or automatic table drop. Direct child access is locked down. The independently retained audit record is durable evidence, not the outbox transport log.

**Do not schedule or invoke detach before the staging P7.18 release gate.** Function privileges already exist; the restriction is operational, not a revoked grant. Follow [release/rollback](outbox-release.md); image rollback neither undoes schema nor reattaches detached data. Never clear alerts by deleting rows, purging queues or detaching unresolved work.

## Observe and Recover

Use the [metric catalogue](metric-catalogue.md#outbox-addendum--task-plat4-obs-001). Unknown/stale samples are not zero. Aggregate duplicated global backlog snapshots with `max` within one deployment and replica-local counter rates with `sum`. Tick attempts include lock losers/errors and are not owner-success evidence. PENDING > 5,000 or oldest pending age > 300 seconds is the baseline P2 backlog condition; additional FAILED/liveness/DLQ/version alerts remain proposals pending Ops adoption.

- [Backlog](runbooks/outbox-backlog.md): broker outage versus stalled relay versus load; bounded tuning.
- [FAILED rows](runbooks/outbox-failed-rows.md): metadata inspection, repair, guarded delivery and audited redrive.
- [Integration DLQ](runbooks/integration-dlq.md): compatible consumer deployment before draining unsupported versions.
- [Stalled relay](runbooks/outbox-relay-stalled.md): exact advisory-lock holder mapping, safe restart and conditional recovery window.
- [Readiness handoff](evidence/FEAT-PLAT-004/P9.10-operational-readiness-handoff.md): fresh zero backlog/FAILED/DLQ and successful relay work, not merely registered meters.
