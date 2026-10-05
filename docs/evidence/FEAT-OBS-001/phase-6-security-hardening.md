# FEAT-OBS-001 Phase 6 Security and Hardening Evidence

Date: 2026-10-05
Scope: observability tasks `P6.1`-`P6.10`

## P6.1 - Plaintext PIN Adversarial Paths

Status: PASS (2026-10-05).

`TelemetrySchemaGateTest.refusesPlaintextPinAcrossEveryLoggingEscapePath`
attempts all five required paths at the only value accepted by the production
encoder:

| Attempt | Enforced outcome |
|---|---|
| Domain field named `pin` | Rejected by the kernel-owned secret-field policy. |
| Map value | The entire map escape hatch is rejected before a value can be read. |
| Nested object containing `pin` | Recursive schema inspection reaches and rejects the nested field. |
| Exception message | `Throwable` is not an admitted telemetry value; the structured error type contains frames only. |
| Arbitrary `toString()` | `Object` is rejected and the test proves `toString()` was not invoked. |

The assertions require the blocking `ARC-OBS-002` diagnostic. Refusal occurs
before serialization, so the attempted plaintext cannot reach a log sink.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.refusesPlaintextPinAcrossEveryLoggingEscapePath'
```

## P6.2 - OTP, Authentication Token and Password Matrix

Status: PASS (2026-10-05).

`TelemetrySchemaGateTest.refusesOtpAuthenticationTokenAndPasswordAcrossEveryLoggingEscapePath`
repeats the five-path admission test for each prohibited credential category.

| Credential category | Domain field | Map value | Nested object | Exception message | `toString()` |
|---|---|---|---|---|---|
| OTP | Refused | Refused | Refused | Refused | Refused |
| Authentication token | Refused | Refused | Refused | Refused | Refused |
| Password | Refused | Refused | Refused | Refused | Refused |

Field-name attempts are rejected by `SecretFieldPattern`; unbounded maps,
throwables, and objects are rejected by the closed telemetry schema. None of
the attempts requires a committed raw credential value.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.refusesOtpAuthenticationTokenAndPasswordAcrossEveryLoggingEscapePath'
```

## P6.3 - Candidate Personal-Data Hygiene

Status: PASS (2026-10-05).

`TelemetrySchemaGateTest.rejectsCandidateEmailAndNameFromEveryTelemetrySurface`
proves that candidate email and candidate name are rejected as log fields,
span attributes, and metric labels by the shared telemetry field policy.
Neither field exists in `StructuredLogEvent`, `SpanAttributeName`, or the
approved metric contract.

`CandidateIdentifierHasherTest` proves the only approved diagnostic candidate
reference is `h1.<base64url>`, produced by HMAC-SHA-256 over the internal
`CandidateId`. The output contains neither the identifier nor key material,
is stable inside one environment, and differs when the environment secret
changes. The API accepts `CandidateId`, not email or name.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.rejectsCandidateEmailAndNameFromEveryTelemetrySurface'
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.CandidateIdentifierHasherTest'
```

## P6.4 - Answer-Content Exclusion

Status: PASS (2026-10-05).

The answer boundary is closed at three layers:

| Path | Evidence |
|---|---|
| Typed log and span fields | `TelemetrySchemaGateTest.rejectsAnswerContentFromLogsAndSpanAttributes` rejects `answerContent` on both surfaces. |
| R2DBC statement parameter | `TracingConnectionFactoryTest.emitsOnlyTheParsedStatementNameAndNeverReadsABindValue` binds an object whose `toString()` fails if touched. The emitted span contains only `db.select`; SQL text, column names, and parameter values are absent. |
| Serialization failure | `StructuredJsonLogEncoderTest.serializationFailureDoesNotExposeItsAnswerContentMessage` injects a failure carrying answer-shaped detail and asserts the public exception is fixed, cause-free, and contains none of that detail. |

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.rejectsAnswerContentFromLogsAndSpanAttributes' \
  --tests 'org.meldtech.platform.platform.infra.observability.TracingConnectionFactoryTest.emitsOnlyTheParsedStatementNameAndNeverReadsABindValue' \
  --tests 'org.meldtech.platform.platform.infra.observability.StructuredJsonLogEncoderTest.serializationFailureDoesNotExposeItsAnswerContentMessage'
```

## P6.5 - Fail-Closed Redaction

Status: PASS (2026-10-05).

`RedactingJsonSerializer` now treats a null/unknown serialized value and any
runtime serializer failure as a redaction rejection. It emits the fixed
`[REDACTED]` node and increments
`telemetry_redaction_rejection_total{surface="log",reason="serialization-failure"}`;
it never retries through a permissive serializer or emits exception detail.

`RedactingJsonSerializerTest.failsClosedForUnknownCyclicAndFailingValues`
asserts the exact placeholder for an unknown null result, a genuinely cyclic
object passed to Jackson, and an explicit serializer exception. All three
produce a value, so the structured event is retained rather than silently
dropped.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.RedactingJsonSerializerTest.failsClosedForUnknownCyclicAndFailingValues'
```

## P6.6 - Log-Injection Hardening

Status: PASS (2026-10-05).

`StructuredJsonLogEncoderTest.hostileValuesCannotForgeOrBreakEventsAtTheSink`
writes the adversarial cases to a temporary JSONL sink, reads the sink bytes
back, and parses every physical line. A message containing a newline is
refused before the sink exists. A control character, ANSI escape, embedded
JSON fragment, and 65,536-character value each produce exactly one parseable
JSON object whose decoded message equals the input.

The sink contains one physical newline per accepted event and no literal
control or escape byte. This assertion is against the persisted sink output,
not the encoder return value.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.StructuredJsonLogEncoderTest.hostileValuesCannotForgeOrBreakEventsAtTheSink'
```

