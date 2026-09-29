# Phase 8 Deployment and Release Evidence

## P8.1 - Platform Correlation Header Contract

Verdict: **PUBLISHED**

`X-Correlation-Id` is the platform-wide request and response header. A caller
may supply exactly one value. The platform accepts it only when it is a
canonical uppercase ULID matching `^[0-7][0-9A-HJKMNP-TV-Z]{25}$`; an absent,
duplicate, malformed, lower-case, oversized, ambiguous-character, or
control-character-bearing value is replaced with a server-generated ULID.

The resolved value is returned as `X-Correlation-Id` on every response,
including error responses, and is the `correlationId` member of every
`ProblemDetail`. It is also the join value supplied to logging and tracing.
Invalid caller input is never echoed or logged.

The identifier is opaque support metadata. It contains no actor, tenant,
email, name, request payload, or other personal data. Clients may retain and
display it for support diagnostics but must not interpret its contents or use
it as authentication, authorization, idempotency, or business identity.

Consumers:

- Frontend volumes send an existing canonical value when continuing a flow,
  read the response value, and surface it with support-facing error details.
- `FEAT-OBS-001` consumes the resolved Reactor-context value for structured
  logs and traces; it never reads the unvalidated request header directly.
- API features consume the response/error contract without generating a
  second identifier.

Compatibility: accepting a broader input grammar, changing the header name,
omitting the response header, or changing the resolved value within one
request is a platform contract change and requires the section 10.6 API
version treatment.

Evidence:

- `RequestContextWebFilter`
- `CorrelationId` and `UlidCorrelationIdGenerator`
- `CorrelationIdLifecycleTest`
- `RequestContextWebFilterTest.replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink`
- Phase 6 evidence P6.3 and P6.4

## P8.2 - Runtime-Role Configuration Review

Verdict: **PASS - ONE KERNEL CONFIGURATION**

The shared kernel has no role-specific configuration property. Typed
identifiers, `ActorContext`, the controlled clock, decimal conventions,
`OutboxWriter`, the problem catalogue and mapper, correlation validation,
`SecretFieldPattern`, and the idempotency contracts are identical in the
`api`, `worker`, and `pindist` profiles.

The profile documents in `application.yaml` vary only workload-owned
infrastructure inputs: database pool name, login role, and pool sizing. The
production document additionally supplies external Redis connection metadata
and a secret-backed password; it does not alter kernel semantics, catalogue
entries, correlation rules, retention, or degradation behavior. The Redis
adapter's 24-hour retention is a code-level invariant rather than a profile
override.

`FEAT-PLAT-006` can therefore package one kernel and one generated error
catalogue into all three runtime roles. A future role-specific kernel property
would violate this review and requires a new architecture decision rather than
an unreviewed profile branch.

Evidence:

- `src/main/resources/application.yaml`
- `RedisIdempotencyStore.RETENTION`
- `KernelErrorConfiguration`
- `compileKernelJava` framework-boundary task

## P8.3 - Rollback Statement

Verdict: **CODE-ONLY ROLLBACK**

`FEAT-PLAT-003` creates no database schema, migration, or durable
platform-owned record. Rolling back its implementation is an application
image/source revert and requires no data rollback. Redis idempotency entries
are disposable, version-prefixed cache records; an older compatible image may
ignore them and their fixed TTL removes them naturally.

The error catalogue is additive once published. Removing or reusing a
published `code`, changing its semantic meaning, changing its `type` URI, or
removing a response field is a breaking client change. A code revert must
therefore retain all catalogue entries already released, or follow the plan
section 10.6 version treatment. Rollback is not permission to contract the
public error contract.

The `X-Correlation-Id` header and accepted canonical ULID form have the same
compatibility boundary. Their implementation may be reverted only to an image
that preserves the published request, response, and error-body behavior.

Evidence:

- Plan feature card data impact: none
- No `FEAT-PLAT-003` migration exists under `src/main/resources/db/migration`
- `error-catalogue.yaml` and generated OpenAPI/client artifacts
- `RedisIdempotencyStore` versioned key prefix and 24-hour retention

## P8.4 - Redis-Absent Startup Degradation

Verdict: **PASS**

`RedisUnavailableStartupTest` starts the idempotency application context with
the real `LettuceConnectionFactory`, reactive Redis template,
`RedisIdempotencyStore`, route registry, metrics, and request filter pointed at
unreachable `127.0.0.1:1`. Context refresh succeeds because Redis connectivity
is not a startup prerequisite.

The same test sends `POST /candidate/answers` through the real filter. The
route declares a durable record protected by the PostgreSQL unique index
`uq_answer_attempt_operation`, so it does not consult Redis and invokes the
candidate chain successfully. P7.14 separately proves that a Redis-backed
generic `POST` returns the fixed duplicate-request problem without executing a
side effect when the running Redis container is stopped.

Evidence command (passed 2026-09-28):

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisUnavailableStartupTest'
```
