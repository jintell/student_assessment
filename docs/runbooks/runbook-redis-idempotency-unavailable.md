# Runbook: Redis Idempotency Store Unavailable

Use this runbook when `idempotency_store_unavailable_total` rises or a
Redis-backed generic `POST` begins returning
`CBT-PLAT-IDEMPOTENCY-UNAVAILABLE`.

## Expected symptoms

- `idempotency_replay_total{outcome="unavailable"}` and
  `idempotency_store_unavailable_total` increase together.
- Generic non-durable `POST` routes protected by `Idempotency-Key` return the
  fixed `409` problem: "The request cannot be safely repeated at this time."
- The protected transition is not invoked because the filter obtained no
  reservation. Retrying remains safe but will continue to fail closed until
  the store recovers.
- Durable-record operations continue to use PostgreSQL unique constraints,
  deduplication rows, or state guards. Candidate answer acceptance and attempt
  submission do not depend on Redis availability.
- Redis-backed caches may miss or reconnect according to their owning
  features, but Redis is never authoritative domain state.

## Triage

1. Record the environment, deployment version, start time, Redis endpoint
   health, TLS/authentication result, connection-pool saturation, latency, and
   recent network or secret rotations. Do not print Redis credentials.
2. Compare `idempotency_store_unavailable_total` with the bounded replay
   outcomes and affected route telemetry. Confirm failures are limited to
   declared generic non-durable transitions.
3. Select a metric exemplar, follow its trace, and use the trace's
   `correlationId` to inspect protected logs. Confirm the filter returned the
   unavailable problem before the application chain or side-effect port ran.
4. Verify the relevant PostgreSQL business table, outbox, and audit evidence
   contain no record attributable to the refused correlation identifier. If a
   record exists, treat it as a duplicate-side-effect incident and escalate.
5. Run or inspect a candidate-path synthetic check for answer acceptance and
   attempt submission. Confirm its PostgreSQL protection succeeds and no
   Redis-unavailability problem is returned.

## Restore service

1. Restore network reachability, workload identity/secret resolution, TLS
   trust, DNS, or Redis capacity through the owning infrastructure procedure.
2. Do not disable the idempotency filter, change the route to unguarded
   execution, extend a client retry loop, or substitute an in-memory store.
3. Allow the client to retry the original generic transition with the same
   `Idempotency-Key` after health is restored. A successful reservation may
   execute once; a completed reservation replays the stored response.
4. Confirm new `reserved` and `replay` outcomes appear, unavailable counters
   stop increasing, and the candidate-path synthetic check remains healthy.

## Safety verification

The following assertions are mandatory before closure:

- Every refused generic transition has zero durable business, outbox, and
  audit side effects.
- Repeating a completed key returns the identical stored response and does not
  execute the transition again.
- Candidate routes creating durable records never accept the Redis
  `Idempotency-Key` mechanism and remain available through PostgreSQL guards.
- Recovery requires no correctness repair because Redis held only disposable
  reservation/replay state, not authoritative domain state.

## Closure evidence

Retain the incident interval, affected route classes, representative
correlation identifiers, infrastructure cause, zero-side-effect query
evidence, candidate-path synthetic result, recovery time, and post-recovery
outcome counters. Production incidents also reference the still-open
`TASK-PLAT3-DEFECT-005` ownership gap until production Redis has a named
feature owner and exercised operational controls.
