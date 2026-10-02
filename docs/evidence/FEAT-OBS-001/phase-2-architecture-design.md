# FEAT-OBS-001 Phase 2 Architecture and Design

Date: 2026-10-02
Architecture baseline: `arch-v1.4`
Scope: observability tasks `P2.1`-`P2.17`

This document fixes the design contracts that Phase 3 infrastructure and
Phase 4 implementation must follow. It does not claim those later phases are
implemented.

## P2.1 Telemetry Layering

Telemetry follows the dependency rule: policy and business code describe
facts through stable application-owned contracts; infrastructure translates
those facts into vendor signals.

| Layer | Permitted types and responsibility | Forbidden dependency |
|---|---|---|
| `shared.kernel` | `BusinessEvent`, its closed event-code enumeration, `BusinessEventRecorder`, and `RequestTelemetry`. Contracts use Java primitives, kernel identifiers, `Instant`, and Reactive Streams `Publisher` only. | Micrometer, OpenTelemetry, Logback, Spring, exporter, or encoder types. |
| Feature `domain` | Creates domain facts and selects a business-event code. | Logging facade and every vendor telemetry API. |
| Feature `slice` | Uses SLF4J for stable operational messages and invokes `BusinessEventRecorder` or `RequestTelemetry`. | Direct Micrometer, OpenTelemetry, Logback, encoder, registry, meter, span, or observation imports. |
| `platform.infra` | Implements the two ports; owns meters, observations, exporters, JSON encoding, redaction, and runtime wiring. | Business decisions or capability-owned state transitions. |

`BusinessEvent` is an immutable typed value. `BusinessEventRecorder` records a
completed domain fact without exposing a meter. `RequestTelemetry` brackets a
slice publisher and accepts closed metadata (`module`, `slice`, `audience`,
`operation`); its implementation owns lifecycle outcomes and cancellation.
Neither port accepts arbitrary maps, tag keys, or vendor contexts.

The blocking conformance rule is `TelemetryDependencyRules`: `domain` must
not depend on SLF4J or any telemetry framework, and `slice` may depend only on
SLF4J plus the two kernel ports. Vendor package references are permitted only
below `org.meldtech.platform.platform.infra`. The rule scans production
bytecode, includes annotation and generic-signature references, and has
deliberate negative fixtures for a Micrometer import, an OpenTelemetry
annotation, and a Logback type.

The current conformance-reference slice's direct `ObservationRegistry` use is
an input to `P2.17`; Phase 4 replaces it with `RequestTelemetry` rather than
making it a permanent exception.

## P2.2 Typed Structured-Log Schema

`StructuredLogEvent` is the only value accepted by the production encoder.
It is a final Java record with no extension map, varargs key/value API, or
`Object`-typed component. Optional components use `Optional`, so `null` is
rejected at construction and omitted in JSON. Value objects validate their
closed syntax before an event can exist.

| Component | Java type | Presence | Validation/source |
|---|---|---|---|
| `timestamp` | `Instant` | Always | UTC on serialization. |
| `level` | `LogLevel` enum | Always | Closed logging-level set. |
| `logger` | `LoggerName` | Always | Static logger name; no control characters. |
| `message` | `LogMessage` | Always | Registered stable message; no interpolation or line breaks. |
| `correlationId` | `CorrelationId` | Always | Reactor request/work context. |
| `traceId` | `TraceId` | Always | Active or synthetic root trace; 32 lowercase hex digits. |
| `spanId` | `SpanId` | Always | Active or synthetic root span; 16 lowercase hex digits. |
| `role` | `RuntimeRole` enum | Always | `API`, `WORKER`, or `PINDIST`. |
| `module` | `ModuleId` | Always | Registered module catalogue value. |
| `slice` | `SliceId` | Always | Registered `<verbNoun>` slice value. |
| `actorType` | `Optional<ActorType>` | Authenticated work | Kernel actor context. |
| `actorId` | `Optional<SafeActorId>` | Authenticated work | Opaque internal identifier or approved hash, never name/email. |
| `tenantId` | `Optional<TenantId>` | Tenant-scoped work | Kernel tenant context. |
| `eventCode` | `Optional<BusinessEventCode>` | Business event | Same stable code as its audit event type. |
| `errorCode` | `Optional<ErrorCode>` | Failure | Same allowlisted code as `ProblemDetail`. |
| `durationMs` | `Optional<Long>` | Request completion | Non-negative elapsed milliseconds. |
| `dbQueryCount` | `Optional<Long>` | Request completion | Non-negative context-scoped count. |
| `error` | `Optional<StructuredError>` | Approved exception | Contains only `stack`; see `P2.3`. |

