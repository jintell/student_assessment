# Failed Outbox Rows

Owner: Platform Ops and the owning event feature. Any FAILED row is a proposed P2 signal under TASK-PLAT4-OBS-001; installation is pending, not an existing baseline alert.

## Inspect and Classify

Use the authorized metadata-only operator view or approved restricted diagnostic connection. Record event identity, tenant scope, event type, attempt count, classified `last_error`, timestamps and correlation reference in restricted incident evidence. Do not copy payloads, credentials or raw driver exceptions into logs or tickets. Inspect the oldest failures first with bounded pagination; do not scan payloads.

A publication reaches FAILED at eight failed attempts. FAILED is not proof that the consumer did nothing: a lost confirm or database update can follow a successful broker delivery. Classify broker availability/TLS/routing failures, negative confirms and timeouts against broker health and relay traces. An unknown classification requires owner investigation, not repeated resets. Repair the underlying cause and verify a healthy publication before replaying a controlled sample.

## Authorized Redrive

1. Use the FEAT-OPS-002 or FEAT-GRD-005 authorized workflow around `OutboxRedriveCommand.redriveFailed`. The [interface handover](../evidence/FEAT-PLAT-004/P5.1-operator-interface-handover.md) defines ownership; this feature supplies no HTTP endpoint or operator UI. If that workflow, audit wiring or production relay is unavailable, stop and escalate rather than issuing manual state updates.
2. Supply the original tenant/event identity, authorized actor, incident reason and stable request ID. Verify tenant and event ownership before invocation. The database requeue and audit must participate in the caller's reactive transaction.
3. Only a matching FAILED row becomes PENDING. The command resets attempts and retry time and clears stale claim/publication/failure metadata. Preserve event identity and payload; do not create a new event to bypass deduplication. `NOT_ELIGIBLE` means inspect the current state, not force an update.
4. A redrive re-checks the durable consumer guard on delivery before any business effect. The FAILED-row requeue command itself does **not** pre-check that guard; the consumer must still be deployed and use the guard. The separate dead-letter command also probes it before republishing. A prior processed event must be acknowledged as a duplicate without repeating its effect. Never delete or reset guard records to force recovery.
5. Verify `platform.OUTBOX_REDRIVE_COMPLETED.v1` with actor, reason, request ID and outcome, then observe publication and consumer outcome. Redrive is audited because it deliberately restarts delivery after terminal failure and can affect business state. A successful requeue is not a successful business effect. Do not assume a stable request ID alone makes the entire workflow exactly-once.

## Verify and Close

Start with one event, confirm the expected effect or guarded duplicate, then use approved bounded batches. On an ambiguous response, check current row state, audit evidence and the durable guard before retrying. Stop if the cause recurs or audit evidence is missing. Require fresh FAILED counts returning to zero, no new failures and healthy pending age/throughput. Retain restricted before/after metadata and audit references. Never delete failed rows, purge queues or detach partitions to make counts green.
