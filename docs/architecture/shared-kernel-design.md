# Shared Kernel and Uniform Error Contract Design

Status: normative design for `FEAT-PLAT-003` (`P2.1`-`P2.18`).
Architecture baseline: `arch-v1.4`.
Sources: architecture sections 8.4, 8.5, 10.4, 10.5, 14.1, 14.4,
16.1, and 19.8; plan section 8.1; and the Phase 1 discovery record.

This document defines contracts only. Implementation remains assigned to Phase
3 and Phase 4 of the feature task list. Where architecture section 8.4 names
`outbox.event`, this design uses the granted relation `outbox.outbox_event` as
resolved by `TASK-PLAT3-DEFECT-004`.

## P2.1 Typed Identifiers

Each identifier is a distinct immutable final value type around one non-null
`UUID`. The initial set is `TenantId`, `CandidateId`, `AttemptId`,
`AssessmentId`, `ExamSessionId`, `ResultId`, `CorrectionRequestId`, and
`OutboxEventId`; an owning feature introduces another type rather than sharing
a generic `EntityId` or passing a raw UUID.

Every type has these operations and no implicit conversion:

- `parse(String)` trims nothing and accepts only the canonical hyphenated UUID
  representation; null, blank, malformed, braced, or non-canonical text is
  rejected with a value-construction exception before a handler runs;
- `newId(IdGenerator)` obtains a UUIDv7 from the injected generator; identifier
  values never read ambient time or randomness directly;
- `toString()` returns the canonical lower-case representation used at HTTP,
  event, and persistence boundaries;
- equality and hashing compare the wrapped UUID and the concrete identifier
  class. There is no shared supertype with a raw-value method and no public
  raw-UUID accessor.

Boundary adapters parse external text into the required concrete type and map
construction failure to the catalogue validation problem. Persistence adapters
bind a concrete identifier through a type-specific converter. Public handler,
domain, query, and port signatures use the concrete type, so Java compilation
rejects a `CandidateId` where a `TenantId` is required. This is the primary
`REQ-SEC-003` defence against identifier confusion and cross-tenant reads.

## P2.2 Actor Context

`ActorContext` is an immutable record with these components:

| Component | Type and invariant |
|---|---|
| `actorType` | Required `ActorType`: `WORKFORCE_USER`, `CANDIDATE`, or `SYSTEM` |
| `actorId` | Required non-blank `ActorId`; opaque and safe for logs, never an email or display name |
| `tenantId` | `Optional<TenantId>`; present for every tenant-scoped operation |
| `correlationId` | Required, strictly valid `CorrelationId` ULID |
| `sourceIp` | Required validated `SourceIp` derived only from the trusted proxy boundary |
| `systemActorName` | `Optional<SystemActor>`; present exactly when `actorType == SYSTEM` |

The compact constructor rejects null optionals, blank actor identifiers,
invalid correlation/source values, a system actor without a
`systemActorName`, and a non-system actor with one. The closed `SystemActor`
enumeration contains only `AUTO_SUBMIT_SWEEPER`, `GRADING_WORKER`,
`RETENTION_ENGINE`, `IDP_RECONCILER`, `OUTBOX_RELAY`, and
`NOTIFICATION_DISPATCHER`; neither null nor free text such as `"system"` can
represent a system actor.

Construction uses named factories for `candidate`, `tenantWorkforce`,
`platformWorkforce`, `tenantSystem`, and `platformSystem`. Candidate and
tenant-workforce factories require a tenant. Tenant-system work requires a
tenant unless the enumerated operation is explicitly platform-scoped.
A tenantless workforce actor is accepted only by `platformWorkforce` after
the authorization layer has selected an enumerated platform operation. These
factories make tenant omission an explicit platform/system choice rather than
an accidental null.

Every protected handler receives a non-null `ActorContext` parameter; write
ports repeat that parameter where attribution must remain explicit. Missing
request context is a denial mapped to a catalogue problem. There is no
anonymous factory, default actor, no-argument write method, or optional actor
parameter, so an absent actor is unrepresentable on a valid write path.