Constructor invariants require the two actor fields together, require
`tenantId` for tenant-scoped metadata, require request fields together, and
require `errorCode` when `error` is present. These are schema conditions, not
encoder guesses. Unknown fields cannot be represented and are rejected by
the JSON-schema baseline test, making the section 16.1 allowlist assertable.

## P2.3 JSON Encoder

The non-local console appender accepts only `StructuredLogEvent`, applies the
redactor, and serializes with a configured JSON library to UTF-8. It writes
exactly one compact JSON object followed by one `\n` for each accepted event.
No pattern-layout appender, exception printer, throwable proxy, or raw
fallback appender is active in `api`, `worker`, `pindist`, or `production`.

`timestamp` is rendered as an RFC 3339 UTC instant ending in `Z`; level and
closed values use their canonical strings; optional fields are omitted rather
than written as `null`. JSON escaping handles carriage returns, line feeds,
tabs, quotes, and control characters, so an input cannot create another
physical log line.

`StructuredError.stack` is a bounded array of typed frames containing only
class, method, file, and line. It excludes exception messages, suppressed
payloads, local variables, and arbitrary `toString()` output. Cause depth and
frame count have configured hard maxima, and truncation is represented by a
boolean in that same structured value. Encoder or redactor failure drops the
event and increments the self-observability rejection counter; it never emits
the original event through a less restrictive path.

Contract tests split captured bytes on physical newlines and require each
non-empty line to parse as exactly one object matching the closed schema.
They cover embedded newlines and nested exceptions and assert the absence of
pattern-layout output in every non-local profile.

## P2.4 Redacting Serializer

The serializer delegates every candidate field path to the single kernel
`SecretFieldPattern`; it carries no private regex or copied secret-word list.
The kernel matcher normalizes camel case and `.`, `_`, and `-` separators,
then matches the whole normalized segment `pin`, `otp`, `token`, `secret`,
`password`, `key`, or `authorization`. Substrings such as `monkey` do not
match, while `apiKey` and `request.authorization` do.

The permitted-key exception is a closed exact-leaf-name catalogue owned by
the kernel. Its initial and only entry is canonical `policy_key`, accepting
the Java spelling `policyKey` and a nested path such as
`retention.policy_key`. It is justified by architecture section 16.2 as the
reviewed retention-policy label. It does not permit `api_key`,
`encryption_key`, `privateKey`, a parent path containing another secret
segment, or any future `*_key` without architecture and security review.

On a match the serializer writes the exact JSON string `[REDACTED]` and does
not call an accessor, serializer, or `toString()` for the original value.
Names and email addresses are omitted by the schema rather than redacted.
The same field-decision API is used for log fields, span attributes, metric
labels, errors, and event-payload checks. Tests cover every spelling and
separator, the `policy_key` positive cases, near misses, nested paths, and a
value whose accessor throws to prove a rejected value is never evaluated.

`TASK-OBS1-DEFECT-003` remains an implementation blocker for `P4.5`: this
design authorizes no observability-local workaround before the kernel owner
adds the enumerated allowance and its proof.

## P2.5 Build-Time Telemetry Safety Check

`TelemetrySchemaGate` runs after compilation in blocking CI stages 4 and 10.
It consumes compiled bytecode and the generated log, span, and metric
registries rather than searching source text.

The gate applies four checks:

1. Starting at `StructuredLogEvent`, `BusinessEvent`, and both telemetry-port
   method signatures, recursively inspect every reachable record component,
   field, accessor, collection element, and nested value type. A name rejected
   by `SecretFieldPattern` fails the build unless it is the kernel-approved
   exact permitted-key entry.
