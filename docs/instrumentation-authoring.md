# Instrumentation Authoring Guide

This guide is the implementation contract for feature and slice authors. Use
the shared-kernel ports from `org.meldtech.platform.shared.kernel.observability`;
vendor telemetry APIs belong only in `platform.infra`.

## Observe a Slice

Construct one immutable `RequestTelemetry.RequestMetadata` descriptor for the
slice and wrap the complete deferred reactive publisher with
`RequestTelemetry.observe(...)`. The observed boundary starts before policy
evaluation and ends after the response publisher terminates.

```java
private static final RequestTelemetry.RequestMetadata TELEMETRY =
        new RequestTelemetry.RequestMetadata(
                "assessment",
                "publishAssessment",
                RequestTelemetry.Audience.WORKFORCE,
                RequestTelemetry.Operation.WRITE,
                RequestTelemetry.RouteClass.STANDARD);

Mono<AssessmentResponse> publish(Command command) {
    Mono<AssessmentResponse> request =
            Mono.defer(() -> policy.authorize(command)
                    .then(handler.handle(command))
                    .map(mapper::toResponse));
    return Mono.from(requestTelemetry.observe(TELEMETRY, request));
}
```

Do not call `block()`, subscribe inside the slice, or assemble work before the
deferred boundary. Use a registered, constant module and `<verbNoun>` slice
name. Choose the audience, read/write operation, and route class from their
closed enums; these are policy and telemetry contracts, not request input.

The adapter supplies the span, terminal outcome, duration, query counter,
correlation identifier, actor/tenant context, and request-completion log. A
slice must not duplicate these fields or start a second root span.

## Record a Business Event

Select only a `BusinessEventCode` from the six-event MVP enumeration. Record
through `BusinessEventRecorder` after the state-changing transaction commits,
and keep the recording in the reactive chain.

```java
return repository.save(assessment)
        .flatMap(saved ->
                Mono.from(businessEventRecorder.record(
                                BusinessEvent.occurred(
                                        BusinessEventCode.RESULT_PUBLISHED,
                                        clock.now())))
                        .thenReturn(saved));
```

PIN validation uses `BusinessEvent.pinValidation(occurredAt, outcome)` because
its bounded outcome is mandatory. The `eventCode` must align with the audit
event type, but recording a metric never substitutes for the required
in-transaction audit write. Telemetry failure is swallowed by the adapter and
must not change the business result.

Do not register a Micrometer meter in feature code, invent an event-code
string, or emit the same state transition from an HTTP filter. Filters do not
know whether a domain transition committed and can double count retries or
idempotent replays.

## Logging

Use a static SLF4J logger and stable, constant operational messages. The
request adapter automatically supplies `correlationId`, trace identity,
runtime role, module, slice, actor/tenant context where applicable, duration,
query count, terminal outcome, and a stable error code.

Author code may add only fields already allowed by `docs/logging-contract.md`.
Never pass a domain object, request or response body, throwable message, SQL,
provider payload, email, personal name, PIN, OTP, token, credential, answer,
or arbitrary map to a logger. Do not place a tenant or correlation identifier
in a metric label.

## Tests

Use `ObservabilityTestFixture` when a focused unit or slice test needs
in-memory spans, metrics, and captured log events. Test the behavior owned by
the slice:

- exactly one `<module>.<verbNoun>` span per subscription;
- correct parent and safe attributes for success, denial, error, timeout, and
  cancellation paths that the slice supports;
- the business metric increments once after commit and not on rollback;
- the response, problem detail, log, span, and exemplar use the same
  correlation identifier where those surfaces participate;
- query count meets the declared route budget; and
- attempted secret or personal-data emission is absent or rejected.

Run the narrow slice test first, then the baseline gates:

```bash
./gradlew test --tests 'fully.qualified.SliceTest'
./gradlew businessEventCompletenessGate metricCardinalityGate queryBudgetGate telemetrySchemaGate
./gradlew conformanceTest
```

CI stage 4 runs architecture conformance, business-event completeness, and
the telemetry schema gate. Stage 5 runs unit/coverage and cardinality checks.
Stage 8 owns integration and propagation checks. Stage 10 runs the blocking
secret-leak scan, including captured operational logs.

## Review Checklist

1. Does one registered descriptor wrap the entire deferred reactive boundary,
   including policy evaluation and response mapping?
2. Are module, slice, audience, operation, route class, and outcomes closed
   values rather than caller-provided strings?
3. Are Micrometer, OpenTelemetry, Logback, exporter, encoder, and registry
   types absent from `domain` and `slice` packages?
4. Are log messages constant and all fields representable by the typed schema,
   with no domain object or throwable passed to SLF4J?
5. Is a state-change event selected from the six-code contract, aligned with
   its audit event, and recorded only after commit?
6. Are metric labels declared, secret-safe, bounded, and free of tenant and
   correlation identifiers?
7. Does R2DBC work run under the request query counter, meet its declared
   budget, and exclude SQL text and parameters from telemetry?
8. Do errors use an allowlisted `errorCode`, preserve correlation, and exclude
   exception messages from public and telemetry fields?
9. Do scheduler, outbox, broker, Redis, and provider boundaries use approved
   propagation adapters without blocking or thread-local business state?
10. Do tests cover the relevant terminal paths, redaction, cardinality, query
    budget, and correlation joins while audit remains a separate obligation?