## P2.3 Controlled Clock

`org.meldtech.platform.shared.kernel.time.Clock` is a framework-free port with
one operation, `Instant now()`. Application and domain code receive it through
constructor injection. A caller may derive a date or duration from that
instant, but may not obtain current time from another source.

`platform.infra.time.SystemClock` is the only production adapter and the only
production location permitted to call `Instant.now()`; it delegates to an
injected JDK clock. Tests use `FixedClock`, constructed with an explicit
instant and optionally advanced by the test itself. Neither implementation
uses global mutable state.

CI stage 4 scans bytecode and source outside `SystemClock` and rejects
`Instant.now()`, `System.currentTimeMillis()`, `LocalDate.now()`,
`LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()`, direct
construction of a system JDK clock, and equivalent ambient-time calls. This
makes server-authoritative deadlines under `BR-ASMT-002` deterministic and
testable.

## P2.4 Decimal Conventions

All scoring values are `BigDecimal`. Every multiplication, addition, and
division uses `MathContext.DECIMAL128`; intermediate section and overall
percentages are never rounded for presentation or persistence. Raw scores map
to PostgreSQL `NUMERIC(12,4)` and full-precision percentages map to
`NUMERIC(9,6)`. Persistence rejects overflow or excess scale instead of
silently truncating. `FEAT-GRD-001` owns the evaluation pipeline and consumes
these conventions.

`Decimal.roundHalfUpToWholeNumber(BigDecimal)` is the only whole-number
rounding operation. It rejects null, calls `setScale(0, RoundingMode.HALF_UP)`,
and returns a scale-zero `BigDecimal`; HALF_UP rounds ties away from zero.

| Input or boundary | Required result or behavior |
|---|---|
| `0.499999` | `0` |
| `0.500000` | `1` |
| `1.500000` | `2` |
| `-0.499999` | `0` |
| `-0.500000` | `-1` |
| `-1.500000` | `-2` |
| Raw `99999999.9999` / `-99999999.9999` | Accepted at `NUMERIC(12,4)` bounds |
| Raw magnitude above `99999999.9999`, or scale above 4 | Rejected before persistence |
| Percentage `999.999999` / `-999.999999` | Accepted at `NUMERIC(9,6)` bounds |
| Percentage magnitude above `999.999999`, or scale above 6 | Rejected before persistence |

Binary `double`, `float`, `Double`, and `Float` are prohibited in grading and
scoring types, fields, parameters, return types, and arithmetic. The table is
the `P7.6` test oracle, including exact ties, negative values, and both storage
boundaries.

## P2.5 Outbox Writer Port

`shared.kernel.outbox.OutboxWriter` is the sole asynchronous cross-module
publication port. Its operation is conceptually:

```java
Publisher<Void> append(TenantId tenantId, ActorContext actor, OutboxMessage message);
```

`Publisher` is the Reactive Streams protocol abstraction, not a Reactor,
Spring, R2DBC, or persistence type. The returned publisher is cold and must be
composed into the caller-owned transaction. Subscription appends exactly one
row to `outbox.outbox_event` on the connection already installed in request
context; it never opens, commits, or switches a transaction and never sends to
the broker. Absence of that transaction is an error.

`OutboxMessage` is immutable and contains `OutboxEventId eventId`, a stable
versioned `eventType`, `AggregateReference` (`aggregateType` plus typed-id
text), an immutable module-owned `IntegrationEvent payload`, the originating
`CorrelationId`, and the event time supplied by `Clock`. The port accepts no
JSON node, database row, broker record, arbitrary context map, or framework
serialization type. The adapter owns serialization and preserves the
correlation identifier as explicit metadata.

`FEAT-PLAT-004` supplies the `platform.infra.outbox` adapter and relay. This
feature owns only the framework-free port and values. The existing transitional
`outbox.api.OutboxWriter` is replaced at `P4.5`; it does not remain as a second
publication path.