2. Reject `Object`, arbitrary maps, unbounded key/value collections, and a
   domain value type at any telemetry boundary. Only the closed safe scalar
   and enum catalogue is allowed, so a domain object cannot reach an encoder
   through generic serialization.
3. Inspect the span-attribute and metric-definition registries. Every key must
   be declared, pass the same secret-name decision, have a closed value source,
   and, for metrics, have a series budget. Dynamic attribute or label keys are
   prohibited.
4. Apply an Error Prone call-site rule: `domain` cannot log; a slice may call
   only a constant-message SLF4J overload and cannot pass a domain object,
   throwable, structured argument, marker, or interpolated value. Rich data
   flows through the typed ports.

Compile-fail fixtures prove rejection for a domain `pin` component reachable
from a business event, `authorizationToken` used as a span attribute, a
metric label named `candidate_email`, a parameterized logger call containing
a domain object, and an undeclared dynamic key. Positive fixtures cover safe
opaque identifiers and the approved `policy_key`. A failing fixture is kept
in the repository and compiled by the gate test so the check is proven to
block, not merely to pass the current model.

This extends `ARC-OBS-002` consistently across logs, span attributes, and
metric labels and records the closure design for `TASK-OBS1-DEFECT-004`.

## P2.6 Candidate-Identifier Hasher

Telemetry represents a candidate, only where an approved diagnostic use case
requires it, as `h1.<base64url>` produced by HMAC-SHA-256. The input is the
canonical bytes of the internal `CandidateId`, prefixed with the domain
separator `cbt:candidate-telemetry:v1\0`. Email and name are not accepted by
the hasher API and are never emitted in raw, normalized, encrypted, or hashed
form.

The HMAC secret is at least 256 random bits, unique to one environment, and
loaded from the configured secret-manager/config-tree reference into a
`SecretKey`; the secret value cannot be supplied as an ordinary application
property. The application never logs its path, value, derived material, or
initialization exception detail. A missing, short, or unreadable secret fails
startup. Deployment attestation compares non-secret secret-manager version
metadata and rejects reuse across environments. The full 256-bit digest is
encoded without padding; it is stable within one environment for incident
joining and deliberately different in every other environment.

Rotation changes the output namespace. Operators may retain the previous
secret only for the approved log-search overlap, after which it is destroyed;
new events use only the active version and no event carries a secret version
or key identifier. Tests use fixed non-production secrets to prove
determinism within an environment, inequality across environments, canonical
input handling, and that neither input identity nor secret appears in output
or failure text.

The per-environment HMAC secret is the plan's environment-specific salt; it
is never shared between development, test, staging, and production, so the
same candidate hash cannot be joined across those environments.

## P2.7 Metric Names and Cardinality Budgets

Approved section 16.2 names remain stable. New names use lowercase
`snake_case`, a base-unit suffix (`_seconds`, `_bytes`, or `_ratio`) where
applicable, and `_total` only for monotonic counters. A name describes the
measured fact, not its implementation or vendor. Labels use `lowerCamelCase`
contract names and values from enums or reviewed catalogues only.

Every `MetricDefinition` declares its instrument kind, unit, permitted label
keys and values, and `maxSeries`. Registration fails when that computed bound
is absent or exceeded; recording an unknown key or value is rejected and
counted. `tenantId` and `correlationId` are globally forbidden labels. They
remain join fields in protected logs/spans, and correlation may appear in a
single exemplar without becoming a time-series dimension.

The table below is the required budget disposition from `P1.8`. Symbols are
not estimates: each resolves at build/startup from its named approved closed
catalogue, and the generated registry records the resulting integer.

