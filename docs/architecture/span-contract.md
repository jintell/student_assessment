# Slice Span Contract

Status: registered platform tracing contract for `FEAT-PLAT-001` (`P2.9`, `P9.1`), consumed by
`FEAT-OBS-001`. Source: architecture §16.3.

## Boundary and Name

The tracing unit is one slice invocation. A span starts immediately before policy evaluation/handler dispatch and closes when the returned reactive publisher completes, errors, or is cancelled. Its name is exactly `<module>.<verbNoun>`, using the module id and slice package name, for example `examaccess.verifyPinAndStartAttempt`.

HTTP server, broker consumer, and scheduler spans are parents; the slice span is their child. An outbox consumer continues valid W3C trace context from broker headers or starts a new trace linked to the producer context when continuation is invalid or too old.

## Required Attributes

| Attribute | Presence | Value |
|---|---|---|
| `module` | Always | Closed module id such as `examaccess` |
| `slice` | Always | `<verbNoun>` package identifier |
| `correlationId` | Always | The value seeded in Reactor `Context`; this is the canonical correlation-id field name in logs, problems, and spans |
| `audience` | Always | `workforce`, `candidate`, `operator`, `provider`, or `system` |
| `actorType` | Always | `WORKFORCE_USER`, `CANDIDATE`, or an enumerated system actor |
| `tenantId` | Tenant-scoped spans | Typed tenant identifier rendered in canonical form; absent only for explicitly platform-scoped operations |
| `operation` | Always | `READ`, `CREATE`, `UPDATE`, `TRANSITION`, or `DELETE` |
| `outcome` | On completion | `SUCCESS`, `DENIED`, `REJECTED`, `ERROR`, `TIMEOUT`, or `CANCELLED` |
| `errorCode` | Failures only | Stable allowlisted application code, never an exception message |

Trace and span identifiers use the OpenTelemetry/W3C fields `traceId` and `spanId` in logs; they are intrinsic span identity rather than duplicated custom attributes.

## Child Spans and Propagation

Database statement spans contain operation/table names only, never SQL parameters. Redis, broker publish/consume, outbox relay batch, and external provider calls use standard semantic attributes plus the same correlation identifier. The outbox row and broker headers carry W3C context and `correlationId`; the consumer reconstructs Reactor context before opening its slice span.

## Data Safety and Sampling

No span name, event, status description, or attribute contains PINs, OTPs, tokens, answer content, email, name, source request body, SQL parameter, provider payload, or exception message. Resource IDs other than `tenantId` are added only when an incident-use case is approved and cardinality/privacy are reviewed.

Sampling follows architecture §16.3: retain 100% of errors and exam-entry/grading traces, with 10% tail-based sampling elsewhere. Sampling must not change whether spans are created or whether `correlationId` propagates.

## Verification Contract

Tests assert exact span naming, required attributes on success/denial/error/cancellation, one slice span per invocation, parentage under HTTP and broker spans, correlation equality with response/log/problem fields, and absence of every forbidden secret/personal-data key.

The conformance reference endpoint implements the boundary with a Micrometer observation covering the full
reactive publisher lifecycle. `SliceTest.emitsOneSpanAtTheSliceBoundary` attaches a tracing handler and proves
that one invocation finishes exactly one `platform.getConformanceReference` span with its required safe tags.
Exporter selection, sampling, dashboards, and production telemetry infrastructure remain with
`FEAT-OBS-001`.