## P2.6 Idempotency Store Port

`IdempotencyKey` is an immutable opaque ASCII value accepted only from the
single `Idempotency-Key` header. Construction rejects null, blank, control
characters, leading/trailing whitespace, and values longer than 128 characters.
It has value equality but is never logged because a client may reuse it across
systems.

`IdempotencyStore` is a framework-neutral asynchronous port with two operations:

```java
Publisher<ReservationOutcome> reserveOrReplay(
        IdempotencyScope scope, IdempotencyKey key, RequestFingerprint request);
Publisher<Void> complete(ReservationToken reservation, StoredResponse response);
```

`IdempotencyScope` includes the stable route id and tenant or explicitly
platform-scoped actor identity, preventing one tenant or operation from
replaying another's response. `RequestFingerprint` is a one-way digest of the
canonical method, route, content type, and body; it contains no raw request or
secret. `StoredResponse` is an immutable, size-bounded status, permitted-header,
content-type, and byte-body value sufficient to reproduce the first response.

`reserveOrReplay` has exactly three public outcomes:

- `RESERVED(token)` means this caller exclusively owns the transition and must
  either complete the response or let the reservation expire;
- `REPLAY(response)` returns the byte-identical completed response. A key reused
  with a different fingerprint maps to the fixed duplicate-request response,
  never to execution of the new request;
- `UNAVAILABLE` means the store could not establish a trustworthy decision.
  Expected connectivity, timeout, and empty-store conditions become this value,
  not an exception that escapes as `500`.

Reservations and completed responses expire 24 hours after reservation. The
adapter performs reservation atomically, completion is owned by the reservation
token, and no caller can extend the retention beyond the fixed convention.

## P2.7 Idempotency Degradation

The application service branches on every `ReservationOutcome` before invoking
the transition:

| Outcome | Application behavior | Side-effect rule |
|---|---|---|
| `RESERVED` | Execute once, store the completed response, and return it | Only this branch may invoke the transition |
| `REPLAY` | Return the stored response unchanged | Do not invoke the transition |
| `UNAVAILABLE` | Map `CBT-PLAT-IDEMPOTENCY-UNAVAILABLE` to the fixed duplicate-request problem | Do not invoke the transition |

An unexpected adapter exception is normalized to `UNAVAILABLE` at the adapter
boundary. Failure to complete a reservation is recorded for operations but does
not trigger a blind retry of the transition. A pending concurrent reservation
returns the same fixed duplicate-request problem until a completed response is
available.

Candidate-path routes must not declare `IdempotencyStore` as a dependency and
remain correct with Redis empty or unreachable. Durable-record operations use
their PostgreSQL unique constraint, deduplication row, or state guard. Thus
Redis loss can refuse a generic non-durable transition, but can neither permit
a duplicate side effect nor deny exam delivery, answer submission, or another
candidate-path request.

## P2.8 Idempotency Route Rule

Every state-changing route class carries one runtime-visible
`@IdempotencyPolicy` declaration with:

```text
createsDurableRecord: boolean
mechanism: POSTGRES_UNIQUE | DURABLE_STATE_GUARD | REDIS_HEADER | NOT_APPLICABLE
databaseProtection: optional non-blank constraint, index, table, or state-guard name
```

The route-table generator joins this declaration to each stable route id and
HTTP method. CI rejects a missing or duplicate declaration and applies these
closed rules:

| Declaration | Required condition |
|---|---|
| `REDIS_HEADER` | Method is `POST`, `createsDurableRecord` is false, and `databaseProtection` is absent |
| `POSTGRES_UNIQUE` | `createsDurableRecord` is true and a concrete PostgreSQL constraint or index name is present |
| `DURABLE_STATE_GUARD` | Durable behavior is protected by a named table/state transition or deduplication row |
| `NOT_APPLICABLE` | Route is safe/read-only or has no retry-sensitive transition and does not accept `Idempotency-Key` |