| Metric definition(s) | Permitted labels | Maximum series |
|---|---|---:|
| `exam_started_total`, `exam_finished_total`, `result_published_total`, `correction_applied_total`, `provisional_feedback_released_total` | None | 1 each |
| `pin_validation_total` | `outcome` from `PinValidationOutcome` | `count(PinValidationOutcome)` |
| `http_server_requests` | Registered route, its declared audience, method, status class, bounded outcome | Sum of each route's declared Cartesian product; raw URI is impossible |
| `db_query_duration` | Registered slice and `Operation` | `count(SliceCatalogue) * count(Operation)` |
| `db_pool_acquire_duration`, `db_pool_pending`, `db_pool_max_size` | `workload` from `Workload` | `count(Workload)` each |
| `redis_command_duration`, `redis_errors_total` | `command`, and bounded `outcome`/`reason` where declared | Product of each definition's closed enums |
| `rabbitmq_queue_depth`, `queue_consumer_utilisation`, `notification_dlq_depth` | `queue` from deployment queue catalogue | `count(QueueCatalogue)` each |
| `concurrent_active_attempts` | Active `session`, plus one platform aggregate | `activeSessionCeiling + 1`; a closed session series is removed |
| `sse_active_streams` | `replica` from the live role replica catalogue | Sum of configured role replica ceilings; stale series expire |
| `retention_policy_version_active`, `retention_disposition_policy_version` | `policy_key` from reviewed retention-policy catalogue | `count(RetentionPolicyCatalogue)` each; version is a value, never a label |
| `audit_chain_head_lock_wait_seconds`, `audit_chain_shard_skew` | Integer `shard` in configured `[0,N)` | `N` each |
| `db_context_install_failure_total`, `db_role_assumption_total` | `role` from the closed database-role catalogue | `count(DatabaseRole)` each |
| Metrics labelled by status, category, outcome, reason, channel, stream, result, mismatch type, subject, control, workload, role, or shard | Only the definition's named enums/catalogues | Exact Cartesian product declared by that individual definition |
| All remaining section 16.2 metrics without a documented dimension | None | 1 each |

`audit_root_chain_seq` is an aggregate value with no tenant label; an affected
tenant is diagnosed through authorized logs/spans. Session and replica are
the only time-varying identifiers retained because their simultaneous domains
have approved ceilings. Exception text, SQL, cache keys, actor/candidate IDs,
resource IDs, provider IDs, request content, and arbitrary destination names
are prohibited labels. A snapshot test covers every registered metric so a
new metric cannot bypass a budget declaration.

## P2.8 Business-Event Recording Path

`BusinessEventCode` is the closed kernel enumeration below. Each constant
owns one stable audit-aligned `eventCode` and one canonical metric name.

| Enumeration | `eventCode` / audit type | Metric |
|---|---|---|
| `EXAM_STARTED` | `EXAM_STARTED` | `exam_started_total` |
| `EXAM_FINISHED` | `EXAM_FINISHED` | `exam_finished_total` |
| `PIN_VALIDATION` | `PIN_VALIDATION` | `pin_validation_total` |
| `RESULT_PUBLISHED` | `RESULT_PUBLISHED` | `result_published_total` |
| `CORRECTION_APPLIED` | `CORRECTION_APPLIED` | `correction_applied_total` |
| `PROVISIONAL_FEEDBACK_RELEASED` | `PROVISIONAL_FEEDBACK_RELEASED` | `provisional_feedback_released_total` |

`BusinessEvent` contains the code, occurrence `Instant`, and an optional
code-specific closed outcome. It has no map, payload, actor, tenant, or
arbitrary label. The capability emits its audit/outbox fact inside the
business transaction and invokes `BusinessEventRecorder.record(event)` only
after successful commit. The returned `Publisher<Void>` is composed into the
reactive flow; cancellation or business rollback does not count the event.

The `platform.infra` Micrometer adapter resolves the static definition,
increments its counter with only the permitted outcome, attaches the current
trace/correlation exemplar, and emits a typed structured log carrying the
same `eventCode`. An exporter failure cannot fail or roll back business work;
it updates bounded self-observability instead.

`BusinessEventCompletenessTest` compares the enum, metric definitions, audit
event-code mapping, and approved six-row baseline bidirectionally. Missing,
extra, duplicate, renamed, or outcome-incompatible entries fail the build.
`SYNC_OUTCOME` and `PAYMENT_OUTCOME` are asserted absent until their Post-MVP
features and an approved contract change exist.

## P2.9 Trace Sampling

