# Platform Log Field Contract

Status: registered platform contract for `FEAT-PLAT-001` (`P9.2`), consumed by `FEAT-OBS-001`. Sources:
architecture §16.1 (`ARC-OBS-001`, `ARC-OBS-002`) and plan §8.1 (`FEAT-OBS-001`).

## Correlation Identifier

The canonical field name is exactly `correlationId`; the HTTP carrier is `X-Correlation-Id`. It is an opaque,
non-semantic join key shared by responses, problem details, logs, spans, audit events, and later outbox/broker
metadata. It must never be derived from or encode a person's name, email, login, candidate number, actor ID,
tenant ID, source address, credential, or request content. Code must not parse it for business meaning.

The request filter accepts only a syntactically well-formed identifier and otherwise generates one. The
filter copies only that opaque value into the immutable request carrier and logging projection; it does not
combine the value with any personal-data field.

## Structured Event Fields

Production logging is one JSON object per stdout line. Fields outside this table are denied until the privacy
and security review updates this contract.

| Field | Presence | Contract |
|---|---|---|
| `timestamp` | Always | RFC 3339 UTC timestamp. |
| `level` | Always | Enumerated log level. |
| `logger` | Always | Logger name, never a dynamic identifier. |
| `message` | Always | Stable operational message with no interpolated secret or personal data. |
| `correlationId` | Always | Opaque identifier defined above. |
| `traceId`, `spanId` | Always | OpenTelemetry identifiers joining the event to its trace. |
| `role` | Always | Runtime role: `api`, `worker`, or `pindist`. |
| `module`, `slice` | Always | Closed module and vertical-slice names. |
| `actorType`, `actorId` | Authenticated requests | Actor enumeration and safe internal identifier; never name or email. |
| `tenantId` | Tenant-scoped operations | Internal tenant identifier only. |
| `eventCode` | Business events | Stable code aligned with the audit event type. |
| `errorCode` | Failures | Stable code returned to the client; never exception text. |
| `durationMs`, `dbQueryCount` | Requests | Numeric request duration and query count. |
| `error.stack` | Approved failures | Structured exception field subject to the same redaction policy; no multiline event. |

## Data Safety and Ownership

Field names matching `pin`, `otp`, `token`, `secret`, `password`, `key`, or `authorization` are redacted.
Names, email addresses, request/response bodies, answers, provider payloads, SQL parameters, credentials, and
arbitrary object serialization are forbidden. Logs are operational telemetry and never substitute for the
audit store.

The foundation supplies Reactor-context propagation and the `correlationId` MDC projection. `FEAT-OBS-001`
owns JSON serialization, the complete allowlist/redactor, trace field projection, export, sampling, and the
blocking log-hygiene verification.