Only `REDIS_HEADER` permits the HTTP adapter to read `Idempotency-Key`. A route
that creates a durable record while declaring `REDIS_HEADER`, or names no
PostgreSQL protection, fails CI stage 4 with the stable route id in the error.
The check consumes the existing `PolicyProtectedRoute` table plus the
annotation; it does not infer safety from endpoint names or prose.

## P2.9 Error Catalogue Format

`src/main/resources/error-catalogue.yaml` is the single declarative source for
error responses and generated client documentation. It is validated at build
time against a closed JSON Schema before code or OpenAPI generation. The root
contains a `catalogueVersion` and a `problems` map keyed by unique `code`; no
duplicate file or code-level response constants are permitted.

Each problem entry has exactly these fields:

```yaml
CBT-PLAT-INTERNAL:
  type: https://errors.meld-tech.com/problems/internal
  title: Unexpected error
  status: 500
  detail: The request could not be completed.
  extensions: {}
```

`code` must match `^CBT-PLAT-[A-Z0-9]+(?:-[A-Z0-9]+)*$`. `type` must be a
unique absolute URI below the approved base
`https://errors.meld-tech.com/problems/`; `title` and `detail` are required,
non-blank, fixed strings; `status` is an integer HTTP status from 400 through
599. `extensions` is a map from an approved response-field name to a primitive
schema (`string`, `integer`, `number`, or `boolean`) with requiredness and
constraints. Base RFC 9457 members and arbitrary object-valued extensions
cannot be redefined.

`CBT-PLAT-INTERNAL` is mandatory and cannot carry extensions. Catalogue order
has no meaning, codes and type URIs are stable once published, and removal or
semantic reassignment is a breaking API change. The generator produces the
runtime immutable catalogue, the OpenAPI component, and client documentation
from this artifact, so body behavior and documentation cannot diverge.

## P2.10 Problem Detail Mapper

`ProblemDetailMapper` is the only type allowed to instantiate the platform's
`ProblemDetailDocument`. The document is a framework-free immutable value with
package-private construction and public read access to exactly `type`, `title`,
`status`, `code`, `detail`, `instance`, `correlationId`, and the catalogue-
declared primitive extensions. Spring's `ProblemDetail`, maps assembled by an
endpoint, and module-owned error response DTOs are forbidden construction
paths.

The mapper receives a `Throwable` and a validated `ProblemContext` containing
the request-instance URI and `CorrelationId`. An immutable exception-mapping
registry maps known exception classes or stable domain failure keys to one
catalogue code; the mapper then copies all client-visible language from that
catalogue entry. It derives no field from `Throwable.getMessage()`,
`toString()`, a stack trace, SQL/provider diagnostics, or nested causes.

Unknown exceptions, unknown domain keys, and mappings to absent codes select
`CBT-PLAT-INTERNAL`. The selected catalogue status is also the HTTP response
status, and only extensions declared by that entry may be supplied. Framework
adapters translate the immutable result to `application/problem+json` without
adding body fields. This closed mapping implements `ARC-SEC-010`: extending the
response surface requires a reviewed catalogue change, not another exception
handler.

## P2.11 Mapper Failure Tolerance

The public entry point is `mapSafely(Throwable, ProblemContext)`. It is a
bounded synchronous operation over the startup-validated immutable catalogue;
it performs no I/O, blocking, retry, recursive cause traversal, or user code.
It catches mapping/runtime failures and returns a response rather than throwing
a second exception.

| Failure | Required fallback |
|---|---|
| Catalogue code is absent or corrupt at lookup | Select the built-in immutable `CBT-PLAT-INTERNAL` entry |
| Normal JSON serialization fails | The web adapter asks the mapper for its minimal UTF-8 fallback renderer, which emits only the seven required base fields from the built-in entry |
| Correlation identifier is absent or invalid | Generate a new strict ULID before rendering; never copy the absent/invalid input |

