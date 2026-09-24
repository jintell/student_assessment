# FEAT-PLAT-003 Phase 1 Discovery Record

Date: 2026-09-24
Architecture baseline: `arch-v1.4`
Primary sources: architecture v1.4 sections 8.4, 8.5, 10.4, 10.5,
14.2, 14.4, 16.1, 18.1, and 19.8; plan sections 8.1 and 14.4

This is the working artifact for kernel tasks P1.1-P1.9. It records source
defects rather than silently changing approved architecture or plan text.

## P1.1 Kernel Ownership Table

Architecture section 8.4 contains eleven table rows, although this task says
that the table contains twelve components. The task list adds the
`IdempotencyStore` port from section 10.5 as a required kernel concern. The
table below therefore preserves all eleven architecture rows and identifies
that additional, derived concern explicitly.

| Component or concern | Source responsibility | Owning feature and task |
|---|---|---|
| Typed identifiers (`TenantId`, `CandidateId`, `AttemptId`, ...) | Prevent identifier confusion; incompatible identifiers are not assignable | `FEAT-PLAT-003` `P4.1` |
| `ActorContext` | Required handler attribution for workforce, candidate, and enumerated system actors | `FEAT-PLAT-003` `P4.2` |
| `RequestContextPropagation` | Carry correlation, actor, and tenant data in Reactor `Context`, with a logging bridge | Mechanism: `FEAT-PLAT-001` `P4.7`-`P4.9`; definitive payload and adoption: `FEAT-PLAT-003` `P4.15`-`P4.17` |
| `Clock` | Sole server time source; ambient time calls are prohibited elsewhere | `FEAT-PLAT-003` `P4.3`, `P4.19` |
| `OutboxWriter` | Append an integration event in the caller transaction; asynchronous cross-module propagation uses the outbox | Port: `FEAT-PLAT-003` `P4.5`; implementation: `FEAT-PLAT-004` |
| `AuditEmitter` | Append a hash-linked audit event in the caller transaction | `FEAT-AUD-001`; explicitly excluded from this feature |
| `ProblemDetailMapper` | Sole construction site for allowlisted RFC 9457 responses | `FEAT-PLAT-003` `P4.10`-`P4.12` |
| Decimal conventions | `BigDecimal`, `DECIMAL128`, fixed storage precision, and canonical whole-number rounding | `FEAT-PLAT-003` `P4.4`, `P4.20`; scoring pipeline: `FEAT-GRD-001` |
| `TransactionalCollaboration` | Execute an enumerated atomic cross-module flow under one transaction and composite role | `FEAT-PLAT-002` `P4.7`; consumed later by `FEAT-EXAM-007` |
| `SecurityContextInitializer` | Install transaction-local role and tenant context and reset pooled connections | `FEAT-PLAT-002` `P4.2`-`P4.5`; real-type adoption: `FEAT-PLAT-003` `P4.17` |
| `TenantScopedQuery` | Mark queries whose methods must accept the tenant identifier | `FEAT-PLAT-001` `P4.5`, with the definitive type adopted by `FEAT-PLAT-003` `P4.16` |
| `IdempotencyStore` concern (derived from section 10.5; absent from the section 8.4 table) | Reserve or replay generic non-durable `POST` transitions using Redis for 24 hours, with fail-safe degradation | Port and adapter: `FEAT-PLAT-003` `P4.6`-`P4.9` |

The six kernel delivery groups owned here are: identity and actor context,
controlled time, exact decimal conventions, the outbox port, the problem
detail contract, and the idempotency port. The existing propagation mechanism
is adopted rather than rebuilt. `TenantScopedQuery`, `TransactionalCollaboration`,
`SecurityContextInitializer`, and `AuditEmitter` remain assigned to their
named sibling features.

## P1.2 Problem Detail Response-Shape Card

The approved P0.3 record fixes error-code namespace `CBT-PLAT`, type URI base
`https://errors.meld-tech.com/problems/`, RFC 9457 semantics, and a closed
allowlist. Platform responses make all seven base fields mandatory even where
RFC 9457 permits a member to be omitted; extension fields are mandatory only
for the catalogue entries that declare them.

| Field | Required | Source and value rule |
|---|---|---|
| `type` | Every response | Absolute URI below the approved type URI base, selected by catalogue entry |
| `title` | Every response | Stable, human-readable catalogue title; never derived from an exception |
| `status` | Every response | HTTP status recorded by the catalogue and equal to the response status |
| `code` | Every response | Stable machine-readable key in the approved `CBT-PLAT` namespace |
| `detail` | Every response | Fixed catalogue text keyed by `code`; no exception, SQL, provider, stack-trace, or secret text |
| `instance` | Every response | URI of the failed request instance |
| `correlationId` | Every response | Strictly valid request or generated ULID; identical to the log and trace value |
| Per-code extensions, for example `retryAfterSeconds` | Only when declared for that code | Closed set in the catalogue; `retryAfterSeconds` is an integer duration and is emitted for retryable/lockout responses that declare it |

