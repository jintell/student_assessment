# Phase 7 Testing Evidence

## P7.5 - Kernel Purity Negative Test

Status: PASS (2026-09-28).

`KernelPurityConformanceTests.rejectsASpringDependencyInTheKernel` imports a conformance-only class in
`shared.kernel.r6fixture` that depends on Spring's `ApplicationContext`. The production rule reports a
`KERNEL-PURITY` violation naming the forbidden Spring type. The normal production-class assertion passes in
the same test class, proving the deliberate import is confined to the negative fixture and is absent from the
shipped kernel.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.KernelPurityConformanceTests'
```

## P7.6 - Decimal Boundary Table

Status: PASS (2026-09-28).

`DecimalTest` verifies the P2.4 table at `0.499999`, exact positive `.5` ties, exact negative `.5` ties,
and values on both sides of zero. It accepts the largest positive and negative `NUMERIC(12,4)` raw
scores (`99999999.9999`) and `NUMERIC(9,6)` percentages (`999.999999`), while rejecting one-digit
integer overflow and excess fractional scale for each convention. The tests exercise the canonical
`Decimal.roundHalfUpToWholeNumber` method consumed by `FEAT-GRD-001`.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.shared.kernel.decimal.DecimalTest'
```

## P7.7 - Exact Decimal Negative Tests

Status: PASS (2026-09-28).

`R6DomainPurityTests.rejectsASecondRoundingHelperInTheGradingDomain` applies the production exact-decimal
rule to a conformance-only helper that calls `BigDecimal.setScale` and observes the required failure directing
the caller to `Decimal.roundHalfUpToWholeNumber`. The existing
`rejectsFloatingPointInTheGradingDomain` fixture independently introduces a `double` score and observes the
binary-floating-point failure. Both fixtures live outside production and both positive production scans pass.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.R6DomainPurityTests'
```

## P7.8 - ARC-VERIFY-011 Actor Attribution

Status: PASS (2026-09-28).

`ActorAttributionConformanceTests` scans every mutating `PolicyProtectedRoute`, requires a single colocated
handler, and requires `ActorContext` on its write entry point and write-port targets. Its no-argument `POST`
fixture observes an `ACTOR-CONTEXT` failure. The companion system-actor rule rejects direct construction and
`SystemActor.valueOf`; its free-text fixture observes a `SYSTEM-ACTOR` failure. The production scans pass and
the fixtures remain confined to `conformanceTest`.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.ActorAttributionConformanceTests'
```

## P7.9 - Ambient Time Negative Test

Status: PASS (2026-09-28).

The conformance-only `AmbientTime` fixture calls `Instant.now()` outside the `SystemClock` adapter.
`R6DomainPurityTests.rejectsAnAmbientTimeCallOutsideTheClockAbstraction` applies the production scan and
observes the stable `R6 controlled time violated` failure. The positive scan permits only the named adapter
and remains green.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.R6DomainPurityTests.rejectsAnAmbientTimeCallOutsideTheClockAbstraction'
```

## P7.10 - Correlation Identifier Four-Way Join

Status: PASS (2026-09-28).

`CorrelationIdLifecycleTest` exercises a valid supplied ULID, an invalid value, and an absent header through
the real request filter and Reactor-to-MDC bridge. A valid value is honoured; invalid and absent values use the
generated ULID. For every case, one assertion joins that resolved value across the response header, captured
structured-log MDC, tracing span tag, and mapped `ProblemDetail`. Rejected input appears in none of those
diagnostic surfaces. The suite is included in blocking CI stage 10.

Evidence command:

```bash
./gradlew problemDetailAllowlistTest --tests \
  'org.meldtech.platform.shared.infra.web.CorrelationIdLifecycleTest'
```

## P7.11 - Mapper Failure Tolerance

Status: PASS (2026-09-28).

`ProblemDetailFailureToleranceTest` independently forces a catalogue miss, a missing correlation identifier,
and a real Jackson serialization exception at the web boundary. Every invocation completes without a second
exception, returns the fixed generic status, code, title, detail, correlation identifier and empty extension
set, and increments its expected bounded fallback reason exactly once. The serialized fallback also proves
the original provider-shaped detail is absent. The suite is included in blocking CI stage 10.

Evidence command:

```bash
./gradlew problemDetailAllowlistTest --tests \
  'org.meldtech.platform.platform.infra.kernel.error.ProblemDetailFailureToleranceTest'
```

## P7.12 - Redis Idempotency Integration

Status: PASS (2026-09-28).

`RedisIdempotencyStoreIntegrationTest` runs the production adapter against the pinned Redis 8.8.3
Testcontainer. It reserves a key, reads an initial TTL within the real 24-hour retention window, completes the
reservation, and verifies status, permitted headers, content type and body replay byte for byte. It then sets a
short real Redis TTL on the completed key, waits reactively for server-side expiry, and proves the same request
can reserve again. This test runs in blocking CI stage 8.

Evidence command:

```bash
./gradlew integrationTest --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisIdempotencyStoreIntegrationTest'
```

## P7.13 - Idempotency Route Negative Test

Status: PASS (2026-09-28).

`IdempotencyRouteRuleTests.rejectsDurableRecordsProtectedByRedisHeader` declares a durable-record `POST`
route with the Redis header mechanism and observes the stable `IDEMPOTENCY-ROUTE` failure requiring
`createsDurableRecord=false`. The same class keeps the positive PostgreSQL-unique route and safe read route
green and separately rejects an undeclared state-changing route.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.IdempotencyRouteRuleTests'
```