Every fallback increments `problem_detail_unmapped_total` through the
framework-free `ProblemDetailMetrics` port with a bounded reason
(`CATALOGUE_MISS`, `SERIALIZATION_FAILURE`, or `MISSING_CORRELATION`). Metric
adapter failure is swallowed after local diagnostic emission and cannot alter
the response. The minimal renderer accepts only catalogue constants, a strict
ULID, and a normalized request path, escaping the latter with a small owned
JSON-string routine; it does not invoke the failed general serializer.

The final response is status 500, `application/problem+json`, and the generic
non-disclosing body with a valid correlation identifier. The web failure
handler writes it once and terminates the exchange. It neither re-enters the
normal exception chain nor waits for telemetry, which prevents a leak, hang,
or second escaping exception on all three paths.

## P2.12 Response Allowlist Test

CI stage 10 runs a standalone blocking `problemDetailAllowlistTest` with four
closed-world assertions:

1. Load the generated catalogue, require the built-in generic entry, enumerate
   every code, map a generated representative failure for that code, serialize
   it, and assert the status, media type, seven base fields, and only the
   declared extension fields match the catalogue.
2. Enumerate every exception/domain-failure mapping and require exactly one
   existing catalogue target. Exercise an unmapped exception and require the
   generic entry. Dead mappings and catalogue entries without a generated test
   case fail the task.
3. Parse every emitted body as JSON and reject undeclared keys, missing base
   fields, wrong primitive types, exception text, stack/SQL/provider fragments,
   secret-pattern matches, or a correlation value that is not a strict ULID.
4. Use ArchUnit to reject production construction of `ProblemDetailDocument`,
   Spring `ProblemDetail`, `application/problem+json` responses, or alternate
   error DTOs outside `ProblemDetailMapper` and its single web writer. Endpoint
   and advice packages may only delegate to that writer.

The test inputs are generated from the catalogue and mapping registry rather
than a hand-maintained list. A new response code, mapping, field, writer, or
body shape therefore expands the enumerated set or fails the build; there is no
uninspected error path. `ci/stage-10` invokes this Gradle task as a required,
non-continue-on-error check.

## P2.13 Correlation Identifier Lifecycle

`CorrelationId` is an immutable value that accepts only the approved canonical
ULID pattern `^[0-7][0-9A-HJKMNP-TV-Z]{25}$`. It is exactly 26 uppercase ASCII
characters, carries no personal or business data, and exposes no timestamp as
application authority.

The highest-precedence request filter applies this lifecycle:

1. Read at most one `X-Correlation-Id` value. An absent header generates a new
   server ULID. Multiple values, wrong length/case/alphabet, whitespace, or any
   control character are invalid and generate a replacement.
2. Never echo, log, tag, or include the invalid source value in telemetry. A
   bounded invalid-input counter may increment without recording the value.
3. Put the resulting `CorrelationId` inside the definitive `ActorContext` in
   Reactor `Context` before policy or handler execution.
4. Set the same canonical value on every HTTP response, including authorization,
   validation, mapped, generic, and pre-handler failures.
5. Project the same value into the scoped `correlationId` log field, the
   `correlation.id` span attribute, and every `ProblemDetailDocument`.

Logging and span bridges read only the validated value from Reactor `Context`;
MDC remains an observability projection, never an authority. Outbox messages
persist the value explicitly because Reactor context does not cross a durable
boundary. The `P7.10` join test compares response header, log, span, and problem
body values byte for byte.

## P2.14 Request-Context Adoption

The definitive Reactor payload is `ActorContext` under the class key
`ActorContext.class`; it already contains the typed tenant, correlation, actor,
and source-address values, so no parallel string carrier is retained.
`RequestContextPropagation` is the adapter-side write/read mechanism: the web
filter validates and writes the value, application boundaries use
`deferContextual`, and the logging bridge projects its allowlisted fields. A
missing value is an error, never an empty or platform-scope default.

Adoption is one ordered change set:

1. Kernel `P4.15` changes baseline `P4.7`'s `WebFilter`, baseline `P4.8`'s
   `ContextSnapshot` bridge, policies, handlers, and the outbox reconstruction
   boundary to `ActorContext`; baseline `P4.9` is re-run across
   `publishOn`/`subscribeOn`.
