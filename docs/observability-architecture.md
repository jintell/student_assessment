# Observability Architecture

Status: normative clean-architecture decision for `FEAT-OBS-001`.

Telemetry follows the dependency rule: business and policy code describe
facts through application-owned contracts; infrastructure translates those
facts into vendor signals. The business model does not know how a metric,
span, log, exporter, or collector is implemented.

## Layer Responsibilities

| Layer | Responsibilities | Prohibited dependency |
|---|---|---|
| `shared.kernel.observability` | Framework-free `BusinessEvent`, closed event and outcome enums, `BusinessEventRecorder`, and `RequestTelemetry` contracts. Uses Java values, kernel identifiers, `Instant`, and Reactive Streams `Publisher`. | Micrometer, OpenTelemetry, Logback, exporters, encoders, registries, or arbitrary key/value maps. |
| Feature `domain` | Produces domain facts and chooses the applicable closed business-event code. | Vendor telemetry APIs and infrastructure adapters. |
| Feature `slice` | Brackets the full reactive use case with `RequestTelemetry`; records a completed domain fact through `BusinessEventRecorder`; may use SLF4J only for stable operational messages. | Micrometer, OpenTelemetry, Logback implementation types, observations, meters, spans, registries, encoders, and exporters. |
| `platform.infra.observability` | Implements ports; owns Micrometer/OpenTelemetry configuration, meters, spans, JSON encoding, redaction, export queues, health, and Spring wiring. | Capability-owned business decisions and state transitions. |

Dependencies point inward: infrastructure depends on kernel contracts, while
the kernel and feature business code do not depend on infrastructure.
`BusinessEventRecorder` is a driven port; `MicrometerBusinessEventRecorder` is
its adapter. `RequestTelemetry` is the reactive request-boundary port;
`OpenTelemetryRequestTelemetry` is its adapter.

Neither port accepts a vendor context, arbitrary labels, an extension map, or
an `Object` payload. This keeps privacy, cardinality, lifecycle, and failure
behavior centrally enforceable.

## Why Business Events Use a Port

An HTTP filter knows that a request arrived and which transport result was
returned. It does not know whether a transaction committed, an idempotent
replay returned an existing result, a retry occurred, or a domain transition
was legitimately absent. Recording `EXAM_STARTED` or `RESULT_PUBLISHED` from a
filter would therefore count attempts rather than completed business facts
and can double count retries and replays.

The owning application path records the event after commit because it knows
the semantic transition and its audit-aligned event code. The port preserves
that meaning while hiding the metric registry and ensuring telemetry failure
cannot alter the business result. The adapter maps the closed event code to a
stable metric name and bounded labels.

HTTP filters remain the correct place for transport-level request spans,
method/route classification, correlation setup, latency, and status-family
metrics. They do not infer domain events.

## Reactive Request Boundary

`RequestTelemetry.observe(metadata, publisher)` wraps the entire deferred
slice publisher. The adapter starts one slice span at subscription, restores
the propagated actor/correlation/trace context, installs the request-local
query counter, and records the terminal outcome on completion, error, or
cancellation. Feature code does not subscribe, block, or manage thread-local
telemetry state.

The metadata is closed and low-cardinality: module, slice, audience,
read/write operation, and route class. This lets infrastructure derive spans,
request logs, and bounded technical metrics without learning business rules.

## Enforced Boundary

`TelemetryBoundaryConformanceTests` imports compiled production classes and
fails with `OBS_TELEMETRY_BOUNDARY_VIOLATED` when a class in a `domain` or
`slice` package directly depends on:

- any `io.micrometer.*` type;
- any `io.opentelemetry.*` type; or
- an implementation in `platform.infra.observability`.

It also rejects any additional implementation of `BusinessEventRecorder`
outside the approved Micrometer adapter. Negative fixtures prove a slice with
a direct vendor metric and a domain class with a direct tracer are blocked.
The rule is part of `conformanceTest`, which is blocking in CI stage 4.

Run it locally with:

```bash
./gradlew conformanceTest --tests 'org.meldtech.platform.conformance.TelemetryBoundaryConformanceTests'
```

New telemetry needs are satisfied by extending a stable kernel contract only
when the semantics are genuinely cross-cutting, then implementing the vendor
translation in `platform.infra`. A feature must not bypass the boundary with a
local adapter, service locator, static registry, reflection, or an untyped
payload.
