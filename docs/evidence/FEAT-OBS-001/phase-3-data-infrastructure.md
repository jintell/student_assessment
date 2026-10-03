# FEAT-OBS-001 Phase 3 Data and Infrastructure

## P3.1 - Data Impact

`FEAT-OBS-001` makes no schema change: telemetry is exported to the approved
collector and is not persisted in a platform-owned database, cache, or broker
store.

CI stage 12 exclusion: `ci/stage-12-schema-exclusions.txt` records this feature
as code-and-configuration-only. The exclusion does not bypass migration
verification for any migration present in the repository.

## P3.2 - Telemetry Dependencies

The application declares the OpenTelemetry SDK and Micrometer OTLP registry
without component versions. Spring Boot 4.1.1's dependency BOM imports the
OpenTelemetry 1.62.0 and Micrometer 1.17.1 BOMs, so the supported stack owns
their alignment. The R6 domain-purity conformance rule rejects both
`io.opentelemetry` and `io.micrometer` imports from every `domain` package;
vendor telemetry adapters belong in `org.meldtech.platform.platform.infra`.

## P3.3 - Twelve-Factor Configuration Surface

`ObservabilityProperties` defines the configuration schema under
`cbt.observability`; `application.yaml` maps each value to an environment
variable without embedding endpoints, trust material, sampling defaults, queue
defaults, allowlist entries, or a hash salt. The candidate hash setting is a
secret reference only; the secret value has no application property. `P4.26`
will register and validate this schema before runtime adapters start.

## P3.4 - Production Logging Configuration

Every profile except `local` selects Spring Boot's built-in Logstash JSON
encoder for the console. No file appender or pattern configuration is present
in those documents, so the single Boot console appender is the only production
sink. The human-readable MDC pattern is isolated to the explicit `local`
profile. `NonLocalLoggingConfigurationTest` makes this profile split a build
assertion.

## P3.5 - Collector Contract

Platform Ops and the Engineering Lead approved the signed contract at
`ci/dor/FEAT-OBS-001/P3.5-collector-contract.json`. It fixes the staging and
production OTLP/gRPC endpoints, the three SPIFFE workload identities, mTLS
trust and short-lived client-certificate references, store ownership and
retention, and the collector failure boundary.

The collector gateway is required to run the tail-sampling processor. It must
retain every error and every exam-entry or grading trace before applying the
deterministic 10% standard sample. The one-minute continuation limit and
75-second decision wait make late-span handling explicit. Production remains
gated on proving those policies against the deployed collector; tail sampling
is a required stack capability, not an application assumption. This closes
`TASK-OBS1-DEFECT-001` and `TASK-OBS1-DEFECT-007` for `P3.5`.

## P3.6 - In-Memory Test Substrate

`ObservabilityTestFixture` owns an isolated OpenTelemetry SDK with in-memory
span and metric exporters and a scoped Logback appender. It exposes immutable
snapshots and flushes providers before assertions; closing it detaches the
appender and shuts down both providers so state cannot leak between tests.

The conformance-reference `SliceTest` proves a slice test can assert a span,
metric, and log event through the fixture without a collector or network
service. The focused test is part of both the stage 5 unit suite and the stage
7 slice-test selection.

## P3.7 - OTLP Integration Sink

`OtlpGrpcTestSink` is a bounded in-process gRPC stub implementing the OTLP
trace, metric, and log services. `OtlpExportIntegrationTest` drives the real
OpenTelemetry OTLP exporters over HTTP/2 and asserts the deserialized protobuf
requests contain the emitted span, counter, and log body. It uses no vendor
backend and no production endpoint.

The test lives in `integrationTest`, so the existing CI stage 8 entry point
executes it alongside the real-infrastructure integration suite. Its focused
run passed for all three signals and proves wire serialization rather than
only SDK callback behavior.

## P3.8 - Blocking Operational-Log Leak Scan

CI stage 10 now depends on `operationalLogSecretScan` alongside the existing
payload and error-response limbs. A dedicated test captures a non-empty JSONL
operational event, and `OperationalLogLeakScanner` fails closed when captures
or the adversarial marker catalogue are missing or empty. It rejects every
field matched by the kernel `SecretFieldPattern` and every exact synthetic
PIN, OTP, token, password, or answer marker without echoing a leaked value in
its own error.

`operationalLogLeakScannerSelfTest` plants both a forbidden field and a
forbidden value and proves they fail. The complete capture/self-test/scan task
passes locally and is a blocking dependency of `ciStage10`; Phase 7 extends
the capture cases across the implemented redactor and export surfaces.

## P3.9 - Pipeline Assertions

Three named, independently runnable gates are wired into their owning stages:

| CI stage | Gate | Assertion |
|---|---|---|
| 4 | `businessEventCompletenessGate` | The approved DoR remains exactly the six MVP event-code/metric pairs, with sync and payment outcomes explicitly absent. |
| 5 | `metricCardinalityGate` | Every registered foundation metric has a positive maximum-series budget and no `tenantId` or `correlationId` label; the forbidden set matches the signed contract. |
| 8 | `queryBudgetGate` | Every Phase 0 route in the query-budget contract resolves to its compiled `ROUTE_ID`, has one unique entry, and has `maxQueries = fixedOverhead + sliceBudget`. |

The cardinality and query-budget inputs are versioned under
`config/observability`. All three gates pass locally. The stage 8 assertion is
the pipeline proposal for `TASK-OBS1-OBS-001`; Phase 4 supplies live query
counting and `P7.12` turns the declared reference-route budget into the
measured regression proof.
