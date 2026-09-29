# Phase 9 Monitoring and Operations Evidence

## P9.1 - Problem Responses Emitted by Code

Verdict: **PASS**

`MicrometerProblemDetailMetrics` registers
`problem_detail_emitted_total{code}` lazily from the closed error catalogue.
`ProblemDetailMapper` calls it once after selecting and validating the final
response definition, including when the final definition is the generic
internal response. Metric failures are isolated and cannot alter an error
response.

`MicrometerProblemDetailMetricsTest.recordsEveryMappedResponseByCode` maps two
validation failures through the real mapper and observes a count of two for
`code="CBT-PLAT-VALIDATION"`. This proves the counter follows emitted mapper
responses rather than only direct metric calls.

Evidence command (passed 2026-09-28):

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.kernel.error.MicrometerProblemDetailMetricsTest'
```

## P9.2 - Unmapped Problem Defect Signal

Verdict: **PASS**

`problem_detail_unmapped_total{reason="catalogue_miss"}` increments whenever
an exception has no declared exception-to-code mapping or a mapping names a
catalogue code that does not exist. The client still receives the fixed
`CBT-PLAT-INTERNAL` response, whose emitted counter increments separately.

The mapper previously selected the internal code directly for an unmapped
exception, which produced a safe response but skipped the defect signal. P9.2
corrected that path: mapping absence is now explicit, increments the fallback
metric, and then selects the non-disclosing internal definition.

`MicrometerProblemDetailMetricsTest.recordsAnUnmappedExceptionAsADefectSignal`
passes an unregistered exception through the real mapper and asserts one
unmapped increment plus one emitted internal response. The signal is
actionable: any non-zero rate means an unanticipated failure class reached the
client boundary and requires diagnosis and an intentional catalogue mapping,
not suppression as counter noise.

Evidence:

- `ProblemDetailMapper.map`
- `ProblemDetailMapperTest.unmappedFailureUsesTheGenericNonDisclosingEntry`
- `MicrometerProblemDetailMetricsTest.recordsAnUnmappedExceptionAsADefectSignal`

## P9.3 - Idempotency Outcomes and Store Availability

Verdict: **PASS**

`IdempotencyMetrics` registers `idempotency_replay_total{outcome}` for the
closed outcomes `reserved`, `replay`, and `unavailable`. An unavailable outcome
also increments the untagged `idempotency_store_unavailable_total`. Metrics are
recorded immediately after the port returns and before the filter selects
execute, replay, or fail-closed behavior; telemetry exceptions cannot change
that behavior.

`IdempotencyMetricsTest.recordsEveryBoundedOutcomeAndStoreUnavailability`
records one instance of every outcome and asserts all three tagged counters
plus the dedicated availability counter. Tenant, actor, route, client key,
exception, and response values are deliberately absent from metric labels.

Evidence command (passed 2026-09-28):

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.IdempotencyMetricsTest'
```

## P9.4 - Correlation Diagnosability Join

Verdict: **PASS**

The resolved correlation identifier is the application-level join key.
`CorrelationIdLifecycleTest` proves the same value reaches the response header,
MDC log event, `ProblemDetail`, and a traced observation for valid, invalid,
and absent request headers. The trace carries `correlationId` as a diagnostic
attribute.

`ProblemDetailMetricExemplarTest` uses the production Prometheus registry and
`MicrometerProblemDetailMetrics` to prove a problem counter sample carries the
current sampled `trace_id` and `span_id` exemplar. An operator follows the
counter exemplar to that trace and reads its correlation attribute, then uses
the correlation identifier to retrieve the matching logs and client-visible
problem. Correlation identifiers are not metric labels, avoiding an unbounded
cardinality defect.

`ReactorContextPropagationConfigurationTest.preservesCarrierAndLoggingContextAcrossSchedulerHops`
proves the correlation, actor, and tenant carrier survives bounded-elastic and
parallel scheduler hops and is cleared afterward.

Evidence command (passed 2026-09-28):

```bash
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.kernel.error.ProblemDetailMetricExemplarTest' \
  --tests 'org.meldtech.platform.shared.infra.web.CorrelationIdLifecycleTest' \
  --tests 'org.meldtech.platform.shared.infra.web.ReactorContextPropagationConfigurationTest'
```

## P9.5 - Alert and Dashboard Ownership Gap

`TASK-PLAT3-OBS-001` is raised to `FEAT-OBS-001` and `FEAT-OPS-004` in
`docs/defects/TASK-PLAT3-OBS-001.md`. It proposes the sustained-rate P2 alert,
the required first action, the unmapped-rate panel, and the top emitted error
codes panel. The record remains open until those owners register, route,
render, and exercise the definitions.

## P9.6 - Unmapped-Exception Operations Runbook

`docs/runbook-problem-detail-unmapped.md` explains the generic client response,
uses metric exemplar to trace to correlation identifier and protected logs,
separates internal-path fixes from intentional public mappings, and defines the
allowlist, leak, generated-artifact, compatibility, release, and closure steps.

## P9.7 - Redis-Unavailability Operations Runbook

`docs/runbook-redis-idempotency-unavailable.md` defines the expected counter
and response symptoms, proof that the refused chain produced no business,
outbox, or audit side effect, and a candidate-path synthetic check. It forbids
disabling the filter or substituting an in-memory store and requires
post-recovery reserve/replay evidence.

## P9.8 - Kernel Log-Field Conformance

Verdict: **PASS FOR FEATURE-OWNED FIELDS**

The application log pattern reserves `correlationId`, `actorType`, `actorId`,
`tenantId`, and `errorCode` on every line with an explicit `none` value when a
field is not applicable. This feature supplies their values as follows:

| Field | Source and presence |
|---|---|
| `correlationId` | Resolved canonical ULID in Reactor context for every request |
| `actorType`, `actorId` | `ActorContext` on authenticated requests and system work |
| `tenantId` | `ActorContext` on tenant-scoped work; absent for explicit platform scope |
| `errorCode` | Final mapper outcome on the failure log; identical to the client `ProblemDetail.code` |

`ReactorContextPropagationConfigurationTest` now asserts all four request
context fields on both bounded-elastic and parallel scheduler threads and
asserts their removal after termination. The error handler temporarily adds
the mapped `errorCode`, logs only the stable code and exception class (never
the exception message), and restores the prior MDC value in `finally` so a
reused thread cannot inherit the failure context.

`ActorId` now accepts only a bounded opaque identifier alphabet and rejects
email-shaped values, display names containing whitespace, controls, and values
longer than 128 characters. `ActorContextTest.actorIdsRejectPersonalDisplayValues`
enforces that boundary. The caller still owns the semantic requirement to use
an identity-provider subject or platform identifier rather than a person's
name.

Evidence command (passed 2026-09-28):

```bash
./gradlew test \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  --tests 'org.meldtech.platform.shared.infra.web.ReactorContextPropagationConfigurationTest' \
  --tests 'org.meldtech.platform.platform.infra.kernel.error.ProblemDetailWebExceptionHandlerTest'
```

`FEAT-OBS-001` retains ownership of JSON serialization, the remaining section
16.1 fields, exporter configuration, and the logging-wide secret-field scan;
this verdict covers only the values and failure log owned by this feature.
