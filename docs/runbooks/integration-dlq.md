# Integration Dead-Letter Recovery

Scope: `integration.dlq`, bound to `integration.dlx`. Owner: Platform Ops with the event consumer owner. Queue depth ≥ 1 and unhandled-version dead-letters are separate proposed P2 alerts under TASK-PLAT4-OBS-001, not installed baseline guarantees.

## Triage Without Losing Messages

1. Record environment, broker/vhost, release, queue ready and unacknowledged counts, oldest-message evidence and exporter freshness. An empty ready count while messages are unacknowledged is not an empty DLQ. Preserve evidence in the restricted incident record.
2. Use an approved inspection tool that does not acknowledge/remove messages while sampling. Inspect message ID, `event_type`, `dead_letter_reason` and `correlation_id`; inspect broker death headers where present. Do not dump bodies into tickets or shared logs. Payload access requires separate authorization.
3. Classify `UNHANDLED_EVENT_VERSION` separately from poison/schema or transient failures. Missing/unknown reason or event identity requires owner investigation, not bulk replay. Confirm tenant and consumer ownership using the authorized workflow, not an assumed message header.

## Unhandled Event Version

The remedy is to deploy a compatible consumer, **not to redrive into the same unsupported consumer**. Compare the event version with `contracts/events/consumers.yaml`, registered handled versions and the actual deployed consumer release. Have the feature owner implement/test the missing version, pass contract compatibility checks, and deploy it through the release process. Do not relabel an event version, rewrite its body, mark it processed or suppress the distinct alert.

Once support is deployed and verified, the owner may authorize a controlled drain of retained messages through that supported consumer. This subsequent drain is recovery after the fix, not the fix itself. Stop immediately if messages dead-letter again.

## Controlled Drain

- Repair the diagnosed cause first; for poison data, obtain an explicit feature-owner disposition rather than editing queue payloads or repeatedly retrying.
- Use the authorized FEAT-OPS-002/FEAT-GRD-005 workflow around `OutboxRedriveCommand.redriveDeadLetter`, preserving original tenant/event identity and supplying actor, reason and stable request ID. No outbox-owned HTTP endpoint is provided. If the workflow, eligibility policy, guard probe, gateway or audit wiring is unavailable, retain messages and escalate.
- The command probes the durable consumer guard first. `ALREADY_PROCESSED` skips republishing; it does not call the gateway or automatically acknowledge the retained DLQ message. Any eventual removal requires the approved audited disposition workflow. `NOT_ELIGIBLE` also leaves the message for investigation. An eligible event is republished and acknowledged through the gateway; verify that the deployed gateway confirms durable publication before acknowledging the source.
- The target consumer re-checks its guard before the business effect. Keep guard records intact. Record `platform.OUTBOX_REDRIVE_COMPLETED.v1` and its outcome. Broker publication/acknowledgement and database audit are not one atomic transaction: an audit failure after broker activity is ambiguous. Reconcile broker state, audit records and the guard before retrying; do not assume exactly-once replay.
- Start with one message, verify its consumer outcome, then drain bounded batches within approved worker/consumer capacity. Do not purge a queue, acknowledge an unhandled version as success, disable deduplication or create a separate uncontrolled replay consumer.

## Completion Evidence

Require fresh ready **and** unacknowledged counts of zero, no repeated dead-letter ingress over the agreed observation window, verified consumer effects or guarded duplicates, and complete audit/disposition references. Keep version deployment evidence for unhandled-version incidents. Cross-check [FAILED rows](outbox-failed-rows.md) and [pending backlog](outbox-backlog.md); emptying the DLQ alone does not establish recovery.
