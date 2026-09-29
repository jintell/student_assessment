# Idempotency Authoring

Status: normative guide for retry-safe HTTP transitions and durable side
effects. Sources: architecture section 10.5 and `ARC-PLAT-010`.

## Decision Rule

A route that creates a durable domain record may not rely on Redis or the
`Idempotency-Key` header for correctness. Protect the durable fact in
PostgreSQL with a named unique constraint, deduplication row, or state-machine
guard, and return the previously established outcome on a valid replay.

Use the Redis-backed `Idempotency-Key` mechanism only for a `POST` transition
that creates no durable domain record of its own. The response is retained for
24 hours. When Redis cannot establish a trustworthy reservation or replay,
return the fixed `CBT-PLAT-IDEMPOTENCY-UNAVAILABLE` problem without invoking
the transition. Redis loss must never permit a duplicate side effect.

Candidate-path requests do not depend on Redis. Answer submission, attempt
submission, exam entry, and other durable candidate behavior remain correct
when the idempotency store is empty or unreachable.

## Route Declaration

Every state-changing route declares one `@IdempotencyPolicy`:

| Mechanism | Declaration rule |
|---|---|
| `POSTGRES_UNIQUE` | `createsDurableRecord=true`; `databaseProtection` names the concrete PostgreSQL constraint or index |
| `DURABLE_STATE_GUARD` | `databaseProtection` names the table, state transition, or durable deduplication guard |
| `REDIS_HEADER` | HTTP method is `POST`; `createsDurableRecord=false`; `databaseProtection` is empty |
| `NOT_APPLICABLE` | No durable record and no database protection; use only when the transition has no retry-sensitive effect |

Only `REDIS_HEADER` permits the HTTP adapter to read `Idempotency-Key`. Do not
read the header inside a handler or attach it to a route with a database-backed
record. CI stage 4 validates the declaration against the stable route table.

## Architecture Examples

| Operation | Stable key | Required mechanism | Owning feature |
|---|---|---|---|
| Answer submission | Client `operationId` in the body | Unique `(attempt_id, operation_id)`; replay returns the stored response | `FEAT-DLV-002` |
| Attempt submission | `attemptId` and terminal state | Durable state guard; re-submitting `SUBMITTED` returns `200` with the same body | `FEAT-DLV-002` |
| PIN issuance for a session | `(examSessionId, candidateId)` | Partial unique index; return existing live PIN metadata | `FEAT-EXAM-001` |
| Result publication | `(resultId, versionNumber)` | Unique index prevents duplicate authoritative versions | `FEAT-RSLT-002` |
| Correction application | `correctionRequestId` in `APPROVED` state | State-machine guard; only `APPROVED` may transition | `FEAT-CORR-003` |
| Notification dispatch | `(eventId, channel, recipientHash)` | Unique index prevents duplicate PIN or OTP sends | `FEAT-NOTF-001` |
| Provider webhook | Provider `MessageID` and event type | Durable deduplication row with a bounded window | `FEAT-NOTF-003` |
| Outbox relay | `outboxEventId` and consumer business key | At-least-once relay plus durable consumer deduplication | `FEAT-PLAT-004` and each consumer |

None of these durable operations may be reclassified as `REDIS_HEADER`.

## Redis Reserve or Replay

For an eligible non-durable transition, compose the store result before the
side effect:

- `RESERVED(token)`: this caller alone may execute, then complete the stored
  response with that reservation token.
- `REPLAY(response)`: return the byte-identical stored response; do not invoke
  the transition.
- `UNAVAILABLE`: return the fixed unavailable problem; do not invoke the
  transition.

Scope the key by stable route identity and tenant or platform actor identity.
Fingerprint the canonical method, route, content type, and body without
retaining raw request data or secrets. Never log the key. Reuse with a
different fingerprint is a duplicate-request conflict, not permission to run
new work. A failed completion is observable, but does not justify a blind
retry of the transition.

Run the route rule after adding or changing a state-changing route:

```bash
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.IdempotencyRouteRuleTests'
```