## P7.14 - Redis-Unavailable Degradation

Status: PASS (2026-09-28).

`RedisUnavailableDegradationIntegrationTest` starts a dedicated pinned Redis Testcontainer, captures its
endpoint, stops the container, and then drives the production adapter and request filter against that dead
endpoint. A Redis-backed generic `POST` returns the catalogue's fixed 409
`CBT-PLAT-IDEMPOTENCY-UNAVAILABLE` problem and never invokes its transition. In the same stopped-container
state, a candidate answer route protected by a named PostgreSQL unique constraint bypasses Redis and executes
normally. No mocked Redis failure participates in either assertion.

Evidence command:

```bash
./gradlew integrationTest --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisUnavailableDegradationIntegrationTest'
```

## P7.15 - Typed Identifier Compile Failure

Status: PASS (2026-09-28).

The `CandidateAsTenant.java` fixture passes a `CandidateId` to a method requiring `TenantId`.
`TypedIdentifierCompileFailureTest` invokes the JDK 21 compiler with the production classes on its classpath
and requires compilation to fail with an incompatible-types diagnostic naming both identifiers. The fixture is
kept under test resources and can never enter the production artifact.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.shared.kernel.identity.TypedIdentifierCompileFailureTest'
```

## P7.16 - Real Context Payload Scheduler Propagation

Status: PASS (2026-09-28).

`ReactorContextPropagationConfigurationTest.preservesCarrierAndLoggingContextAcrossSchedulerHops` installs a
real tenant-bound `ActorContext`, crosses a bounded-elastic `subscribeOn` hop and a parallel `publishOn` hop,
and asserts the complete carrier survives while the same correlation identifier is projected into MDC on both
schedulers and cleared afterward. This is the post-P4.16 extension of the baseline P4.9 proof.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.shared.infra.web.ReactorContextPropagationConfigurationTest.preservesCarrierAndLoggingContextAcrossSchedulerHops'
```

## P7.17 - R1-R8 Adoption Regression

Status: PASS (2026-09-28).

The full `conformanceTest` task completed 45 tests with zero skipped, failed or errored tests after adoption of
the definitive kernel types. Reports are present for every R1 through R8 class, including
`R5TenantQuerySignatureTests`, whose production scan now resolves the real `TenantId`. Kernel-specific actor,
decimal, time and purity rules also ran in the same stage-4 suite.

Evidence command:

```bash
./gradlew conformanceTest
```

## P7.18 - ARC-VERIFY-024 Adoption Regression

Status: PASS (2026-09-28).

The four-test `AdversarialConnectionReuseIntegrationTest` was rerun from scratch against Testcontainers after
the definitive kernel `TenantId` adoption and completed with zero skipped, failed or errored tests. The retained
kernel record is `P7.18-arc-verify-024-adoption-regression.json`.

Launch-condition L9 remains backed by the intact staging artifact
`FEAT-PLAT-002/P7.17-pooled-connection-security-context-adversarial-report.json`, whose registered SHA-256
still matches. Git history proves the real `TenantId` adoption was committed at 10:25 on 2026-09-26 and the
staging evidence at 22:48 that day, so the staging run postdates the adoption and does not represent the
placeholder carrier.

Evidence command:

```bash
./gradlew integrationTest --tests \
  'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest' \
  --rerun-tasks
```

## P7.19 - Error-Response Secret-Leak Baseline

Status: PASS (2026-09-28).

The standalone stage-10 error limb completed 28 tests with zero skipped, failed or errored tests.
`ErrorResponseSecretLeakTest` validates every catalogue entry through the kernel secret-field policy and drives
stack-trace, SQL, provider-diagnostic, authorization-token and password markers through the actual web error
boundary. None appears in a response. The allowlist, construction-site, correlation, reactive-termination,
failure-tolerance and layered fault suites passed in the same blocking task.

Evidence command:

```bash
./gradlew problemDetailAllowlistTest --rerun-tasks
```

## P7.20 - Verification Evidence Register

Status: PASS (2026-09-28).

The section 19.9 register now contains retained, checksummed entries for the seven-layer
`ARC-VERIFY-013` fault-injection report and the blocking `ProblemDetail` allowlist result. Both artifacts carry
their originating JUnit result digest and zero-failure counts. The register also records
`TASK-PLAT3-DEFECT-002`: the architecture has no valid dedicated identifier for shared-kernel context
propagation, so this feature retains its owned evidence without inventing one.

Registered artifacts:

- `P7.20-fault-injection-report.json`
- `P7.20-problem-detail-allowlist-result.json`

## P7.21 - Clean Blocking Pipeline

Status: PASS (2026-09-28).

Stages 4, 8 and 10 ran together from a fresh temporary source copy with no repository-local Gradle or build
state. Stage 4 passed 45 tests, stage 8 passed 29, and stage 10 passed 30 across its error-contract and
tenant-isolation limbs; no test was skipped, failed or errored. The retained run record is
`P7.21-clean-pipeline-run.md`, and the Phase 0 exit-criteria evidence links to it.

## P7.22 - Feature Acceptance Verification

Status: VERIFIED (2026-09-28).

`P7.22-acceptance-verification.md` maps each of the five plan section 8.1 acceptance outcomes to named tasks,
executable assertions and retained artifacts. Every row is verified; the production Redis provisioning
deferral remains explicitly outside the behavioral claims.