2. Kernel `P4.16` replaces baseline `P4.6`'s `RequestTenantId`, `RequestActor`,
   `RequestActorType`, and `RequestCarrier` everywhere and deletes them in the
   same change. Baseline `P4.16`'s `TenantScopedQuery` implementation and
   `P4.22` R5 signature rule adopt `TenantId`; baseline `P4.23` R6 and `P4.24`
   R7 are re-run against the real `Clock`, decimal, and `OutboxWriter` types.
   Baseline `P7.8`-`P7.10` negative fixtures are re-run, followed by the full
   R1-R8 suite required by kernel `P7.17`.
3. Kernel `P4.17` updates persistence `P4.2`-`P4.5`
   `SecurityContextInitializer`, persistence `P4.6` platform scope, and
   persistence `P4.7` transactional collaboration to `TenantId` and
   `ActorContext`. Persistence `P7.12` (`ARC-VERIFY-024`) is then re-run and its
   `L9` retained evidence is refreshed by persistence `P7.17`, as required by
   kernel `P7.18`.

Compilation is not supported in a mixed-carrier state. A source-wide search
for all four placeholder type names is a blocking adoption assertion, and
both sibling suites must be green before the Phase 0 seam is closed at `P8.6`.

## P2.15 Secret Field Pattern

`SecretFieldPattern` is one immutable kernel utility that classifies field
names, never field values. It owns the closed, case-insensitive sensitive-token
set `pin`, `otp`, `token`, `secret`, `password`, `key`, and `authorization`.
Before matching, it splits camel case and treats `.`, `_`, and `-` as segment
boundaries, then lower-cases with `Locale.ROOT`. A name is sensitive when any
complete normalized segment is in the set. Thus `apiKey`, `access_token`,
`pin-ciphertext`, `passwordHash`, and `authorization.header` match, while an
unrelated substring is not redacted accidentally.

The API exposes only `boolean isSecretField(String fieldPath)` and rejects null
input. Consumers import this type; they may not copy the tokens or compile a
second pattern:

| Consumer limb | Owner and required use |
|---|---|
| Problem-detail field emission | `FEAT-PLAT-003`: reject a catalogue extension or supplied extension whose name matches before serialization |
| Structured logging redaction | `FEAT-OBS-001`: replace the value before a field reaches any sink |
| Audit payload validation | `FEAT-AUD-001`: reject secret-named payload fields before append/hash calculation |

A CI source rule searches for alternative sensitive-name lists or regexes in
these three limbs and points the caller to `SecretFieldPattern`. Changes to the
closed set are security-contract changes with tests shared by all consumers.

## P2.16 Kernel Purity Rule

CI stage 4 defines `kernel_is_framework_and_module_independent`. It selects all
production classes in `org.meldtech.platform.shared.kernel..` and permits
dependencies only on the JDK, the minimal `org.reactivestreams.Publisher` SPI
used by asynchronous ports, and other kernel types. It rejects:

- `org.springframework..`, including Spring Web `ProblemDetail` and Modulith
  annotations on kernel classes;
- `reactor..` concrete types, `io.r2dbc..`, Jackson, Redis clients, database or
  broker clients, servlet APIs, and telemetry implementations;
- every `org.meldtech.platform.<module>..`, `..infra..`, `..slice..`, and
  `..migration..` type outside the kernel, including transitional shared API
  carriers.

The build also isolates the kernel compile classpath so a forbidden import
cannot compile merely because the application uses that dependency elsewhere.
Spring Modulith metadata remains in the parent `shared` module descriptor,
outside `shared.kernel`; there is no annotation exception inside the kernel.

The stable failure prefix is
`KERNEL-PURITY: <origin> depends on forbidden <target>; shared.kernel may use only JDK, Reactive Streams SPI, and kernel types.`
The rule has no package-name allowlist for adapters. `P7.5` adds a deliberate
Spring import and proves the blocking rule fails before reverting the fixture.

