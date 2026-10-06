# Telemetry Degradation Contract

This contract defines application behavior when the OpenTelemetry collector,
an exporter, or a telemetry store is slow or unreachable. It is normative for
operators, reviewers, and feature authors.

## Core Guarantee

Telemetry export failure degrades observability, never application
availability. A request or background operation succeeds or fails solely for
its business, security, persistence, and messaging reasons. No telemetry
exception, timeout, queue state, or health state may change that outcome.

An unavailable collector does not:

- change an HTTP status or response body;
- roll back or commit a database transaction;
- trigger a business retry, compensation, or idempotency result;
- skip authorization, tenant isolation, validation, or audit work;
- block a Reactor event-loop or request thread on network I/O;
- fail application liveness or readiness;
- make an otherwise healthy replica leave service; or
- permit an unbounded telemetry queue or memory growth.

If any of these occurs, treat it as an application availability defect in
addition to the telemetry incident.

## Failure Boundary

Traces, metrics, and logs have distinct bounded in-memory queues and dedicated
daemon export workers. Request paths offer an item without waiting for network
I/O. Each export batch has a hard deadline.

When a queue is full, the oldest item is dropped and the new item is accepted.
Items older than their configured maximum age expire. Rejected, timed-out, or
failed batches are counted and discarded; they are not retried through
business control flow. Export-worker exceptions are contained and the worker
continues with the next batch.

This design deliberately prefers measured telemetry loss to request failure,
event-loop blocking, or unbounded resource use. Dropped in-memory telemetry is
not reconstructed from application or audit data after recovery.

## Observable Degradation

The local self-observability set remains the source of evidence when the
remote path is unavailable:

- `telemetry_export_attempt_total`, `telemetry_export_success_total`, and
  `telemetry_export_timeout_total` by `signal`;
- signal-specific drop counters by `reason` (`overflow`, `expiry`,
  `rejection`, or `export-failure`);
- `telemetry_export_queue_depth` and
  `telemetry_export_queue_capacity`; and
- `telemetry_redaction_rejection_total` for hygiene-boundary failures.

The secured local `observability` health component reports `DEGRADED` and the
affected `trace`, `metric`, or `log` signals after a failure newer than the
last success. This component is diagnostic detail, not liveness or readiness.
Remote dashboards can become stale or blank and must not be used alone to
infer application health.

## Startup Versus Runtime

Fail-open export applies only after a valid application configuration has
started. Missing collector endpoints, invalid sampling ratios, absent
redaction allowlists, missing candidate-hash secret references, or invalid
non-local TLS configuration are startup contract violations and must fail
fast. A correctly configured collector that later becomes unreachable invokes
the runtime degradation behavior above.

This distinction prevents an outage from taking down requests without
allowing a deployment to hide a malformed or insecure telemetry setup.

## Operational Response

Use `docs/runbook-telemetry-collector-outage.md`. Confirm liveness, readiness,
request success, and latency through a path independent of the telemetry
backend. Preserve local counter and queue snapshots, restore downstream stores
before collector exporters and receivers, then confirm fresh exports succeed
and queues drain within the configured maximum age.

Do not restart healthy request replicas solely to recover telemetry, enlarge a
queue beyond its reviewed bound, weaken TLS, disable redaction, or claim that
expired/dropped items were recovered. Record the known loss and prove that
application availability remained within its independent bounds.

## Review and Test Obligations

Changes to exporters or instrumentation must retain bounded queues, dedicated
workers, finite deadlines, exception containment, per-signal health, and
request-path independence. Tests cover collector refusal, timeout, queue
saturation, expiry, serialization rejection, and recovery, asserting both the
drop/health evidence and unchanged request behavior.

Focused baseline verification:

```bash
./gradlew test --tests 'org.meldtech.platform.platform.infra.observability.BoundedExportQueueTest'
./gradlew test --tests 'org.meldtech.platform.platform.infra.observability.OtlpExportPipelineTest'
./gradlew test --tests 'org.meldtech.platform.platform.infra.observability.ObservabilityHealthIndicatorTest'
```