`ProblemDetailMapper` is the only construction site. A catalogue miss or mapper
failure must use the generic, non-disclosing catalogue response rather than
relaxing these required fields.

## P1.3 Idempotency Card

| Operation | Key | Mechanism and replay behavior | Requirement | PostgreSQL unique index |
|---|---|---|---|---|
| Answer submission | Client `operationId` in the body | Unique `(attempt_id, operation_id)`; replay returns the stored response | `REQ-ASMT-022`, `AC-ASMT-005-01` | Yes |
| Attempt submission | `attemptId` plus terminal-state check | Re-submit of `SUBMITTED` returns `200` with the same body | `REQ-ASMT-020` | No; durable state guard |
| PIN issuance for a session | `(examSessionId, candidateId)` | Partial unique index; reissue returns existing live PIN metadata | `REQ-ASMT-009` | Yes, partial |
| Result publication | `(resultId, versionNumber)` | Unique index prevents a grading retry from publishing twice | `REQ-RSLT-043` | Yes |
| Correction application | `correctionRequestId` plus `APPROVED` state | State machine permits application only from `APPROVED` | `REQ-RSLT-031` | No; durable state guard |
| Notification dispatch | `(eventId, channel, recipientHash)` | Unique index is the duplicate PIN/OTP control because the provider has no idempotency key | `DEP-002` conditions 2 and 9 | Yes |
| Provider webhook | Provider `MessageID` plus event type | PostgreSQL deduplication table with a bounded window | `REQ-SEC-015` | Not specified; durable dedup row |
| Outbox relay | `outboxEventId` | At-least-once delivery; consumers deduplicate on the business key | `NFR-REL-003` | Not specified; durable outbox/business key |

`ARC-PLAT-010` adds one convention outside those eight operation-specific
rows: a generic `POST` transition that creates no durable domain record may
accept `Idempotency-Key`, with its response cached in Redis for 24 hours. This
is the only Redis-dependent idempotency mechanism. It is safe to lose because
unavailability yields a duplicate-request problem, never an unguarded retry or
duplicate side effect. Any operation that creates a durable record must use
its PostgreSQL key, constraint, or state guard and may not rely on Redis.

## P1.4 Actor-Context Card

`ActorContext` is immutable and is a required parameter of every handler. Its
source shape is:

| Field | Contract |
|---|---|
| `actorType` | Required closed value: `WORKFORCE_USER`, `CANDIDATE`, or `SYSTEM` |
| `actorId` | Required non-blank safe identifier; never an email or display name |
| `tenantId` | Present for tenant-scoped work; omission is limited to an explicitly authorized platform-scope or system operation |
| `correlationId` | Required strictly valid correlation ULID |
| `sourceIp` | Required trusted proxy-resolved source address |
| `systemActorName` | Required when `actorType` is `SYSTEM`, absent otherwise; selected only from `SystemActor` |

`SystemActor` is a closed six-value enumeration:

1. `AUTO_SUBMIT_SWEEPER`
2. `GRADING_WORKER`
3. `RETENTION_ENGINE`
4. `IDP_RECONCILER`
5. `OUTBOX_RELAY`
6. `NOTIFICATION_DISPATCHER`

No write path may omit `ActorContext`. A system actor name may be neither
`null` nor free text; the string `"system"` is specifically forbidden.
These rules discharge `BR-IAM-001` and `SC-008` structurally.

## P1.5 Structured-Log Field Ownership

`FEAT-PLAT-003` owns the semantic request/error context values below;
`FEAT-OBS-001` owns structured serialization, enrichment, export, and the
remaining observability fields.

| Architecture section 16.1 field | Presence | Value owner | Emission responsibility |
|---|---|---|---|
| `timestamp`, `level`, `logger`, `message` | Always | `FEAT-OBS-001` | `FEAT-OBS-001` |
| `correlationId` | Always | `FEAT-PLAT-003` | `FEAT-OBS-001` consumes propagated context |
| `traceId`, `spanId` | Always | `FEAT-OBS-001` | `FEAT-OBS-001` / OpenTelemetry |
| `actorType`, `actorId` | Authenticated requests | `FEAT-PLAT-003` `ActorContext` | `FEAT-OBS-001` consumes propagated context |
| `tenantId` | Tenant-scoped operations | `FEAT-PLAT-003` `ActorContext` | `FEAT-OBS-001` consumes propagated context |
| `module`, `slice` | Always | `FEAT-OBS-001` | `FEAT-OBS-001` |
| `eventCode` | Business events | Owning business feature | `FEAT-OBS-001` |
| `errorCode` | Failures | `FEAT-PLAT-003` error catalogue | `FEAT-OBS-001` consumes mapper outcome |
| `durationMs`, `dbQueryCount` | Requests | `FEAT-OBS-001` | `FEAT-OBS-001` |

