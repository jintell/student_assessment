# Stalled Sweep

Use this runbook when `SweepStale` or `AutoSubmitSweepStale` fires, or when
`sweep_last_success_timestamp_seconds` stops advancing.

## Establish Scope

1. Record environment, sweep name, alert start, configured interval, and
   owning feature from `platform.sweep_registry`.
2. Compare `sweep_tick_total` outcomes, `sweep_lock_acquire_total`, duration,
   processed rows, and worker saturation for the same window.
3. Confirm whether the registry row is enabled. A disabled row is a control
   change, not a lock failure.

```sql
SELECT sweep_name, owning_feature, default_interval_ms, enabled, updated_at
FROM platform.sweep_registry
WHERE sweep_name = :sweep_name;
```

## Identify the Lock Holder

Run with the approved read-only operations role. This query supports signed
64-bit registry keys and identifies the current advisory-lock backend:

```sql
SELECT registry.sweep_name,
       lock.pid,
       activity.application_name,
       activity.client_addr,
       activity.state,
       activity.backend_start,
       activity.state_change,
       activity.wait_event_type,
       activity.wait_event,
       lock.granted
FROM platform.sweep_registry AS registry
JOIN pg_catalog.pg_locks AS lock
  ON lock.classid = ((registry.lock_key >> 32) & 4294967295)::oid
 AND lock.objid = (registry.lock_key & 4294967295)::oid
LEFT JOIN pg_catalog.pg_stat_activity AS activity
  ON activity.pid = lock.pid
WHERE registry.sweep_name = :sweep_name
  AND lock.locktype = 'advisory';
```

## Diagnose

### Live Replica Holds the Lock

- Match `pid`, client address, and application identity to a ready worker pod.
- If duration and row counters advance, the sweep is slow, not stalled. Follow
  the owning feature's backlog procedure and preserve the current transaction.
- If the batch exceeds its declared timeout or reports lock loss, capture logs
  and traces, then roll back only the affected worker image if required.

### Orphaned Session

- Treat a backend whose pod no longer exists, or an idle session left after a
  network partition, as orphaned only after Platform Ops confirms the mapping.
- Prefer restoring/closing the owning connection through the deployment or
  pool. Use `pg_terminate_backend(pid)` only under the database incident
  procedure after confirming it is the exact advisory-lock backend.
- Never terminate an unrelated transaction by client address alone. The lock
  releases when the PostgreSQL session ends; the next tick can then acquire it.

### Registry Row Disabled

- Verify the corresponding audited `platform.SWEEP_REGISTRY_CHANGED.v1` event and change
  reference. An unexplained disable is a security/operations incident.
- Re-enable only through the audited registry-change path. Do not issue an
  unaudited ad hoc update from a general application role.

### Genuine Volume Spike

- A live holder, increasing duration, increasing batch rows, and a growing
  feature backlog indicate load rather than singleton failure.
- Check worker queue, database pool, CPU, and statement-timeout saturation.
  Follow the owning feature's capacity/backlog runbook; do not add a second
  runner or bypass the advisory lock.

## Recovery Verification

Confirm one worker records `outcome=success`, last-success age returns below
three intervals, no concurrent-sweep alert fires, and the owning feature's
backlog declines. Retain the lock query, pod mapping, action, and recovery time
in the incident record.
