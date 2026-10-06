# FEAT-OBS-001 Phase 9 Monitoring and Operations Evidence

## P9.1 - MVP Business-Event Metrics

Verdict: **PASS FOR FOUNDATION REGISTRATION AND RECORDING**

`MicrometerBusinessEventRecorder` is the runtime adapter for the kernel-owned
`BusinessEventRecorder` port. Construction registers the complete closed
`BusinessEventCode` enumeration, and recording a typed event increments its
corresponding counter:

| Event code | Metric | Labels |
|---|---|---|
| `EXAM_STARTED` | `exam_started_total` | none |
| `EXAM_FINISHED` | `exam_finished_total` | none |
| `PIN_VALIDATION` | `pin_validation_total` | closed `outcome` |
| `RESULT_PUBLISHED` | `result_published_total` | none |
| `CORRECTION_APPLIED` | `correction_applied_total` | none |
| `PROVISIONAL_FEEDBACK_RELEASED` | `provisional_feedback_released_total` | none |

`MicrometerBusinessEventRecorderTest.recordsEveryBusinessEventThroughTheKernelPort`
constructs each typed domain fact, records it through the port, and asserts
that all six metrics increment exactly once. The build-time completeness gate
also rejects an omitted MVP event, an extra unapproved event, or a mismatched
metric name.

`BusinessEvent.eventCode` is the single stable code carried to the metric and
the structured-log `eventCode`; the log-field contract requires the owning
capability to use that same code as its audit event type. There is no second
observability-only event-name mapping. `FEAT-AUD-001` and each business
capability remain responsible for their audit catalogue and for invoking the
port only after the corresponding domain transition commits.

This repository is still a foundation implementation and contains no exam,
result, or grading use-case transition. The evidence therefore confirms the
live registered adapter and all six increment paths without claiming those
future capability flows already exist.

Evidence:

- `BusinessEventCode`
- `BusinessEvent`
- `MicrometerBusinessEventRecorder`
- `MicrometerBusinessEventRecorderTest`
- `BusinessEventCompletenessGate`
- `docs/evidence/FEAT-OBS-001/P7.3-business-event-completeness-result.json`

## P9.2 - Running Log-Field Conformance

Verdict: **PASS**

The runtime logging capture was regenerated on 2026-10-06. Its JSONL event
contains every section 16.1 always-present field:

```text
timestamp, level, logger, message, correlationId, traceId, spanId,
role, module, slice
```

`StructuredJsonLogEncoderTest.emitsEveryAlwaysPresentFieldAndNoUndeclaredField`
also encodes the minimal and fully populated typed event. The minimal event
contains exactly the ten fields above; the complete event contains only the
declared conditional actor, tenant, business-event, error, duration,
query-count, and structured-stack fields in addition. No extension map or
arbitrary field path exists.

Authenticated events accept `actorId` only through the kernel `ActorId`
value. Its closed syntax rejects email addresses, display names, overlong
values, and the reserved system spelling. The running capture is
unauthenticated and correctly omits both actor fields; the full encoder proof
uses opaque `operator-123` and verifies the paired `actorType`/`actorId`
invariant.

Evidence command (PASS on 2026-10-06):

```bash
./gradlew operationalLogCaptureTest test \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  --tests 'org.meldtech.platform.platform.infra.observability.StructuredJsonLogEncoderTest'
```

The regenerated runtime line is available at
`build/reports/operational-logs/reference.jsonl`; build output is intentionally
not committed. The durable schema and safety proofs are the test sources,
`docs/architecture/log-field-contract.md`, and this record.

## P9.3 - End-to-End Correlation Diagnosability

Verdict: **PASS - PHASE 0 EXIT CONTRIBUTION MET**

The diagnostic join has two executable limbs:

1. `CorrelationIdLifecycleTest` proves one validated identifier appears in the
   response header, RFC 9457 problem, MDC-backed log, and trace attribute. The
   emitted metric exemplar carries that trace's `trace_id` and `span_id`, so
   the operator path is metric exemplar -> trace -> `correlationId` ->
   protected log. Invalid inbound text is replaced and reaches none of those
   surfaces.
2. `ObservabilityPropagationIntegrationTest` proves the same application
   correlation and W3C trace context cross the HTTP root, Reactor scheduler,
   PostgreSQL transaction, outbox row, relay batch, RabbitMQ header, and
   consumer span. It uses real PostgreSQL and RabbitMQ containers and includes
   both scheduler-hop and relay-batch cases.

`correlationId` is globally forbidden as a metric label by the registered
cardinality contract. The exemplar carries only trace/span identity; the
linked trace supplies the protected high-cardinality correlation attribute.
This preserves the four-surface join without creating one time series per
request.

Evidence command (PASS on 2026-10-06):

```bash
./gradlew test \
  --tests 'org.meldtech.platform.shared.infra.web.CorrelationIdLifecycleTest' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.outbox.ObservabilityPropagationIntegrationTest'
```