Route classification happens once at the HTTP, broker-consumer, or scheduler
root. Registered exam-entry and grading route classes receive the immutable
sampling priority `critical` and are head-selected at 100%. Unknown route
classes fail registration rather than silently joining a lower-priority
bucket. Children inherit the root decision and cannot resample themselves.

All other roots are recorded and exported to the collector with priority
`standard`; the application does not make the final 10% choice. This is
necessary because an error, timeout, or cancellation is known only when the
trace completes. The collector waits for completion and evaluates policies
in this order:

1. retain every trace with an error-marked span or outcome `ERROR`/`TIMEOUT`;
2. retain every `critical` exam-entry or grading trace;
3. retain a deterministic 10% of remaining complete traces; and
4. drop the rest after publishing collector drop/decision telemetry.

The SDK therefore creates and propagates every span and correlation value
even when the collector later drops the trace. Sampling never changes logs,
request query counting, metrics, or business behavior. Policy tests cover
success, denial, error, timeout, cancellation, and a late consumer error
after the outbox/broker boundary.

Without a reachable tail-sampling collector configured with those ordered
policies, architecture section 16.3's "100% of errors" rule is unmet. There
is no in-process fallback that can provide it, so collector provisioning and
policy evidence are mandatory deployment prerequisites under
`TASK-OBS1-DEFECT-007` and the unresolved backend ownership gap.

## P2.10 Async Trace-Context Propagation

When the owning transaction appends an outbox event, the writer captures the
active W3C `traceparent` and optional `tracestate` alongside the validated
`correlation_id`. Those values are first-class outbox columns, not event
payload properties. The relay copies them unchanged to lower-case AMQP
headers and never accepts trace headers from the payload. No baggage is
persisted or forwarded.

The relay batch is its own operational span. It links to each valid producer
context rather than choosing one event as the batch parent; each broker
publish span links to its event's producer context. Publication retry reuses
the stored context and does not create a new causal identity.

Before invoking a consumer, the inbound adapter validates W3C syntax, rejects
all-zero IDs and unsupported versions, validates the correlation ULID, and
compares `occurred_at` with the configured maximum continuation age:

| Carrier state | Consumer decision |
|---|---|
| Valid and within continuation age | Install it as the remote parent; the broker-consume and slice spans are children in the producer trace. |
| Valid but older than the maximum | Start a new root and attach one OTel span link to the producer context. |
| Missing or invalid | Start a new root with the carried valid correlation ID; record only a bounded rejection reason and no untrusted header value. |

The adapter installs the selected span context, `CorrelationId`, tenant, and
system actor in Reactor `Context` before policy or handler code runs and
removes them when the publisher terminates. Result and notification outbox
writes repeat the same capture, so submission -> outbox -> relay -> broker ->
grading consumer -> result -> notification remains navigable through parent
edges or explicit links. Tests cover valid continuation, expiry/linking,
invalid/missing headers, retry, batch fan-out, scheduler hops, and equality of
the correlation ID at every boundary. This is the handoff contract to
`FEAT-PLAT-004`; it consumes the carrier already implemented there.

## P2.11 Per-Request Query Telemetry

`RequestTelemetry` creates one `QueryCounter` for each request/work root and
places that instance in Reactor `Context`. The counter is thread-safe because
a reactive chain may move schedulers, but it is never static, thread-local,
or keyed in a process-wide map. Nested slice publishers reuse the same root
counter.

The workload `ConnectionFactory` is decorated once with a
`ContextQueryExecutionListener`. At subscription to each actual R2DBC
`Statement.execute()` publisher, the listener obtains the counter and the
registered slice from Reactor context, increments exactly once for that
database round trip, and times termination. Re-subscription counts another
execution; constructing a statement does not. A batch records the actual
statements reported by the driver listener. Internal pool validation does not
count as a slice query.

On request termination, including error or cancellation, the root telemetry
emits the final non-negative value as `dbQueryCount` on the typed request log
event. Each statement duration records `db_query_duration` using only the
registered slice and closed operation labels. SQL text, bind values,
connection IDs, tenant IDs, and exception details never become metric data.

