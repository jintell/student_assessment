# Phase 6 Security and Hardening Evidence

## P6.1 - Error Allowlist Denies by Default

Status: PASS (2026-09-27).

`ProblemDetailMapperTest.unmappedFailureUsesTheGenericNonDisclosingEntry` introduces an exception type
that has no catalogue mapping and gives it a provider- and credential-shaped message. The mapper emits
the built-in `CBT-PLAT-INTERNAL` response with the fixed internal type, title, status, detail, instance,
and correlation identifier, with no extensions and no occurrence of the exception message. A new
exception therefore remains non-disclosing until an explicit catalogue mapping is added.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.shared.kernel.error.ProblemDetailMapperTest.unmappedFailureUsesTheGenericNonDisclosingEntry'
```

## P6.2 - Adversarial Error-Response Leak Attempts

Status: PASS (2026-09-27).

`ProblemDetailWebExceptionHandlerTest.refusesInternalDetailFromResponseBody` exercises the actual
`application/problem+json` response boundary with five independent exception-message payloads. Each
attempt embeds a unique marker, and the test refuses both the complete source text and its marker.

| Attempt | Refused input |
|---|---|
| Exception message | Internal invariant and exception-message marker |
| Stack trace | Class, method, source-line text, and stack marker |
| SQL fragment | `SELECT` text naming an internal relation |
| Provider error | Upstream diagnostic text |
| Secret value | Authorization-shaped key and secret marker |

All five responses are constructed from fixed catalogue language. None derives any body field from
`Throwable.getMessage()`, satisfying `REQ-SEC-010` and `REQ-RSLT-041` for these adversarial sources.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.kernel.error.ProblemDetailWebExceptionHandlerTest.refusesInternalDetailFromResponseBody'
```

## P6.3 - Correlation-Identifier Privacy Review

Status: PASS (2026-09-27).

The correlation identifier is a canonical ULID. `UlidCorrelationIdGenerator` constructs its 128 bits
from only a 48-bit timestamp supplied by the controlled `Clock` and 80 bits from `SecureRandom`. Its
constructor and `generate()` method accept no actor, tenant, email, request body, authentication claim,
or other personal-data source. `CorrelationId` then admits only the fixed 26-character uppercase ULID
alphabet; it cannot be used as a free-text carrier.

Traceability is deliberately external to the identifier. `RequestContextWebFilter` installs the value
under the `correlationId` Reactor-context key, `ReactorContextPropagationConfiguration` projects that
key into MDC, and `application.yaml` emits it in the log pattern. Support personnel join the response
identifier to authorized log-store records; the value itself discloses no actor, tenant, or email.

Client-supplied identifiers are accepted only when they satisfy the same strict ULID grammar. They are
opaque caller-provided join keys, not values derived by the platform from personal data; hostile input
replacement is verified separately by P6.4.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.context.UlidCorrelationIdGeneratorTest'
```

## P6.4 - Hostile Correlation-Header Hardening

Status: PASS (2026-09-27).

`RequestContextWebFilterTest.replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink`
submits five hostile `X-Correlation-Id` values independently: 129 characters, a control character, a
newline plus forged field, a JSON fragment, and an ANSI escape sequence. The test enables the real
Reactor-to-MDC bridge and captures a Logback appender event emitted by the downstream handler.

For every attempt, the response header contains the generated canonical ULID, the log event's MDC
contains that same generated value, and neither the formatted log message nor any MDC value contains
the hostile input. This proves replacement at both externally visible sinks named by
`TASK-PLAT3-DEFECT-006`, rather than inferring log safety from the response alone.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.shared.infra.web.RequestContextWebFilterTest.replacesHostileCorrelationIdWithoutWritingItToTheResponseOrLogSink'
```

## P6.5 - Idempotency Replay Isolation

Status: PASS (2026-09-27).

`RedisIdempotencyStoreTest.replayIsPartitionedByTenantAndActor` drives the adapter with the same route,
client idempotency key, and request fingerprint across four calls. A repeat under the original tenant
and actor returns `REPLAY`; changing only the tenant returns `RESERVED`, and changing only the actor
also returns `RESERVED`. The stateful Redis substitute observes three distinct hashed storage keys.