`actorId` is a safe opaque identifier only. It is never an email, a personal
name, or another directly identifying display value. The logging redactor and
secret-field enforcement remain `FEAT-OBS-001` responsibilities that consume
the kernel-owned `SecretFieldPattern` without copying it.

## P1.6 Verification Ownership

The architecture section 19.8 register is authoritative for identifiers and
stages. This feature does not create a new `ARC-VERIFY` identifier.

| Verification | Relationship | Gate | This feature's obligation |
|---|---|---|---|
| `ARC-VERIFY-003` | Contributed | CI stage 4, blocking | Extend R6/domain purity with kernel purity and the no-ambient-time and no-binary-floating-point limbs; `FEAT-PLAT-001` retains the slice-anatomy owner role |
| `ARC-VERIFY-011` | Owned | CI stage 4, blocking | Prove every write path requires `ActorContext` and every system actor is enumerated |
| `ARC-VERIFY-013` | Owned | CI stage 10, blocking | Inject faults at every layer and permit only catalogue-allowlisted problem bodies with a correlation identifier |
| CI stage 10 secret-leak scan | Owned for error responses; contributed overall | CI stage 10, blocking under `NFR-SEC-002` | Prove stack traces, SQL, provider text, exception messages, and secret values cannot enter error bodies; logging and audit limbs remain `FEAT-OBS-001` and `FEAT-AUD-001` |
| `ARC-VERIFY-012` | Dependency contribution, not ownership | CI stage 9, blocking | Generate the error schema and catalogue into OpenAPI so a breaking error-contract change is detected against the released baseline |

The plan section 14.4 citations are defective and are retained as named
defects rather than implemented under the wrong identifiers:

| Defect | Plan claim | Architecture section 19.8 resolution |
|---|---|---|
| `TASK-PLAT3-DEFECT-001` | `ARC-VERIFY-004`-`006` cover clock, decimal, and domain purity | `-004` is the tenant-isolation matrix, `-005` is the RLS omission test, and `-006` is composite-role/outbox enforcement. Kernel purity contributes to `ARC-VERIFY-003` in CI 4 |
| `TASK-PLAT3-DEFECT-002` | `ARC-VERIFY-008` covers shared-kernel context propagation | `-008` asserts every route resolves to a policy. The register has no dedicated propagation identifier, so no replacement identifier is invented |
| `TASK-PLAT3-DEFECT-003` | The feature card omits this feature's actor and error verifications | Adopt `ARC-VERIFY-011` in CI 4 and `ARC-VERIFY-013` in CI 10 as owned obligations |

## P1.7 Redis Degradation Card

The task cites architecture section 14.2, but that section defines the IdP
outage contract. Redis behavior is stated in sections 14.1 and 14.4 and the
generic `POST` rule in section 10.5. This record uses those actual source
locations without rewriting the task wording.

| Redis condition or use | Required behavior | Forbidden outcome |
|---|---|---|
| Empty cache after start, flush, or recovery | Treat entries as misses and obtain authoritative data from PostgreSQL; repopulate opportunistically | Assuming a missing cache entry means authorization, domain state, or a durable operation is absent |
| Authorization cache unavailable | Fall back to correct, slower PostgreSQL authorization reads | Permit on uncertainty or deny a candidate solely because Redis is unavailable |
| Rate-limit store unavailable | Use the defined conservative local limiter | Remove rate limiting or make Redis a synchronous candidate-path availability dependency |
| Generic non-durable `POST` idempotency store returns `UNAVAILABLE` | Return the catalogue duplicate-request problem and perform no transition | Execute the transition without a reservation, retry blindly, or create a duplicate side effect |
| Durable-record operation | Use its PostgreSQL unique key, dedup row, or state guard; behavior remains correct with Redis empty or unreachable | Protect the operation only with `Idempotency-Key` or a Redis record |
| Redis recovery | Reconnect and allow caches to repopulate; no correctness repair should be necessary | Treat cached state as the system of record |

The candidate path's required dependency set remains PostgreSQL only. No
candidate-path request is denied merely for lack of Redis, and every behavior
remains correct with Redis empty or unreachable. `P7.14` must exercise the
unavailable store and prove both the duplicate-request response and absence of
a side effect.

## P1.8 Decimal Convention Card

| Obligation | Kernel contract |
|---|---|
| Intermediate arithmetic | Use `BigDecimal` with `MathContext.DECIMAL128`; do not round intermediate section or overall values |
| Stored raw scores | PostgreSQL `NUMERIC(12,4)`; preserve negative raw scores exactly |
| Stored full-precision percentages | PostgreSQL `NUMERIC(9,6)`; preserve the unrounded value used by later calculations |
| Published whole-number percentage | Use the single canonical `roundHalfUpToWholeNumber` function |
| Numeric types in scoring | `double`, `float`, `Double`, and `Float` are prohibited by the CI stage 4 conformance rule |