The counter runs whether or not any span will be retained and is never
calculated from span export. Tests interleave two requests on the same thread,
move one request across schedulers, use repeated subscriptions and batches,
and terminate by success, error, timeout, and cancellation to prove isolation
and accurate final counts against the section 15.2 budgets.

## P2.12 Export Pipeline

Traces, metrics, and logs use OTLP over gRPC with TLS to configured collector
endpoints. One required base endpoint may be overridden per signal; the
protocol is fixed, so no signal silently switches to HTTP/protobuf or a vendor
exporter. Structured logs also retain the `ARC-OBS-001` compact JSON stdout
sink from `P2.3`.

Each signal has an independent bounded FIFO queue and dedicated exporter
worker pool. Request and Reactor scheduler threads only offer an immutable
typed item whose schema cannot carry arbitrary objects; they never perform
DNS, connection setup, serialization, network I/O, retry sleep, flush, or
collector health checks. The worker applies redaction before serialization.
On a full queue, the offer atomically evicts the oldest item, admits the
newest, and increments the signal-specific drop counter. One saturated signal
therefore cannot consume another signal's capacity.

Workers batch up to the configured size and apply one hard deadline to the
entire gRPC attempt. Timeout, unavailable collector, serialization rejection,
or terminal response drops only that batch/item and updates self-telemetry.
Retries use bounded exponential backoff on the exporter pool and may not live
past the item's maximum age or create a second unbounded queue. Shutdown makes
one bounded best-effort flush and then terminates; application shutdown is not
held beyond the same hard deadline.

The logging worker redacts and encodes before writing one stdout line and
constructing the corresponding OTLP record. There is no synchronous fallback
or raw-payload emergency logger. Export self-metrics use fixed in-process
counters read by the metrics exporter and do not enqueue a log about their
own failure, preventing recursion.

Fault tests use unreachable, slow, and non-reading collectors plus queue
saturation. Business publishers must preserve their response and latency
semantics while queue depth stays at its bound and loss is exactly reflected
by counters. This implements the degradation contract from `P1.9`.

## P2.13 Startup Validation

`ObservabilityProperties` lives in `platform.infra` and has no security- or
correctness-sensitive Java defaults. Every runtime role supplies the same
schema. A validator runs before route/listener registration and fails boot
with setting names and bounded reason codes only; it never prints supplied
values or secret paths.

| Required setting | Valid contract |
|---|---|
| `resource.serviceName`, `resource.environment`, `resource.role` | Non-blank registered service/environment; role is exactly `api`, `worker`, or `pindist`. |
| Trace, metric, and log OTLP endpoints | Absolute gRPC collector URI, no user-info/query/fragment; TLS and configured trust material required outside local development. |
| `export.timeout` | `100ms` through `10s`. |
| Per-signal `queue.capacity` | `256` through `65536`; power of two. |
| Per-signal `batch.size` | `1` through `min(8192, queue.capacity / 2)`. |
| Per-signal `itemMaxAge` | `1s` through `5m`; greater than export timeout. |
| `sampling.examEntryHeadRatio`, `sampling.gradingHeadRatio` | Present and exactly `1.0`. |
| `sampling.standardExportRatio` | Present and exactly `1.0`, preserving traces for the tail decision. |
| `sampling.standardTailRatio` | Present and exactly `0.10`; deployment evidence must match collector policy. |
| `trace.maxContinuationAge` | `1m` through `24h`. |
| `redaction.permittedKeyFields` | Present, non-empty, and exactly the kernel-approved catalogue; initially `policy_key`. Unknown additions fail. |
| `candidateHash.secretReference` | Present external config-tree/secret-manager reference; resolved secret is at least 32 bytes and environment-specific. No inline secret property exists. |
| Metric catalogues and ceilings | Present positive bounds for active sessions, replicas, queues, roles, policies, shards, routes, and slices; generated series counts must not exceed each definition. |

The validator also proves the non-local logger is the JSON appender, exporter
executors are dedicated, signal queues are distinct, business-event and
metric registries are complete, and the runtime role is represented as an
OTel resource attribute. Local and test launches use explicit profile values,
not hidden production defaults.