The result completes the operations confirmation referenced by
`P8.7-phase-0-exit-criterion.md` and meets the plan section 10 correlation
exit criterion for this feature.

## P9.4 - Self-Observability and Collector-Outage Visibility

Verdict: **PASS**

The live `ObservabilityHealthMetrics` bean registers the complete closed set:
export attempts, successes and timeouts; dropped spans, logs, and metric
points; redaction rejections; and per-signal queue depth/capacity. All labels
come from closed enums and the metrics remain in the local `MeterRegistry`
even when the OTLP collector path is unavailable.

`ObservabilityHealthIndicator` adds an independent Actuator health component.
The first timeout or export-failure drop marks the affected trace, metric, or
log signal `DEGRADED`; the next successful export for that signal clears it.
The component never reports `DOWN` or `OUT_OF_SERVICE`, so telemetry failure
does not fail liveness, readiness, or a request. Health-detail disclosure
continues to follow Actuator authorization policy.

Each export worker records the local failure immediately when its first
attempt completes unsuccessfully, bounded by the configured hard timeout.
Periodic metrics make that attempt on the configured export interval; traces
and logs attempt as soon as a queued batch is available. The outage is
therefore visible locally by the first failed interval/attempt without relying
on the failed collector to report itself.

Focused evidence (PASS on 2026-10-06):

- `ObservabilityHealthMetricsTest` proves the closed set and forced-failure
  increments;
- `ObservabilityHealthIndicatorTest` proves `UP -> DEGRADED -> UP` around a
  failed then successful export;
- `OtlpCollectorStoppedIntegrationTest` stops a real OTLP endpoint, completes
  100 request-path offers within the latency bound, holds the queue bound, and
  exposes positive local drop metrics.

```bash
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.observability.ObservabilityHealthMetricsTest' \
  --tests 'org.meldtech.platform.platform.infra.observability.ObservabilityHealthIndicatorTest' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.observability.OtlpCollectorStoppedIntegrationTest'
```

## P9.5 - Inbound Gap Handover and Receipt

Verdict: **DISCHARGED TO FEAT-OPS-004 INTAKE**

`docs/evidence/FEAT-OBS-001/P9.5-inbound-gap-discharge.md` confirms receipt of
all four versioned gap records into the `FEAT-OPS-004` intake boundary. The
register preserves every P1/P2 severity, first action, panel request, bounded
label rule, and closure test. The previously task-list-only outbox gap is now
published as `docs/defects/TASK-PLAT4-OBS-001.md`.

Receipt closes this feature's handover obligation only. Each record remains
open until `FEAT-OPS-004` installs, routes, renders, and exercises its
definitions for launch condition `L5`; outbox metric emission also remains
owned by `FEAT-PLAT-004 P9.1`.

## P9.6 - Self-Health Alert Proposals

Verdict: **PUBLISHED FOR FEAT-OPS-004**

`docs/evidence/FEAT-OBS-001/P9.6-self-health-alert-proposals.md` defines two
P2 intake records: sustained telemetry-export failure rate and sustained
redaction-rejection rate. The export alert's first action is exactly to check
the collector and confirm no request-path impact. The rejection alert directs
the responder to find the new emission path without weakening redaction or
exposing the rejected value.

Final thresholds, routing, deployment, and exercises remain `FEAT-OPS-004`
work under launch condition `L5`. The proposals require an independent local
registry/Actuator read so a broken OTLP path cannot hide its own alert input.

## P9.7 - Correlation Diagnosis Runbook

Verdict: **PUBLISHED**

`docs/runbook-correlation-diagnosis.md` gives operators both navigation
directions: client response -> exact protected log -> trace -> metric exemplar,
and alert exemplar -> trace -> correlation identifier -> log -> client-visible
stable error. It covers scheduler hops, relay/consumer boundaries, linked new
traces, query-budget escalation, access restrictions, and sanitized closure
evidence. The runbook is the artifact for `FEAT-OPS-002` operator surfaces to
link.

## P9.8 - Collector and Sink Outage Runbook

Verdict: **PUBLISHED**

`docs/runbook-telemetry-collector-outage.md` records the expected local and
remote symptoms, an availability check independent of telemetry, every queue
and drop signal to inspect, the shared-versus-single-signal diagnosis, and the
store -> collector exporter/processor -> receiver/identity -> application
verification recovery order. It explicitly records loss instead of claiming
that expired or dropped in-memory telemetry can be replayed.

## P9.9 - Log Retention and Sampling Separation

Verdict: **PASS**

`config/observability/log-sink-policy.json` and
`docs/evidence/FEAT-OBS-001/P9.9-log-retention-sampling.md` record the
operational log store's 30-day retention and explicit retain-all baseline
sampling policy. The record contrasts that best-effort path with the audit
store's separate retention classes, access boundary, transactional guarantee,
and absolute prohibition on sampling or dropping. It also states the current
scaffold boundary: the audit contract exists, while `FEAT-AUD-001` still owns
its store implementation.