The production key preimage is `routeId`, tenant id (or the literal platform scope), actor id, and the
client key, delimited before SHA-256 hashing. Consequently, a caller cannot use a known client key to
retrieve another tenant's or actor's stored response.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisIdempotencyStoreTest.replayIsPartitionedByTenantAndActor'
```

## P6.6 - Idempotency Store-Content Review

Status: PASS (2026-09-27).

The Redis key is a SHA-256 digest over the route, tenant scope, actor, and client idempotency key; none
of those raw values is present in the stored key. A pending value contains only a request fingerprint
and random reservation owner. A completed value replaces the owner with a base64url-encoded replay
envelope containing exactly `status`, replay-safe `headers`, `contentType`, and `body`. The body is the
same byte sequence already returned to that caller; the adapter adds no actor, tenant, email, or other
personal field. `StoredResponse` also limits replayed headers to `Location` and `Retry-After`.

`RedisIdempotencyStoreTest.storedEntryContainsOnlyTheReplayableResponseAndPreservesItsTtl` decodes the
persisted envelope, asserts its exact field set and byte-for-byte body identity, confirms raw scope
values are absent from the Redis key, and verifies completion preserves the key's remaining expiry.
`reservesForExactlyTwentyFourHours` separately proves the initial retention is exactly 24 hours.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisIdempotencyStoreTest.storedEntryContainsOnlyTheReplayableResponseAndPreservesItsTtl' \
  --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisIdempotencyStoreTest.reservesForExactlyTwentyFourHours'
```

## P6.7 - Secret-Field Pattern Has One Definition

Status: PASS (2026-09-27).

A source-wide search finds the canonical segment set (`pin`, `otp`, `token`, `secret`, `password`,
`key`, `authorization`) exactly once, in `shared.kernel.security.SecretFieldPattern`. The error mapper
imports that type and invokes `isSecretField`; it has no local regex or copied term set. The current
audit and observability packages contain no alternate implementation. Their later feature work must
consume this exported kernel type, as assigned in the P2.15 design, rather than introduce a copy.

`SecretFieldPatternTest` verifies the canonical pattern across camel case, separators, acronyms, and
non-secret substring lookalikes.

The `FEAT-OBS-001` `P4.5` correction adds the architecture-approved
`policy_key` leaf to an immutable allowlist at this same definition site. The
matcher still rejects every other `*_key` and any approved leaf beneath a
secret parent path; no consumer owns a local exception.

Evidence commands:

```bash
rg -l \
  'Set\\.of\\("pin", "otp", "token", "secret", "password", "key", "authorization"\\)' \
  src/main/java src/test/java config src/main/resources
./gradlew test --tests \
  'org.meldtech.platform.shared.kernel.security.SecretFieldPatternTest'
```

## P6.8 - Problem-Detail Mapper Bypass Review

Status: PASS (2026-09-27).

The review found response replay and capture embedded in `IdempotencyRequestFilter`. Although those
paths handled successful stored responses rather than constructing errors, they made the filter look
like a direct response-body construction site. The transport work now lives in two narrowly scoped
adapters: `StoredResponseReplayer` writes only a previously stored response, and
`IdempotencyResponseCapture` decorates an already produced downstream response for persistence.

After that separation, no production controller or `WebFilter` invokes `writeWith`. The only
`application/problem+json` writer is `ProblemDetailWebExceptionHandler`, and both its normal and
serialization-fallback bodies come from `ProblemDetailMapper`. `ProblemDetailDocument` construction
occurs only inside the mapper. Unavailable idempotency paths raise an exception and therefore reach the
same mapper instead of formatting an error in the filter.

`StoredResponseReplayerTest` proves the non-error adapter reproduces only the stored status, permitted
headers, content type, and bytes. The build-enforced static negative assertion remains the separately
scheduled P7.4 task and is not claimed by this Phase 6 review.

Evidence commands:

```bash
rg -n \
  'writeWith\\(|new ProblemDetailDocument\\(|APPLICATION_PROBLEM_JSON|problem\\+json' \
  src/main/java --glob '*.java'
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.IdempotencyRequestFilterTest' \
  --tests \
  'org.meldtech.platform.platform.infra.idempotency.StoredResponseReplayerTest' \
  --tests \
  'org.meldtech.platform.platform.infra.kernel.error.ProblemDetailWebExceptionHandlerTest'
```

## P6.9 - Secret Scan and Redis Credential Configuration

Status: PASS WITH PRODUCTION-PROVISIONING CARRY (2026-09-27).

The pinned Gitleaks 8.30.1 gate scanned 103 reachable commits and the current working tree, reporting
no leaks in either scope. This covers the shared kernel, error catalogue, Redis configuration, and all
other tracked and untracked repository content.

The `production` profile now requires `spring.data.redis.password` from the external
`cbt.redis.password` property. The property is loaded from the Redis config-tree mount (default
`/run/secrets/redis/`) and has no committed fallback. Redis host, port, and username remain non-secret
deployment metadata, and TLS is mandatory in that profile. `RedisSecretConfigurationTest` blocks a
literal URL credential or a defaulted password from entering the application configuration.

The unauthenticated Redis service in `compose.yaml` is ephemeral, loopback-only local-development
infrastructure. It is not an admissible production configuration. Production provisioning and the
actual workload secret mount remain blocked by signed gap record `GAP-FEAT-PLAT-003-P0.5`; this task
provides and verifies the application-side secret-resolution contract without claiming that unassigned
deployment work.

Evidence commands:

```bash
./gradlew secretScan
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.idempotency.RedisSecretConfigurationTest'
```

## P6.10 - Architecture Section 13.6 Threat Conformance

Status: CONFORMANT WITH NAMED CARRIES (2026-09-27).

The error contract is a non-disclosure backstop, not an authorization or tenant-isolation mechanism.
The review therefore credits it only where an error response could reveal internal or cross-tenant
detail and leaves each root control with its architecture owner.

| Section 13.6 threat | Error-contract mitigation and evidence | Carried control | Residual assessment |
|---|---|---|---|
| **I/E:** a missing tenant predicate returns another institution's candidates, questions, or results | The mapper denies unmapped failures by default, emits fixed catalogue text, preserves only an opaque correlation id, and excludes exception, SQL, provider, and secret text (P6.1-P6.4). It supports the row's `404` non-disclosure outcome without revealing whether a foreign resource exists. | Query-signature and forced-RLS prevention remain `FEAT-PLAT-002`; object-level authorization and cross-tenant `404` selection remain `FEAT-IAM-003`; the complete endpoint matrix remains `FEAT-SEC-001`; privileged-read audit remains `FEAT-AUD-001`. | Critical if any carried layer is absent. The mapper limits disclosure on failure but cannot prevent a successful foreign-row read. |
| **E:** a platform-scope slice reaches tenant data without a platform role | Authorization failures can expose only an allowlisted fixed-detail problem body with the request correlation id. The contract neither creates platform scope nor interprets roles. | Closed platform-scope operations and persistence context are `FEAT-PLAT-002`; role evaluation is `FEAT-IAM-003`; denied-attempt audit is `FEAT-AUD-001`; release coverage is `FEAT-SEC-001`. | Low only after the named authorization and audit controls are delivered; no elevation claim is made here. |
| **T:** a migration or operator script mutates another tenant's data | HTTP operator slices inherit the non-leaking mapper. The contract has no authority over direct SQL or the migration process, so it is not credited as a tamper-prevention control. | DDL-only reviewed migrations are `FEAT-PLAT-005`; audited operator slices and break-glass enforcement are operations/audit work; persistence grants and RLS remain `FEAT-PLAT-002`. | Unchanged by this feature for non-HTTP paths; acceptance depends entirely on the named migration, persistence, audit, and operational controls. |

No §13.6 threat is silently marked discharged by error hygiene. The P6.1-P6.4 evidence establishes
non-disclosure where this feature owns it, while the carries preserve the architecture's independent
defence layers and their release obligations.
