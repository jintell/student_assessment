# Structured Logging Contract

Status: normative platform interface for `FEAT-OBS-001`.

Sources: architecture section 16.1 (`ARC-OBS-001`, `ARC-OBS-002`),
`FEAT-OBS-001` discovery task `P1.1`, and the registered platform log-field
contract. The field names and meanings in this document are compatibility
contracts for log producers, sinks, diagnostics, and dashboards.

## Emission Contract

Non-local profiles emit one JSON object per stdout line. A newline terminates
the event; field values cannot introduce additional physical lines. Timestamps
use RFC 3339 UTC, trace and span identifiers use lowercase OpenTelemetry hex,
and duration and query-count values are non-negative integers.

`correlationId` is the canonical opaque join key. The same resolved value
connects the HTTP response, problem detail, structured log, span, metric
exemplar, and asynchronous carrier. It carries no business meaning and must
not encode a person, tenant, credential, network address, or request content.

## Field Provenance

| Field | Presence | Value producer | Emission owner and rule |
|---|---|---|---|
| `timestamp` | Always | `FEAT-OBS-001` | Encoder emits the event time as RFC 3339 UTC. |
| `level` | Always | `FEAT-OBS-001` | Derived from the logging event; one of `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`. |
| `logger` | Always | `FEAT-OBS-001` | Derived from the static SLF4J logger name; never a dynamic identifier. |
| `message` | Always | Owning feature | Stable, single-line operational text after redaction; no interpolated secret or personal data. |
| `correlationId` | Always | Kernel contract from `FEAT-PLAT-003` | Observability consumes the resolved propagated value; it never consumes or logs an unvalidated request header. |
| `traceId` | Always | OpenTelemetry SDK | Enriched from the active or synthetic root span. |
| `spanId` | Always | OpenTelemetry SDK | Enriched from the active or synthetic root span. |
| `role` | Always | Runtime bootstrap from `FEAT-PLAT-006` | Closed runtime role: `API`, `WORKER`, or `PINDIST`. |
| `module` | Always | `FEAT-PLAT-001` log-field contract | Fixed owning module name matching `[a-z][a-z0-9]*`. |
| `slice` | Always | `FEAT-PLAT-001` log-field contract | Registered vertical-slice name matching `[a-z][A-Za-z0-9]*`. |
| `actorType` | Authenticated operations | Kernel `ActorContext` from `FEAT-PLAT-003` | Emitted together with `actorId`; never inferred by observability. |
| `actorId` | Authenticated operations | Kernel `ActorContext` from `FEAT-PLAT-003` | Safe opaque internal identifier only; never an email or name. |
| `tenantId` | Tenant-scoped operations | Kernel `ActorContext` from `FEAT-PLAT-003` | Protected-log join field; never a metric label. |
| `eventCode` | Business events | Owning feature selects the `FEAT-OBS-001` enumeration | Stable code aligned with the corresponding audit event type. |
| `errorCode` | Failures | Error catalogue from `FEAT-PLAT-003` | Same stable code exposed in the client problem detail; never exception text. |
| `durationMs` | Completed requests | `FEAT-OBS-001` request instrumentation | Present together with `dbQueryCount`. |
| `dbQueryCount` | Completed requests | `FEAT-OBS-001` R2DBC instrumentation | Reactor-context-scoped count measured independently of trace sampling; present with `durationMs`. |
| `error.stack` | Approved failures | `FEAT-OBS-001` | Structured, bounded stack frames under the same redaction policy; requires `errorCode`. |

Absent conditional fields are omitted rather than emitted as `null`.
`actorType` and `actorId` are an inseparable pair. `durationMs` and
`dbQueryCount` are also an inseparable pair.

## Data Safety

The structured boundary is allowlisted. PINs, OTPs, tokens, authorization
values, passwords, keys, credentials, answer content, raw request or response
bodies, SQL parameters, provider payloads, email addresses, and personal names
are prohibited. Secret-pattern keys are replaced with `[REDACTED]`; unsafe
domain objects and unapproved fields fail the telemetry schema gate. Candidate
diagnostic references, where specifically approved, use the platform keyed
hash and never a raw candidate identifier.

Logs are operational telemetry, not audit records. They can be sampled or
dropped during telemetry degradation and use a separate sink, access policy,
and retention schedule from the immutable audit store.

## Compatibility and Verification

Adding a field requires privacy and security review, an update to the
allowlist and this contract, and conformance coverage. Renaming, removing, or
changing the meaning of a field is a breaking change for log consumers.

Run `./gradlew telemetrySchemaGate test --tests
'org.meldtech.platform.platform.infra.observability.StructuredLogEventTest'
--tests
'org.meldtech.platform.platform.infra.observability.StructuredJsonLogEncoderTest'`
for focused contract verification. CI stage 10 additionally runs the blocking
operational log leak scan.
