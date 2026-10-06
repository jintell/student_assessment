# TASK-PLAT4-OBS-001 Outbox Alert and Dashboard Gap

Status: **OPEN - RAISED AND HANDED OFF**

Owners: `FEAT-PLAT-004` (metric emission), `FEAT-OBS-001` (naming and
cardinality), `FEAT-OPS-004` (rules, routing, dashboards, and exercises)

## Gap

Architecture section 16.4 defines only the P2 outbox backlog alert, while
section 16.2 has no outbox metric inventory and section 16.5 has no outbox
panel. Failed rows, relay liveness, DLQ depth, unsupported versions, and relay
throughput can therefore be measurable in implementation without an approved
operator surface.

## Metric Limb

`FEAT-PLAT-004 P9.1` owns registration and emission of:

- `outbox_backlog_depth{state}`;
- `outbox_oldest_pending_age_seconds`;
- `outbox_relay_published_total`;
- `outbox_relay_publish_failure_total{reason}`;
- `outbox_relay_tick_total` and relay tick duration;
- `outbox_stale_claim_reclaimed_total`;
- `outbox_event_failed_total`.

Labels must be closed and bounded; no metric may carry `tenant_id` or a
correlation identifier. `FEAT-OBS-001` supplies that naming/cardinality
contract but does not represent the sibling's open registration task as done.

## Alert Proposals

| Signal | Severity | Trigger | First action |
|---|---|---|---|
| Backlog | P2 | `PENDING` depth above 5,000 or oldest age above five minutes | Distinguish broker outage, stalled relay, and genuine volume spike before tuning or replay. |
| Failed row | P2 | Any outbox row enters `FAILED` | Inspect the bounded failure reason and retained row metadata; classify before an audited redrive. |
| Relay liveness | P2 | No relay tick for more than 60 seconds | Check worker health, singleton ownership, database connectivity, and broker reachability before changing the tick interval. |
| Integration DLQ | P2 | `integration.dlq` depth is at least one | Classify poison payload versus unsupported version before any drain or redrive. |
| Unhandled version | P2 | Any unhandled-version dead letter | Deploy a compatible consumer; do not repeatedly redrive a version the current consumer cannot handle. |

## Dashboard 6 Proposal

Add outbox backlog by state, oldest pending age, relay throughput/failures,
relay tick liveness, reclaimed claims, failed rows, and DLQ depth to the
Platform Health dashboard. Panels must link to the backlog, failed-row, and
DLQ runbooks when those sibling deliverables are published.

## Closure Criteria

- `FEAT-PLAT-004` registers and emits the metric set.
- `FEAT-OPS-004` installs, routes, and exercises the five P2 rules.
- Dashboard 6 contains the proposed panels with working runbook links.
- Synthetic backlog, failure, liveness, poison, and unsupported-version cases
  retain alert-delivery evidence.