## P2.17 CI Stage 4 Conformance Rules

The feature contributes these four blocking rules to the existing
`conformanceTest` source set:

| Rule | Selection and assertion | Stable failure prefix | Ownership |
|---|---|---|---|
| Write attribution | Join state-changing `POST`/`PUT`/`PATCH`/`DELETE` route descriptors to their handler entry methods; every entry method and every reachable write port operation must require a direct non-null `ActorContext` parameter | `ACTOR-CONTEXT: write path <route/method> has no ActorContext parameter.` | `ARC-VERIFY-011`; new kernel rule |
| System actor closure | Production references to `ActorType.SYSTEM` may occur only inside `ActorContext` factories, whose public system factories require `SystemActor`; reject string system names and reflection/configuration construction | `SYSTEM-ACTOR: <origin> bypasses the closed SystemActor enumeration.` | `ARC-VERIFY-011`; new kernel rule |
| Controlled time | Reject ambient-time calls and system-clock construction outside `platform.infra.time.SystemClock`, using the call targets listed in `P2.3` | `R6 controlled time violated: <origin> calls <target>; inject shared.kernel Clock.` | Extends baseline `P4.23` R6; does not create a duplicate rule |
| Exact scoring | In `..grading..`, `..scoring..`, and types implementing the scoring marker, reject `double`, `float`, boxed forms, binary literals/conversions, and `BigDecimal.doubleValue/floatValue` in fields, signatures, and bytecode calls | `R6 exact decimal violated: <origin> uses <type/call>; use shared.kernel Decimal conventions.` | Extends baseline `P4.23` R6; does not create a duplicate rule |

The actor rules use route metadata plus bytecode/signature inspection, not
naming alone. The two R6 limbs replace the baseline placeholder subjects with
the real clock and decimal contracts while preserving the existing R6 task and
failure reporting. Each rule has one isolated negative fixture; a rule that
cannot classify a selected path fails closed and names the unclassified path.

## P2.18 Package Layout and Dependency Direction

```text
org.meldtech.platform
|-- shared
|   |-- package-info.java                 shared module descriptor
|   `-- kernel                            named interface: shared::kernel
|       |-- identity                      typed identifiers, IdGenerator
|       |-- context                       ActorContext, ActorType, SystemActor
|       |-- time                          Clock
|       |-- decimal                       Decimal conventions
|       |-- outbox                        OutboxWriter and event values
|       |-- idempotency                   IdempotencyStore and result values
|       |-- error                         catalogue values and mapper port
|       `-- security                      SecretFieldPattern
`-- platform
    `-- infra
        `-- kernel
            |-- time                      SystemClock
            |-- outbox                    transactional adapter (FEAT-PLAT-004)
            |-- idempotency               Redis adapter and HTTP filter
            |-- error                     WebFlux writer and metric adapter
            `-- context                   WebFilter and logging bridge

context modules/slices -----> shared::kernel ports and values
platform.infra adapters ----> shared::kernel ports and values
shared.kernel --------------> JDK + Reactive Streams SPI only
shared.kernel --------------X platform.infra or any context module
```

`shared.kernel/package-info.java` is descriptor metadata only: it declares the
propagated `shared::kernel` named interface so its public port and value
packages are visible to consumers. The kernel-purity rule excludes that
descriptor file from class dependency selection but permits no Spring import
in any kernel type. Package-private constructors, generated catalogue loaders,
and validation helpers remain unreachable even though their public contracts
share the named interface package tree. The `platform` descriptor adds only
`shared::kernel` to its allowed dependencies.

The direction follows clean architecture: stable policy and value contracts
sit at the center, while WebFlux, R2DBC, Redis, serialization, logging, and
system-time choices point inward from replaceable adapters. No factory or
contract in the kernel returns an adapter, imports a module-owned event type,
or performs infrastructure discovery, so an adapter type is never reachable
from kernel code.