## P6.7 - Correlation-Identifier Privacy Review

Status: PASS (2026-10-05).

This review reuses and reruns the owning kernel feature's `P6.3` and `P6.4`
evidence rather than creating a second correlation implementation.
`UlidCorrelationIdGenerator` accepts only a controlled clock and
`SecureRandom`; it has no actor, tenant, email, claim, or request-data input.
`CorrelationId` accepts only the canonical 26-character uppercase ULID form,
so it cannot carry arbitrary personal data.

`RequestContextWebFilterTest.replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink`
submits malformed, over-long, control-character, newline, JSON, and ANSI
headers. Each is replaced with a generated canonical ULID before the response
header and MDC-backed log sink are populated. The hostile inbound value is
present in neither sink.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.context.UlidCorrelationIdGeneratorTest'
./gradlew test --tests \
  'org.meldtech.platform.shared.infra.web.RequestContextWebFilterTest.replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink'
```

## P6.8 - Candidate-Hash Secret Handling Review

Status: PASS (2026-10-05).

Outside the `local` profile, startup validation now requires
`cbt.observability.candidate-hash.secret-reference` to be an absolute path
beneath `/run/secrets`. An inline value, relative path, ConfigMap-style
property, absent reference, unreadable file, or secret shorter than 32 bytes
fails closed without echoing the path or value. `application.yaml` supplies no
default; it accepts only the external environment reference.

`CandidateIdentifierHasherTest.producesDifferentHashesWithDifferentEnvironmentSecrets`
loads two distinct mounted-file fixtures and proves the same candidate has a
different keyed hash in each environment. The deployment image copies only
the application JAR, and the repository contains no candidate-hash value or
ConfigMap entry. The blocking history and working-tree secret scan is clean.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.ObservabilityConfigurationValidatorTest.rejectsInlineCandidateHashMaterialOutsideLocal' \
  --tests 'org.meldtech.platform.platform.infra.observability.CandidateIdentifierHasherTest'
./gradlew secretScan
```

## P6.9 - Telemetry Export Boundary Review

Status: PASS (2026-10-05).

The signed `P3.5` collector contract fixes SPIFFE X.509 SVID workload
identities for `api`, `worker`, and `pindist`, mutual TLS 1.3, and an
allow-only-enumerated-identities collector policy. `OtlpTlsMaterial` now loads
the projected trust bundle, client certificate, and private key from their
runtime file references and applies them to the trace, metric, and log OTLP
exporters. Temporary byte arrays are zeroed after exporter construction, and
failure diagnostics disclose no reference or credential detail.

Non-local startup refuses HTTP, URI user-info, disabled TLS, or missing TLS
references. `TelemetrySchemaGateTest` supplies the outbound data boundary:
candidate email/name, answer content, secret-shaped attributes, and unbounded
labels cannot enter any exported signal. The approved candidate reference is
the environment-keyed hash from `P6.3`.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.observability.OtlpTlsMaterialTest' \
  --tests 'org.meldtech.platform.platform.infra.observability.ObservabilityConfigurationValidatorTest.rejectsInsecureOrCredentialBearingCollectorEndpointsOutsideLocal' \
  --tests 'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.rejectsCandidateEmailAndNameFromEveryTelemetrySurface' \
  --tests 'org.meldtech.platform.platform.infra.observability.TelemetrySchemaGateTest.rejectsAnswerContentFromLogsAndSpanAttributes'
```

## P6.10 - Operational Log and Audit Separation Review

Status: PASS (2026-10-05).

The two contracts are distinct:

| Concern | Operational logs | Audit records |
|---|---|---|
| Store | OTLP collector contract names `cbt-platform-logs`, owned by Platform Ops. | `FEAT-AUD-001` owns the planned PostgreSQL `audit` store; this repository currently exposes only its `AuditEmitter` API and does not yet claim the store adapter is implemented. |
| Retention | Fixed at 30 days by the signed collector contract. | Retention-class and legal-hold policy belongs to `FEAT-AUD-001`/`FEAT-PRIV-001`, independent of log retention. |
| Access | Collector authorization admits only the enumerated workload identities; operator access belongs to Platform Ops controls. | Compliance query access is tenant-scoped and privileged reads must themselves be audited. |
| Delivery guarantee | Bounded queues may sample or drop operational telemetry without affecting availability. | Audit records are never sampled; owning capability transactions must fail rather than silently omit a required audit fact. |

`ObservabilityAuditSeparationTests.operationalLoggingAndAuditRemainIndependentSinks`
checks compiled production dependencies in both directions. Observability code
cannot depend on the audit API, and the audit module cannot depend on the
observability implementation, SLF4J, OpenTelemetry, or Micrometer. Current
callers may emit a log and an audit fact independently, but neither sink can
delegate to the other.

Evidence command:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.ObservabilityAuditSeparationTests'
```