Negative startup tests omit each property and cover every boundary just below
and above its range. In particular, an absent collector endpoint, missing or
silently defaulted sampling ratio, empty redaction allowlist, and absent hash
secret reference must prevent the context from starting. This is the
observability limb of `ARC-OBS-004`/`ARC-VERIFY-018` and preserves
`TASK-OBS1-DEFECT-006`'s corrected ownership.

## P2.14 Self-Observability

Exporter health is maintained in allocation-bounded in-process counters and
gauges, independent for each signal. The set is closed:

| Signal | Type and labels | Meaning / bound |
|---|---|---|
| `telemetry_export_attempt_total` | Counter; `signal` | Batches attempted; three series. |
| `telemetry_export_success_total` | Counter; `signal` | Batches acknowledged; three series. Success rate is success divided by attempts over the same window. |
| `telemetry_export_timeout_total` | Counter; `signal` | Attempts ended by the hard deadline; three series. |
| `telemetry_span_dropped_total` | Counter; closed `reason` | Span loss by queue overflow, expiry, rejection, or export failure; four series. |
| `telemetry_log_event_dropped_total` | Counter; closed `reason` | Log-event loss by the same four reasons; four series. |
| `telemetry_metric_point_dropped_total` | Counter; closed `reason` | Metric-point loss by the same four reasons; four series. |
| `telemetry_redaction_rejection_total` | Counter; `surface` and closed `reason` | Rejected log/span/metric/event fields; exact product of the two enums. |
| `telemetry_export_queue_depth` | Gauge; `signal` | Current items, always in `[0, configured capacity]`; three series. |
| `telemetry_export_queue_capacity` | Gauge; `signal` | Configured bound for interpreting depth; three series. |

No label contains tenant, correlation, endpoint, exception, field value, or
queue item. Counters use saturating/monotonic primitives and the exporter
reads snapshots; their update path never logs or enqueues another telemetry
item. The same snapshot is available to a secured Actuator health detail so a
collector outage does not hide local queue/drop evidence. Telemetry health may
report `DEGRADED`, but it never makes application liveness/readiness fail.

Tests reconcile produced, exported, queued, expired, rejected, and dropped
items under success, saturation, timeout, and serialization failure, and
prove self-signal failure cannot recurse. These signals are the inputs for
the later `FEAT-OPS-004` panels and `P9.6` alert proposals.

## P2.15 Correlation Join

One `CorrelationId` is created at each work root. HTTP accepts exactly one
well-formed `X-Correlation-Id` or generates a ULID and always returns the
validated value. Broker work restores the carried value; a scheduler creates
one at trigger time. A standalone infrastructure event creates a synthetic
work root, so the typed schema never substitutes `none` for its mandatory
join key.

| Surface | Join rule |
|---|---|
| Response header | `X-Correlation-Id` is set before invoking the chain and preserved on success or error. |
| Structured log | Mandatory typed `correlationId` on every event. |
| `ProblemDetail` | Mandatory extension uses the same Reactor-context value; the mapper may generate only when no work root exists. |
| Span | Mandatory high-cardinality `correlationId` attribute on root and slice spans; never placed in the span name. |
| Metric exemplar | Carries trace ID, span ID, and filtered `correlationId` exemplar metadata; it is not a metric label. |
| Outbox/broker | `correlation_id` column and `correlation_id` header preserve the value exactly. |

`RequestContextPropagation` remains the sole Reactor carrier for request
identity. The existing Micrometer `ContextSnapshot` bridge restores that
context around approved scheduler hops, and the async adapter from `P2.10`
reconstructs it before consumer code. Every logger, problem mapper, span
adapter, and exemplar provider reads from that carrier; none generates a
replacement after the root is established.

The `P7.14` acceptance test captures a response, typed completion/error log,
`ProblemDetail`, span, and metric exemplar before and after a scheduler hop
and outbox relay. It asserts byte-for-byte correlation equality and trace
navigation, including denial, error, timeout, cancellation, expired trace
linking, and an invalid inbound header. It also asserts no time-series label
contains the identifier.

## P2.16 Operational Logs Versus Audit Evidence

