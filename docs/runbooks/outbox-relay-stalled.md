# Stalled Outbox Relay

Owner: Platform Ops with the runtime/outbox owner. Investigate no advancing owner work for 60 seconds, growing pending age or expired claims. The 60-second P2 alert is proposed, not yet a live baseline rule. `outbox_relay_tick_total` includes unsuccessful contenders and errors: an increasing counter alone does not prove publication progress.

## Establish Scope and Lock Ownership

Confirm worker readiness, deployed relay assembly, enabled registry reservation and fresh telemetry. P8.5 production assembly is currently absent and the reservation is disabled; that is a rollout blocker, not evidence of an orphaned lock. Use the approved operations diagnostic role; request missing access rather than granting it yourself.

```sql
SELECT sweep_name, lock_key, default_interval_ms, enabled, updated_at
FROM platform.sweep_registry
WHERE sweep_name = 'outbox-relay';

SELECT registry.sweep_name, lock.pid, lock.granted,
       activity.application_name, activity.client_addr,
       activity.backend_start, activity.state, activity.state_change,
       activity.wait_event_type, activity.wait_event
FROM platform.sweep_registry AS registry
JOIN pg_catalog.pg_locks AS lock
  ON lock.classid = ((registry.lock_key >> 32) & 4294967295)::oid
 AND lock.objid = (registry.lock_key & 4294967295)::oid
 AND lock.objsubid = 1
 AND lock.database = (SELECT oid FROM pg_catalog.pg_database
                      WHERE datname = current_database())
LEFT JOIN pg_catalog.pg_stat_activity AS activity ON activity.pid = lock.pid
WHERE registry.sweep_name = 'outbox-relay'
  AND lock.locktype = 'advisory';

SELECT count(*) AS claimed,
       count(*) FILTER (WHERE claim_expires_at < CURRENT_TIMESTAMP) AS expired,
       min(claim_expires_at) AS earliest_expiry
FROM outbox.outbox_event WHERE state = 'CLAIMED';
```

Only `granted = true` identifies a current holder. The query matches the registry's signed 64-bit key, current database and single-bigint advisory-lock form; do not invent keys or use a separate relay lock. A missing holder may simply be between ticks. Correlate repeated observations with owner success, batch spans, pending/claimed rows and pod identity.

## Diagnose Before Restarting

- A live holder with progressing spans/publications is slow, not stuck. Check broker confirms, database/pool waits and the [backlog guide](outbox-backlog.md). Preserve grading's reserved capacity.
- A disabled reservation needs release-owner approval through the audited runtime control; do not issue an ad hoc registry update. Complete missing assembly before enabling it.
- For an apparently orphaned session, map backend PID, backend start, application identity and client address to the exact pod/connection. An idle state alone does not prove an orphan. Check network partitions and pool/worker health with the runtime owner.
- No holder plus no successful ticks suggests missing scheduling, readiness, role or connection health; adding another scheduler or bypassing the shared `SweepLock` is not a remedy.

## Safe Recovery and Expected Window

Capture restricted diagnostic evidence first. Prefer graceful restart of the identified worker through the deployment controller, allowing in-flight work and connection cleanup under the [release procedure](../outbox-release.md). Do not restart every replica or kill unrelated database sessions. If a confirmed orphan cannot be closed through its owner, the database incident procedure may terminate that exact backend after rechecking PID and backend start; termination is a privileged operator decision, not an automatic runbook command. The lock releases when its owning transaction/session ends.

The design uses a 30-second claim lease. With healthy, enabled scheduling, an abandoned claim becomes eligible after its stored expiry and is reclaimed on the next outbox reclamation pass: lease duration + deployed reclamation interval + scheduler/load delay from claiming. For example, a confirmed 30-second reclamation interval implies approximately up to 60 seconds plus delay. Use actual `claim_expires_at` and the deployed outbox cadence as authoritative. The existing 30-second `stale-claim-reclaim` reservation belongs to grading, not proof of outbox scheduling. Current production outbox reclamation wiring/cadence is absent; the runtime owner must establish it before any bounded recovery promise. A stalled/disabled reclaimer or database outage also extends recovery.

Reclamation changes only expired CLAIMED rows to PENDING, clears ownership and makes them due without increasing attempt count. It must run through its transactional adapter, not manual bulk updates. Relay selection and broker recovery then determine delivery time. Lock loss/crash can still produce duplicate publication; consumers must retain their durable guards. Never assume advisory locking supplies exactly-once broker delivery.

## Verify Recovery

Confirm an eligible worker acquires the shared lock, completes owner work, releases it, and advances successful ticks/batch spans. Verify expired claims decline, reclaimed counters advance when applicable, and pending age/publication recover without duplicate business effects. Record the exact deployment action, lock/pod mapping, observed lease/reclaim timing and outcome. Escalate to the runtime owner if recovery exceeds the observed configured window; do not reset leases, delete rows or disable the guard.
