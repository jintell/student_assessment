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