| Property | Operational log | Audit record |
|---|---|---|
| Purpose | Diagnosis, performance, and incident triage. | Durable evidence of state changes and privileged reads. |
| Write path | Typed redacted event to bounded async stdout/OTLP pipeline. | Audit port inside the same database transaction as the business change. |
| Sink | Collector/log store under the observability service identity. | Dedicated hash-chained audit schema/store under audit database roles. |
| Delivery | Best effort; sampling, queue eviction, and loss are permitted and measured. | No sampling or dropping; audit failure rolls back the protected business operation. |
| Retention | Operational retention policy selected for diagnosis and data minimization. | Independently approved compliance retention/disposition policy with legal hold. |
| Access | Operations/support least privilege. | Separately authorized compliance/audit access, tenant-scoped and itself audited. |
| Content | Fixed operational schema; no business payload or unnecessary personal data. | Approved evidence schema; still excludes secrets and obeys audit-specific controls. |

The two may share opaque correlation and event codes for navigation, but a log
never satisfies an audit obligation and an audit record is never copied to a
logger. Audit writers cannot reference the log exporter, and telemetry
adapters cannot write the audit schema.

The deployment review compares both configurations and fails when they share
a sink/storage target, service identity, retention-policy identifier, export
queue, or deletion control. It also verifies log sampling is explicit, audit
sampling is impossible, audit retention/hold settings are present, and the
collector identity has no audit database grants. Architecture tests enforce
the package dependency separation; integration tests prove telemetry failure
does not roll back business work while audit failure does. This is the
`REQ-AUD-002` separation contract.

## P2.17 Reference Slice and Review Checklist

The conformance-reference slice is the worked pattern. Phase 4 removes its
direct `Observation`, `ObservationRegistry`, and Reactor Micrometer imports.
`Endpoint` instead builds closed request metadata for
`platform.getConformanceReference` (`module=platform`,
`slice=getConformanceReference`, `audience=operator`, `operation=READ`) and
passes the complete deferred authorize -> handle -> response publisher to
`RequestTelemetry.observe(...)`.

The port implementation starts exactly one slice span on subscription, reads
actor/correlation state from Reactor context, seeds the query counter, and
ends on success, denial, rejection, error, timeout, or cancellation. It emits
the typed request-completion log with duration, outcome, error code when
applicable, and final query count. The endpoint retains only its constant
SLF4J operational message and contains no vendor telemetry type. This read
slice emits no business event; a state-changing reference would call
`BusinessEventRecorder` only after commit.

`SliceTest` remains the executable example. It must prove one span with the
registered name and safe attributes, correct parentage, all termination
outcomes, correlation equality, query count, and no telemetry on mere
publisher assembly. A compile/conformance test proves the slice imports only
SLF4J and kernel telemetry ports.

Every later slice instrumentation review answers all of these checks:

1. Does one registered `<module>.<verbNoun>` descriptor wrap the entire
   deferred reactive boundary, including policy evaluation and response?
2. Are module, slice, audience, operation, route class, and possible outcomes
   closed values rather than caller-provided strings?
3. Are there zero Micrometer, OpenTelemetry, Logback, exporter, encoder, or
   registry types outside `platform.infra`?
4. Are log messages constant and are all fields representable by the typed
   schema, with no domain object or throwable passed to SLF4J?
5. Are state-change business events selected from the six-code contract,
   aligned to the audit event code, and recorded only after commit?
6. Are metric labels declared, secret-safe, bounded, and within the metric's
   generated maximum, with no tenant or correlation label?
7. Does R2DBC work run under the context query counter, meet a declared route
   budget, and avoid SQL text/parameters in telemetry?
8. Do errors use the allowlisted `errorCode`, preserve the same correlation
   ID in `ProblemDetail`, and exclude exception messages from public and
   telemetry fields?
9. Do scheduler, outbox, broker, Redis, and provider boundaries propagate or
   link context through the approved adapters without `block()` or thread
   locals?
10. Do success, denial, error, timeout, cancellation, redaction, cardinality,
    and correlation-join tests cover the slice, while audit remains a
    separate in-transaction obligation?

Later documentation task `P10.4` publishes this checklist as the authoring
guide; this section is its approved design source.
