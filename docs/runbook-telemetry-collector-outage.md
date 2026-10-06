# Telemetry Collector or Sink Outage

Owner: Platform Operations

Severity source: `TelemetryExportFailureRateSustained` P2 proposal

Use this runbook when the local observability health component reports
`DEGRADED`, export success falls, timeouts or export-failure drops rise, or a
trace, metric, or log sink is unavailable.

## Expected Symptoms

- `telemetry_export_attempt_total` continues to increase for active signals
  while `telemetry_export_success_total` stalls or grows more slowly.
- `telemetry_export_timeout_total{signal}` and the signal-specific
  `reason="export-failure"` drop counter increase:
  `telemetry_span_dropped_total`, `telemetry_metric_point_dropped_total`, or
  `telemetry_log_event_dropped_total`.
- `telemetry_export_queue_depth{signal}` may approach
  `telemetry_export_queue_capacity{signal}`; overflow or expiry drops may
  follow during a prolonged outage.
- the local Actuator `observability` health component reports `DEGRADED` and
  names the affected signals independently of the failed OTLP route.
- remote dashboards may show stale or missing data. Their silence is not
  evidence that the application is unhealthy.

## Confirm Availability Is Unaffected

1. Check application liveness and readiness. Telemetry degradation must not
   make either fail.
2. Check request success rate and latency through a path independent of the
   affected telemetry backend, such as the ingress/load-balancer health view
   and a bounded synthetic request.
3. Confirm request replicas are serving and Reactor/event-loop saturation has
   not changed. Export work must remain on dedicated workers.
4. Do not restart healthy request replicas solely to recover telemetry and do
   not increase an export queue beyond its reviewed bound as an incident
   workaround.

If requests fail or latency materially changes with the collector stopped,
escalate as an application availability incident in addition to the telemetry
P2; the non-blocking export contract has been violated.

## Diagnose the Boundary

1. Identify affected `signal` values from local health and counters: `trace`,
   `metric`, or `log`.
2. Compare queue depth with capacity and read drop counters by `overflow`,
   `expiry`, `rejection`, and `export-failure`. Preserve a timestamped local
   snapshot before recovery resets the symptom.
3. Verify DNS and network reachability to the configured collector gateway
   without printing credentials or private-key paths.
4. Verify workload identity/SPIFFE SVID freshness, trust bundle validity,
   collector authorization for the runtime role, and TLS handshake health.
5. Check collector gateway health, receiver saturation, tail-sampling
   capacity, exporter backlog, and the target trace/metric/log store.
6. When only one signal fails, follow its receiver/exporter path. When all
   three fail together, prioritize shared gateway, identity, DNS, network, and
   capacity causes.

## Recovery Order

1. Restore the downstream store or remove its capacity/availability fault.
2. Restore collector exporters and processors, including the mandatory tail
   sampler.
3. Restore the collector OTLP/gRPC receiver and confirm mTLS authorization for
   each runtime role.
4. Confirm a fresh export attempt succeeds and the local health component
   returns to `UP` for each affected signal.
5. Confirm queue depth drains within its configured item maximum age and new
   timeout/export-failure/overflow counters stop increasing.
6. Validate one new trace, metric point, and structured log reaches its store.
   Do not claim recovery by replaying expired or dropped in-memory telemetry;
   loss is measured, not reconstructed from application data.
7. Confirm request success and latency remained within their independent
   bounds throughout the event.

## Closure Evidence

Retain the UTC start/end time, affected environments and roles, signal set,
local health transitions, attempt/success/timeout deltas, drop deltas by
reason, peak queue depth/capacity, root cause, recovery action, and request
availability/latency evidence. Record known telemetry loss explicitly.

Never include a credential, certificate/private-key content, candidate data,
answer content, raw payload, SQL parameter, or exception message in incident
evidence.
