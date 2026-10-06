# Correlation-Identifier Failure Diagnosis

Owner: Platform Operations

Consumer: `FEAT-OPS-002` operator diagnostic surfaces

Use this runbook when a client or internal responder reports a failed request
and supplies an `X-Correlation-Id` value.

## Inputs and Access

Required input: one canonical uppercase ULID correlation identifier and the
approximate environment/time of the request. Treat the identifier as opaque;
it grants no access and must not be parsed for actor, tenant, or business
meaning.

Use only authorized environment-scoped log, trace, and metric interfaces. Do
not paste request bodies, credentials, PINs, OTPs, answers, names, or email
addresses into search tools or incident notes.

## What the Client Saw

1. Read `X-Correlation-Id` from the response header. On an RFC 9457 error,
   confirm the same value appears in the `correlationId` member.
2. Record the HTTP status and stable public problem `code`/`type`. The public
   `detail` is intentionally bounded and may not expose the internal cause.
3. Validate the identifier against `^[0-7][0-9A-HJKMNP-TV-Z]{25}$`. If it is
   malformed, do not search for it; confirm the server-generated response
   value with the caller.

## Log to Trace to Metric

1. Search the protected operational log store for exact `correlationId`
   equality in the correct environment and time window. Do not use a partial
   identifier.
2. Confirm `role`, `module`, `slice`, `errorCode`, `durationMs`, and
   `dbQueryCount`. `actorId`, when present, must be an opaque safe identifier;
   do not enrich it with a name or email in telemetry.
3. Open the trace from the log's `traceId` and `spanId`. Confirm the root and
   slice spans carry the same `correlationId`, then follow database, Redis,
   outbox relay, broker consumer, or provider children as applicable.
4. At an asynchronous boundary, a consumer may start a linked trace when the
   producer context is too old or invalid. Continue using exact
   `correlationId` equality and the span link rather than assuming one trace
   identifier forever.
5. From the trace, open linked metric exemplars to establish whether the
   failure coincides with request latency, query duration, pool pressure,
   queue depth, or a business-event outcome. An exemplar carries trace/span
   identity; `correlationId` must not be a metric label.

The reverse path is also valid during an alert: metric exemplar -> trace ->
trace `correlationId` -> exact protected-log search -> client-visible stable
error code.

## Relay and Scheduler Checks

- For a scheduler hop, confirm the same correlation value survives before and
  after the hop; a missing value indicates Reactor-context propagation drift.
- For an outbox flow, compare the source log/trace with the outbox row
  `correlation_id`, broker `correlation_id` header, relay span, and consumer
  span. Never inspect or log a secret-bearing payload to establish the join.
- If the W3C context is absent, the consumer starts a new trace; the
  correlation value must still join the work. Absence of both values is a
  propagation defect, not an invitation to invent a replacement during the
  flow.

## Decision and Escalation

- Stable mapped application error: follow the owning feature runbook using
  `module`, `slice`, and `errorCode`.
- `problem_detail_unmapped_total` exemplar: follow
  `runbook-problem-detail-unmapped.md` and create or correct the intentional
  catalogue mapping.
- Elevated `dbQueryCount`: compare the route with
  `config/observability/query-budgets.json` and escalate the regression to the
  slice owner and `FEAT-OPS-005`.
- Missing correlation on any required surface: treat as an observability
  contract defect and retain the response, sanitized log schema, and trace
  metadata as evidence.
- Collector or store unavailable: switch to
  `runbook-telemetry-collector-outage.md`; do not restart healthy request
  replicas solely to restore telemetry.

## Closure Evidence

Record the canonical identifier, environment, UTC window, stable error code,
trace identifier, affected module/slice, finding, owner, and corrective action.
Do not copy secret values, personal data, answer content, exception messages,
or raw payloads into the incident record.
