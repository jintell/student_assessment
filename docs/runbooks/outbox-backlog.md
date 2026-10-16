# Outbox Backlog

Trigger: `OutboxBacklog`, P2 — PENDING depth > 5,000 **or** oldest pending age > 300 seconds. Rule: `config/alerting/outbox-backlog.rules.yml`. Owner: Platform Ops with the outbox/runtime owner.

## Establish Whether Data Is Live

Record environment, deployment, alert start, release and correlation references. Check scrape health and sample freshness. NaN, missing or stale backlog values are unknown, not an empty backlog. Compare worker observations with `max`, scoped to one cluster/namespace/job; do not sum duplicated global snapshots. Confirm the production relay and backlog sampler are deployed and the registry reservation is enabled. P8.5/P8.6 and live P9.2 acceptance are currently open; an intentionally disabled reservation is a release prerequisite, not a crashed relay.

Use the approved restricted diagnostic connection; do not expose worker credentials or grant operator access through an ad hoc role change. Where no such capability is provisioned, escalate to the database/feature owner.

```sql
SELECT state, count(*) AS rows, min(created_at) AS oldest_created_at
FROM outbox.outbox_event GROUP BY state ORDER BY state;

SELECT count(*) AS due_pending
FROM outbox.outbox_event
WHERE state = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP;

SELECT config_key, integer_value, minimum_integer, maximum_integer, revision
FROM platform.platform_config
WHERE config_key IN ('outbox.relay.batch-size', 'outbox.relay.tick-interval-ms');
```

PUBLISHED rows are retained history. FAILED rows need separate recovery and are not part of the PENDING threshold.

## Classify and Recover

| Evidence | Likely cause | Action |
|---|---|---|
| Fresh ticks, publication rate drops, broker failures rise, broker/TLS/queue health fails | Broker outage or access/topology problem | Restore broker, TLS, credentials or routing through the deployment owner. Request writes remain durable. Eight failures can leave FAILED rows; recover these after repair. |
| No progressing owner tick, old claims or uncertain lock ownership | Stalled relay | Follow [stalled relay](outbox-relay-stalled.md); tick attempts from losing contenders are not successful owner work. |
| Healthy confirms and owner progress, but incoming work exceeds publication throughput | Volume spike | Assess capacity and bounded batch tuning; preserve grading's reserved capacity and the connection envelope. |
| Unknown samples or database counts disagree with gauges | Collection failure | Restore collection; missing series never establish an empty queue. |

## Bounded Tuning

The loader accepts batch size 1–1,000 (default 200) and tick interval 50–5,000 ms (default 200 ms), retaining the last valid snapshot on rejection. Production reload wiring is absent, and the runtime registry currently fixes outbox cadence at 200 ms. Editing a row does not prove a live cadence change. Use the approved audited configuration control when delivered; involve the runtime owner to reconcile cadence contracts. Do not update the registry directly, add a scheduler, bypass singleton locking or exceed approved pools/replicas.

Tune one variable at a time; verify the accepted revision, throughput and latency, and revert the configuration if database pressure grows. The worker budget is grading 4 + lock 2 + background 4. Increasing batch size must not add concurrent database work. Publication retries use bounded jittered backoff capped at 30 seconds; do not reset attempt counters to hide an outage.

## Verify Recovery

Require fresh samples, declining pending depth/age, progressing confirmed publication, understood FAILED rows, controlled DLQ depth and unaffected request/answer latency. Retain before/after counts, revisions, release references and recovery time. Follow [failed rows](outbox-failed-rows.md) and [DLQ recovery](integration-dlq.md) for residual work. Never delete pending events or detach partitions to clear an alert; follow the [retention release gate](../outbox-release.md#partition-detach-release-gate).
