# Distributed Tracing Contract

Status: normative tracing interface for `FEAT-OBS-001`.

Sources: architecture section 16.3 (`ARC-OBS-003`), the approved `P0.8`
observability contract, the `P3.5` collector contract, and the registered
slice-span contract.

## Propagation and Lifecycle

W3C Trace Context (`traceparent` and `tracestate`) is the sole distributed
trace carrier. `correlationId` is carried separately and is the mandatory
application-level join attribute on root and slice spans. Span creation and
context propagation happen for every request regardless of the later export
or tail-sampling decision.

An HTTP server, broker consumer, or scheduler span is the parent of one slice
span per invocation. A reactive span closes exactly once when its publisher
completes, errors, times out, or is cancelled. Outbox rows and broker messages
carry only the allowlisted W3C context and correlation identifier.

Consumers continue a valid context whose originating event is within the
configured maximum continuation age. A valid but stale context becomes a
link from a new root. Missing, malformed, or unverifiable context starts a new
root and never copies the untrusted carrier into telemetry.

## Span Names and Inventory

| Span class | Name contract | Kind and parentage | Safe content |
|---|---|---|---|
| HTTP server | `http.server` | `SERVER`; extracted remote parent or root | HTTP method, registered route class, correlation identifier, and authenticated actor context where present. |
| Slice handler | `<module>.<verbNoun>` | `INTERNAL`; child of request, consumer, or scheduler | Registered module, slice, audience, actor context, operation, outcome, and stable error code. |
| Database statement | Closed operation/table statement name | `CLIENT`; child of active slice | Statement operation and table identity only; no SQL text or parameters. |
| Redis command | Standard semantic command name | `CLIENT`; child of active slice | Closed command and safe server attributes; no key or value. |
| Broker publish | Standard messaging publish name | `PRODUCER`; child of transaction/outbox work | Registered destination class and safe messaging attributes. |
| Broker consume | Registered consumer operation | `CONSUMER`; continued, linked, or new context | Registered destination class and safe messaging attributes. |
| Outbox relay batch | Registered relay batch operation | `INTERNAL` or producer parent | Bounded batch outcome; never row payload. |
| External provider call | Registered provider operation class | `CLIENT`; child of active slice | Safe provider operation and bounded outcome; no payload or credential. |

Span names are low-cardinality contracts. They never contain a tenant,
resource identifier, correlation identifier, raw URI, queue-generated value,
or exception text.

## Attributes

| Attribute | Presence | Contract |
|---|---|---|
| `correlationId` | Every root and slice span | Canonical propagated opaque join key. |
| `module` | Slice spans | Closed module identifier. |
| `slice` | Slice spans | Registered `<verbNoun>` identifier. |
| `audience` | Slice spans | Closed request audience. |
| `actorType`, `actorId` | Authenticated operations | Immutable actor context; safe opaque actor identifier. |
| `tenantId` | Tenant-scoped operations | Canonical tenant identifier; absent only for explicitly platform-scoped work. |
| `operation` | Slice spans | `read`, `create`, `update`, `transition`, or `delete`. |
| `outcome` | On completion | Closed outcome such as success, client/server error, or cancellation. |
| `errorCode` | Known failures | Stable allowlisted application code, never exception text. |
| `http.request.method` | HTTP server spans | Valid HTTP method. |
| `routeClass` | HTTP server spans | `exam-entry`, `grading`, or `standard`. |
| `sampling.priority` | Root sampling result | `critical` for exam entry/grading, otherwise `standard`. |

`traceId` and `spanId` are intrinsic OpenTelemetry identity and are projected
into logs; they are not duplicated as custom attributes.

## Prohibited Content

No span name, attribute, event, link attribute, status description, baggage,
or resource attribute may contain:

1. a PIN;
2. an OTP;
3. a token, authorization value, password, key, credential, or secret;
4. answer or answer-key content; or
5. personal data, including email and name.

Request and response bodies, provider payloads, Redis keys and values, SQL
parameters, raw exception messages, and arbitrary domain-object serialization
are also prohibited. Attribute names pass through the central telemetry field
policy; rejected attempts increment `telemetry_redaction_rejection_total`.

## Sampling

Exam-entry and grading routes are marked critical and retained at 100%.
Traces containing an error span or an `error`/`timeout` outcome are retained at
100%. All other complete traces are deterministically retained at 10% by the
collector tail-sampling processor.

The application head ratio is `1.0` for critical and standard route classes.
This sends complete traces to the collector so a late error remains eligible
for mandatory retention. Applying the 10% rule as an application head sampler
is non-conformant. The collector decision window must cover the configured
continuation age, and capacity must prevent eviction at the approved peak.

Sampling changes export volume only. It never changes span creation, W3C
propagation, correlation propagation, query counting, logs, or metrics.

## Verification and Compatibility

Tests must cover exact slice naming, mandatory attributes, terminal outcomes,
one span per reactive invocation, HTTP and broker parentage, fresh/stale/
invalid continuation, correlation equality across response/log/span/exemplar,
and prohibited-field rejection. Run `./gradlew test --tests
'org.meldtech.platform.platform.infra.observability.HybridRouteSamplerTest'
--tests
'org.meldtech.platform.platform.infra.observability.TraceContextContinuationTest'
--tests
'org.meldtech.platform.platform.infra.observability.SpanAttributeRedactorTest'`
for focused baseline verification.

Changing a published span name, mandatory attribute, propagation rule, or
sampling policy requires Security and Platform Ops review.