`FEAT-PLAT-003` owns these value and rounding conventions. `FEAT-GRD-001`
owns the canonical section/overall evaluation sequence, pass/fail decisions,
and regression fixtures that consume them. The kernel does not implement or
restate that scoring pipeline.

## P1.9 Consumer-Contract Table

The two ambiguous `tasks.md` references in the generated task list resolve in
this repository to `tasks/foundation/baseline/tasks.md` (`FEAT-PLAT-001`) and
`tasks/foundation/persistence/tasks.md` (`FEAT-PLAT-002`). Those are the only
downstream task lists currently present, so their adoption tasks can be named
directly. Later-feature rows are gated by this feature's explicit Phase 8
handover task; their future task lists must cite that handover before starting.

| Consumer | Contract that must be stable before the consumer starts | Adoption or handover task |
|---|---|---|
| `FEAT-PLAT-001` baseline | Replace `RequestTenantId`, `RequestActor`, and `RequestCarrier` from baseline `P4.6`; keep the `P4.7`-`P4.9` WebFilter, logging bridge, and scheduler propagation; update R5/R6/R7 subjects | Kernel `P4.15`-`P4.16`; re-run baseline `P4.22`-`P4.24` through kernel `P7.16`-`P7.17`; close both seams at `P8.6` |
| `FEAT-PLAT-002` persistence | `SecurityContextInitializer` and transactional APIs use definitive `TenantId`/`ActorContext`, not placeholder carriers | Kernel `P4.17`; re-run persistence `P7.12` (`ARC-VERIFY-024`) through kernel `P7.18`; close at `P8.6` |
| Every later handler and module API | Use typed identifiers, require `ActorContext` for writes, obtain time only from `Clock`, and emit errors only through `ProblemDetailMapper` | Kernel implementation `P4.1`-`P4.4`, `P4.10`-`P4.13`, enforcement `P4.18`-`P4.21`, usage guide `P10.3`-`P10.4` |
| `FEAT-PLAT-004` | Implement the framework-free `OutboxWriter` port and preserve originating correlation identifiers | Kernel `P4.5`; interface handover `P8.5` |
| `FEAT-AUD-001` | Attribute audit records with `ActorContext` and consume the single `SecretFieldPattern`; this feature does not implement `AuditEmitter` | Interface handover `P8.5`; pattern implementation `P4.14` |
| `FEAT-GRD-001` | Consume `DECIMAL128`, the two storage precisions, and the sole canonical rounding function; own the evaluation sequence and fixtures | Boundary proof `P7.6`; interface handover `P8.5`; authoring contract `P10.6` |
| `FEAT-OBS-001` | Emit propagated correlation/actor/tenant/error fields, consume `SecretFieldPattern`, and use the correlation identifier as the logs/traces join key | Header contract `P8.1`; interface handover `P8.5`; observability gap handoff `P9.5` |
| `FEAT-SEC-001` and every API feature | Enforce the closed error catalogue, generic unmapped default, allowlist-only response bodies, and secret-leak denial | Error-contract handover `P8.5`; normative catalogue `P10.1`; authoring guide `P10.4` |
| `FEAT-EXAM-009`, `FEAT-DLV-001`-`004`, and other time-sensitive slices | Use injected `Clock`; never read ambient time | Kernel `P4.3`, enforcement `P4.19`, proof `P7.9`, usage guide `P10.3` |
| Owners of the eight section 10.5 durable operations | Declare and implement the PostgreSQL unique key, dedup row, or state guard; do not depend on Redis for durable side-effect safety | Route rule `P4.9`, negative proof `P7.13`, idempotency guide `P10.5`; each owning feature supplies its operation-specific database constraint |
| Generic non-durable `POST` transition owners | Use `Idempotency-Key` only when no durable domain record is created and honor `UNAVAILABLE` as a duplicate-request response with no side effect | Kernel `P4.6`-`P4.9`, Redis proof `P7.12`-`P7.14`, guide `P10.5` |
| `FEAT-PLAT-006` runtime roles | Run the same stateless kernel in all three profiles; no role-specific kernel configuration | Configuration review `P8.2` |
| Frontend/API consumers and support tooling | Treat the catalogue and `X-Correlation-Id` request/response convention as stable client interfaces | Header publication `P8.1`; catalogue publication `P10.1`; support guide `P10.7` |

No dependent feature may start against a placeholder carrier or an unpublished
error/idempotency contract. Phase 0 exit therefore requires both concrete
adoption suites green and the five `P8.5` handovers recorded.
