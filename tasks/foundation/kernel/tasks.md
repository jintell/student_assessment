# Task List — `FEAT-PLAT-003` Shared Kernel and Uniform Error Contract

## Overview

|                       |                                                                                                                                                                                                                        |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-PLAT-003`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 track (b), §14.4                                                                                                          |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §8.4, §8.5, §10.4, §10.5 (`ARC-PLAT-010`), §11.2 (`ARC-PLAT-011`), §14.2, §16.1 (`ARC-OBS-001/002`), §16.2, §16.3, §18.1 stages 4/8/10, §19.8, `ADR-009`, `ADR-013` |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                       |
| Dependencies          | `FEAT-PLAT-001` (module boundaries, propagation mechanism, placeholder carriers, conformance-rule harness)                                                                                                              |
| Consumed by           | Every feature in the programme. Directly named: `FEAT-PLAT-004` (outbox port), `FEAT-AUD-001` (actor context), `FEAT-GRD-001` (exact decimal), `FEAT-OBS-001` (correlation identifier), `FEAT-SEC-001` (error contract), `FEAT-EXAM-009` and `FEAT-DLV-*` (controlled clock) |
| Generated on          | 2026-09-03                                                                                                                                                                                                             |
| Methodology           | Clean architecture, strictly applied to the kernel itself. `shared.kernel` is the **innermost** layer: no Spring, R2DBC, Jackson, Redis or module import, enforced by its own conformance rule. Every outward need is a **port** in the kernel; every adapter lives in `platform.infra` |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                  |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                             |

**Objective.** Provide the small set of primitives every module depends on — tenant identity, actor context,
a controlled clock, exact decimal arithmetic, the outbox port, the idempotency port and the problem-detail
mapper — and one uniform error contract that never leaks internal detail and always carries a correlation
identifier.

**Why this feature's timing matters more than its size.** The plan rates its risk "Low individually, but a
dependency of every other feature, so a late change is expensive". It is also the feature that *completes*
two others: `tasks.md` `P4.6` ships placeholder tenant and actor carriers "marked explicitly as
superseded by `FEAT-PLAT-003`", and `tasks.md` assumption 3 defers the real `TenantId` and
`ActorContext` to it. Track (b) runs parallel to track (a) (plan §11.2), so the adoption seam is scheduled
work in Phase 4 here, not an assumption someone remembers later.

### Confirmed implementation decisions

| Decision                    | Choice                                                                                                                                                                                                    | Consequence                                                                                                                                                       |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Kernel purity               | `shared.kernel` imports **no** framework type and no module type, enforced by an ArchUnit rule that ships with the kernel                                                                                  | The `domain`-purity obligation applied to the layer every `domain` depends on. A framework type in the kernel would make purity unenforceable everywhere else      |
| Idempotency scope           | The kernel defines the `IdempotencyStore` **port**, `IdempotencyKey` and the degradation contract; `platform.infra` supplies a Redis adapter with a 24 h TTL, proven against Testcontainers Redis          | `ARC-PLAT-010` becomes a real, testable mechanism rather than a convention. Production Redis **provisioning** is unowned — `TASK-PLAT3-DEFECT-005`                 |
| Idempotency route rule      | A route may use the `Idempotency-Key` header **only if** it creates no durable domain record; a route that creates one must be protected by a PostgreSQL unique index                                      | This is the rule that makes `ARC-PLAT-010`'s Redis dependency safe (P3). It is checkable, so it ships as a conformance rule rather than as guidance                |
| `AuditEmitter`              | **Excluded.** This feature ships the `OutboxWriter` port only, per the plan's scope item (d); `FEAT-AUD-001` ships the audit port and its implementation                                                   | Follows the plan's scope list. The §8.4-versus-plan asymmetry is recorded as a documentation observation, not silently resolved                                    |
| Error `detail` strings      | Emitted only from a fixed catalogue keyed by `code`; an exception message never reaches a response body                                                                                                    | `ARC-SEC-010`. Makes `REQ-SEC-010` and `REQ-RSLT-041` enforceable by one test instead of by reviewing every handler                                                |
| Unmapped-exception default  | A generic non-disclosing `500` with a correlation identifier, plus a metric increment — never a pass-through and never a hang                                                                              | The mapper is on every failure path, so its own failure mode must be the safe one (the feature's reliability expectation)                                          |
| Correlation identifier form | **ULID**, strictly validated on input; a malformed or oversized `X-Correlation-Id` is **replaced**, never echoed                                                                                            | `PLAN-RECOMMENDATION` — "well-formed" is undefined in the architecture, and an unvalidated client value reaches every log line. See `TASK-PLAT3-DEFECT-006`         |
| Secret-name pattern         | `SecretFieldPattern` is defined **once** in the kernel and consumed by the error mapper, the logging redactor and the audit payload check                                                                   | Three copies of one pattern drift. The kernel owns the pattern; `FEAT-OBS-001` and `FEAT-AUD-001` own their limbs                                                  |

### Assumptions

1. **The kernel owns six of the twelve §8.4 components**: typed ids, `ActorContext`,
   `RequestContextPropagation`, `Clock`, `OutboxWriter` (port), `ProblemDetailMapper` and the `Decimal`
   conventions. `TenantScopedQuery` is `FEAT-PLAT-001`'s (`tasks.md` `P4.5`);
   `TransactionalCollaboration` and `SecurityContextInitializer` are `FEAT-PLAT-002`'s (`tasks.md`
   `P4.7`, `P4.2`); `AuditEmitter` is `FEAT-AUD-001`'s. `P1.1` records the ownership per component.
2. **The propagation *mechanism* already exists.** `tasks.md` `P4.7`–`P4.9` built the `WebFilter`, the
   `ContextSnapshot` logging bridge and scheduler-hop propagation against placeholder carriers. This feature
   supplies the real payload and the rule that there is no no-arg write path; it does not rebuild the bridge.
3. **No schema change.** The plan's `Data impact` row is "None. Defines shared value types only." The
   `NUMERIC(12,4)` and `NUMERIC(9,6)` precisions are stated here as conventions; the columns belong to the
   owning features.
4. **The outbox table is `outbox.outbox_event`**, per §9.2, the grant matrix and `tasks.md`
   `TASK-PLAT2-DEFECT-002`, not §8.4's `outbox.event`. The port's naming follows the granted table.
5. **The scoring *pipeline* is `FEAT-GRD-001`'s.** This feature owns the decimal conventions and the single
   canonical `roundHalfUpToWholeNumber`; the §8.5 canonical evaluation sequence and fixtures A–E are not here.
6. **Redis is available as a test substrate only.** CI stage 8 lists a Redis Testcontainer (§18.1), which
   this feature adds and uses. No production Redis exists to configure yet.

### Blockers and defects carried into this task list

| ID                      | Statement                                                                                                                                                                                                                                                                                                                          | Owning task      |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| `PLAN-BLOCKER-001`      | `ci/architecture-ratification.json` is `status: RATIFIED`. Per plan §10 Phase 0 entry criteria this gates Phase 0 **implementation**. Discharged by `tasks.md` `P0.1`–`P0.7`; not restated here                                                                                                                                | `P0.1`           |
| `TASK-PLAT3-DEFECT-001` | Plan §14.4 attributes "conformance rules (clock, decimal, domain purity)" to `ARC-VERIFY-004`/`-005`/`-006`. §19.8 defines those as the tenant-isolation matrix (CI 10), RLS zero rows and composite-role writes. The correct owner is **`ARC-VERIFY-003`** (CI 4). The same mis-citation already propagated into `tasks.md` `P4.23` and is flagged back for correction | `P1.6`, `P10.9`  |
| `TASK-PLAT3-DEFECT-002` | Plan §14.4 attributes "shared-kernel and context propagation" to `ARC-VERIFY-008`, which §19.8 defines as "every route resolves to a `Policy`" (`REQ-SEC-001/002`) — `FEAT-IAM-003`/`FEAT-SEC-001` territory. No verification identifier covers shared-kernel propagation; the gap is raised rather than filled with a new identifier | `P1.6`, `P10.9`  |
| `TASK-PLAT3-DEFECT-003` | The feature card omits two verifications this feature plainly owns: **`ARC-VERIFY-011`** (no write path without an `ActorContext`; system actors from the enumeration, CI 4) and **`ARC-VERIFY-013`** (injected faults at every layer yield only allowlisted `ProblemDetail` bodies with a correlation id, CI 10). Both adopted as owned | `P1.6`, `P7.1`, `P7.8` |
| `TASK-PLAT3-DEFECT-004` | §8.4 names the outbox table `outbox.event` and the audit table `audit.event`; §9.2 and the grant matrix say `outbox.outbox_event` and `audit.audit_event`. `tasks.md`'s resolution is carried forward so the port matches the table its `INSERT` grant was issued against                                                       | `P2.5`, `P10.9`  |
| `TASK-PLAT3-DEFECT-005` | `ARC-PLAT-010` requires Redis and §18.1 stage 8 lists a Redis Testcontainer, but **no feature in `plan.md` owns Redis** — the word does not appear in the document. The port, adapter and test substrate are delivered here; production provisioning is raised as unowned and needs an owner before Phase 6                            | `P0.5`, `P8.7`   |
| `TASK-PLAT3-DEFECT-006` | §16.1 accepts `X-Correlation-Id` "if well-formed" without defining well-formed, and the accepted value is written to every log line, every response and every `ProblemDetail` — an unvalidated client string in a log sink is a log-injection and identifier-spoofing surface. ULID with strict validation adopted as a `PLAN-RECOMMENDATION` | `P0.4`, `P6.4`   |
| `TASK-PLAT3-OBS-001`    | §16.2 defines no metric for the error contract or for idempotency. An unmapped exception reaching the generic response is the leading indicator that the allowlist has been hit by something new, and it is currently invisible. Four metrics proposed and raised to `FEAT-OBS-001`                                                    | `P9.5`, `P10.9`  |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `tasks.md` `P0.1`–`P0.7` and is **not** restated. Under a
`temporaryArchitectureGate` (`implementationAllowed: false`) only Phase 1 and Phase 2 tasks are authorised.

1. [*] Confirm which authorisation scope is in force from `tasks.md` `P0.5` or `P0.6` and record it. Deliverable: one-line phase-log entry. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-001` has delivered the module skeleton, the placeholder carriers, the propagation mechanism and the conformance-rule harness this feature builds on. Deliverable: dependency-satisfied record. Depends on `tasks.md` `P4.2`, `P4.6`–`P4.9`, `P4.18`–`P4.25`.
3. [*] Obtain agreement on the §10.4 error taxonomy and the §10.5 idempotency semantics from the Solution Architect, Security and the API consumer representatives, as the feature's additional Definition of Ready. Deliverable: signed DoR record. Acceptance: the error-code namespace and the `type` URI base are fixed here, because both are client-visible and expensive to change later.
4. [ ] Raise the correlation-identifier form (`TASK-PLAT3-DEFECT-006`) for approval: ULID, strict validation, replace-not-echo on malformed input. Deliverable: approval record or a named alternative form. Acceptance: settled before `P4.13`.
5. [ ] Raise the unowned production-Redis gap (`TASK-PLAT3-DEFECT-005`) to the Engineering Lead and record which feature will own provisioning. Deliverable: gap record with a named owner or an explicit deferral to Phase 6.
6. [ ] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 1 – Discovery and Analysis

Satisfies the feature's additional Definition of Ready: the error taxonomy and idempotency semantics are
agreed.

1. [ ] Transcribe the §8.4 shared-kernel table and annotate each of the twelve components with its owning feature and owning task. Deliverable: kernel ownership table — the artifact that prevents a component being built twice or not at all. Acceptance: six components are owned here; `TenantScopedQuery`, `TransactionalCollaboration`, `SecurityContextInitializer` and `AuditEmitter` are attributed elsewhere with their sibling task references.
2. [ ] Transcribe the §10.4 `ProblemDetail` model field by field — `type`, `title`, `status`, `code`, `detail`, `instance`, `correlationId` and the per-code extension fields such as `retryAfterSeconds` — recording which are mandatory on every response. Deliverable: response-shape card.
3. [ ] Transcribe the §10.5 idempotency table row by row: the eight operations, their keys, their mechanisms and their requirements, marking which are PostgreSQL-unique-index protected and which is the single Redis-dependent case. Deliverable: idempotency card. Acceptance: `ARC-PLAT-010` is recorded as the **only** Redis-dependent mechanism, with the reason.
4. [ ] Transcribe the §8.4 `ActorContext` shape and the six-value system-actor enumeration (`AUTO_SUBMIT_SWEEPER`, `GRADING_WORKER`, `RETENTION_ENGINE`, `IDP_RECONCILER`, `OUTBOX_RELAY`, `NOTIFICATION_DISPATCHER`). Deliverable: actor-context card. Acceptance: records that the enumeration is closed, and that `null` and the string `"system"` are both forbidden values (`BR-IAM-001`, `SC-008`).
5. [ ] Transcribe the §16.1 log-field list and mark which fields this feature supplies (`correlationId`, `actorType`, `actorId`, `tenantId`, `errorCode`) versus which `FEAT-OBS-001` supplies. Deliverable: field-ownership table. Acceptance: `actorId` is recorded as a safe identifier only — never an email, never a name.
6. [ ] Map this feature's verification obligations to their real §19.8 identifiers and CI stages: `ARC-VERIFY-003` (contributed, CI 4 — the R6 clock, decimal and purity limbs), `ARC-VERIFY-011` (owned, CI 4), `ARC-VERIFY-013` (owned, CI 10), plus the CI 10 secret-leak scan and `ARC-VERIFY-012`'s dependence on the error contract. Deliverable: verification-ownership table. Acceptance: no new identifier is created, and `TASK-PLAT3-DEFECT-001` through `-003` are recorded against the plan's citations.
7. [ ] Enumerate the §14.2 and P3 Redis degradation contract: every behaviour must be correct with Redis empty or unreachable; a generic `POST` transition degrades to a duplicate-request error and never to a duplicate side effect; no candidate-path request is denied for lack of Redis. Deliverable: degradation card — the acceptance basis for `P7.14`.
8. [ ] Enumerate the §8.5 decimal obligations this feature must satisfy: `MathContext.DECIMAL128` intermediates, `NUMERIC(12,4)` stored raw scores, `NUMERIC(9,6)` stored full-precision percentages, one canonical `roundHalfUpToWholeNumber`, and the prohibition on binary floating point in scoring. Deliverable: decimal card. Acceptance: names `FEAT-GRD-001` as the owner of the evaluation sequence that consumes them.
9. [ ] List every consumer obligation this feature must satisfy before its dependants start, with the dependant feature and the sibling task that will adopt it. Deliverable: consumer-contract table covering the `tasks.md` and `tasks.md` adoption seams.

---

# Phase 2 – Architecture and Design

1. [ ] Design the typed-identifier scheme: one value type per identifier (`TenantId`, `CandidateId`, `AttemptId`, …), each wrapping a UUID, mutually non-assignable, with parsing that rejects malformed input at construction. Deliverable: typed-id specification. Acceptance: a `CandidateId` cannot be passed where a `TenantId` is expected — the id-confusion class that produces cross-tenant reads (`REQ-SEC-003`).
2. [ ] Design `ActorContext` as an immutable record with the §8.4 shape, and the closed `SystemActor` enumeration. Deliverable: actor-context specification. Acceptance: `tenantId` is optional only for platform-scope and system actors, and the type makes an absent actor unrepresentable rather than defaulted.
3. [ ] Design the `Clock` contract as the single time source, with a fixed-instant test implementation. Deliverable: clock specification. Acceptance: states that `Instant.now()` and `System.currentTimeMillis()` are unavailable to every caller, so `BR-ASMT-002`'s server-authoritative timing is testable rather than asserted.
4. [ ] Design the decimal conventions and the single canonical `roundHalfUpToWholeNumber`, including its behaviour at exactly `.5`, on negative values and at the stored-precision boundaries. Deliverable: decimal specification with a boundary table. Acceptance: the specification is the test oracle for `P7.6`, and `FEAT-GRD-001` inherits a proven primitive rather than a plausible one.
5. [ ] Design the `OutboxWriter` port: append an integration event to `outbox.outbox_event` inside the caller's transaction, with the event type, aggregate reference, payload and the originating correlation identifier. Deliverable: port specification. Acceptance: the port is framework-free; `FEAT-PLAT-004` supplies the adapter; the table name follows `TASK-PLAT3-DEFECT-004`.
6. [ ] Design the `IdempotencyStore` port and `IdempotencyKey` type: reserve-or-replay semantics returning `RESERVED`, `REPLAY(response)` or `UNAVAILABLE`, with a 24 h retention convention. Deliverable: port specification. Acceptance: `UNAVAILABLE` is an explicit outcome the caller must handle, not an exception that becomes a `500`.
7. [ ] Design the idempotency degradation behaviour per `P1.7`: on `UNAVAILABLE`, a generic `POST` transition returns a duplicate-request problem rather than performing an unguarded side effect, and no candidate-path route depends on the store at all. Deliverable: degradation design note.
8. [ ] Design the idempotency route rule: a route may accept `Idempotency-Key` only if it creates no durable domain record, and a route creating one must name the PostgreSQL unique index that protects it. Deliverable: rule specification. Acceptance: mechanically checkable from the route table plus a declared annotation, so it ships as a conformance rule.
9. [ ] Design the error-code catalogue as a declarative artifact keyed by `code`, carrying the `type` URI, `title`, `status`, the fixed `detail` string and the permitted extension fields. Deliverable: catalogue format specification. Acceptance: a code exists in exactly one place, so the response body and the client documentation cannot diverge.
10. [ ] Design the `ProblemDetailMapper` as the only response-body construction site, allowlist-based: an exception maps to a catalogue entry or to the generic non-disclosing default. Deliverable: mapper specification (`ARC-SEC-010`). Acceptance: states that no code path can construct a body from an exception message.
11. [ ] Design the mapper's own failure tolerance: a catalogue miss, a serialisation failure or a missing correlation identifier degrades to the generic response and increments a metric — never a leak, never a hang, never a second exception escaping the mapper. Deliverable: failure-mode design note.
12. [ ] Design the response allowlist test: enumerate every `ProblemDetail` the system can emit, assert each is in the catalogue, and assert no other body shape can leave the application. Deliverable: allowlist-test specification (CI 10, BLOCKING).
13. [ ] Design the correlation-identifier lifecycle: accepted from `X-Correlation-Id` only when it is a strictly valid ULID, otherwise generated; returned on every response; present in every log line, span and `ProblemDetail`; carrying no personal data. Deliverable: correlation specification. Depends on `P0.4`. Acceptance: an over-long or control-character-bearing header value is replaced, never echoed and never logged.
14. [ ] Design the `RequestContextPropagation` payload that replaces `tasks.md` `P4.6`'s placeholders, and the adoption sequence across the two sibling task lists. Deliverable: adoption design note naming each sibling task that must be re-verified.
15. [ ] Design `SecretFieldPattern` as a single kernel-owned pattern (`pin`, `otp`, `token`, `secret`, `password`, `key`, `authorization`) with the three consuming limbs and their owners. Deliverable: pattern specification (`ARC-OBS-002` alignment).
16. [ ] Design the kernel-purity conformance rule: `shared.kernel` imports no Spring, R2DBC, Jackson, Redis, module or infrastructure type. Deliverable: rule specification. Acceptance: the rule is part of the kernel's own deliverable, so the kernel cannot become impure between features.
17. [ ] Design the four conformance rules this feature contributes to CI stage 4 — `ActorContext` required on every write path, system actors from the enumeration, no ambient time source outside `Clock`, no `double`/`float` in scoring — and state which extend `tasks.md` `P4.23`'s R6 rules rather than duplicating them. Deliverable: rule set specification.
18. [ ] Design the kernel's package layout and its dependency direction, showing ports in `shared.kernel` and adapters in `platform.infra`. Deliverable: layout diagram plus a one-paragraph rationale. Acceptance: no adapter type is reachable from the kernel.

---

# Phase 3 – Data and Infrastructure

**No schema change.** This feature defines shared value types only (plan `Data impact`: "None").

1. [ ] Create the `shared.kernel` package with its Modulith descriptor, exposing only its port and value types. Deliverable: package plus descriptor, extending `tasks.md` `P4.3`. Acceptance: `ApplicationModules.of(...).verify()` still passes.
2. [ ] Add the build-level dependency constraint that `shared.kernel` has no framework dependency on the compile classpath. Deliverable: build configuration. Acceptance: adding a Spring import to the kernel fails the build at compile time as well as at conformance time.
3. [ ] Create the `platform.infra` package for the kernel's adapters, with its descriptor. Deliverable: package plus descriptor.
4. [ ] Add the Redis client dependency, resolved through the managed BOM and locked. Deliverable: dependency block plus lockfile update. Acceptance: CI stage 2 lockfile-drift gate stays green; the driver is absent from the `shared.kernel` classpath.
5. [ ] Add the Testcontainers Redis substrate to the CI stage 8 integration harness, alongside the existing PostgreSQL container. Deliverable: test harness extension, consistent with `tasks.md` `P3.3`.
6. [ ] Add a local `docker-compose` Redis service on a pinned image digest for developer use. Deliverable: compose extension. Acceptance: the integration suite runs locally with one command.
7. [ ] Wire the four `P2.17` conformance rules into the existing `conformanceTest` source set and CI stage 4 entry point. Deliverable: rule registration, extending `tasks.md` `P3.6`, `P3.7`.
8. [ ] Add the CI stage 10 entry point for the `ProblemDetail` allowlist test and the error-response limb of the secret-leak scan, runnable standalone. Deliverable: `ci/` script plus Gradle task. Acceptance: blocking, per §18.1 stage 10 and `NFR-SEC-002`.
9. [ ] Add `problem-detail-allowlist` to the `main` branch-protection required checks. Deliverable: protection configuration record extending `tasks.md` `P3.9`.
10. [ ] Publish the error-code catalogue as a build-time generated artifact so the OpenAPI document and the client documentation are generated from it, not written alongside it. Deliverable: catalogue generation step. Acceptance: a new code appears in the OpenAPI document without a second edit.

---

# Phase 4 – Backend Implementation

1. [ ] Implement the typed identifiers from `P2.1` with construction-time validation and no public raw-UUID accessor that invites confusion. Deliverable: identifier types plus their tests.
2. [ ] Implement `ActorContext` and the closed `SystemActor` enumeration from `P2.2`. Deliverable: types. Acceptance: no constructor or factory produces an actor-less or `"system"`-named context.
3. [ ] Implement the `Clock` port and its `SystemClock` adapter in `platform.infra`, plus the fixed-instant test implementation. Deliverable: port, adapter and test double.
4. [ ] Implement the decimal conventions and the canonical `roundHalfUpToWholeNumber` from `P2.4`. Deliverable: decimal utility. Acceptance: the only rounding function in the codebase; a second one fails review and `P7.7`'s assertion.
5. [ ] Implement the `OutboxWriter` port from `P2.5`, framework-free, with its event value type carrying the originating correlation identifier. Deliverable: port plus event type. Acceptance: `tasks.md` `P4.24`'s R7 rule now checks a real interface instead of a stub.
6. [ ] Implement the `IdempotencyStore` port, `IdempotencyKey` and the three-outcome result type from `P2.6`. Deliverable: port plus types.
7. [ ] Implement the `RedisIdempotencyStore` adapter with a 24 h TTL, storing the response alongside the key. Deliverable: adapter. Acceptance: a store or fetch failure yields `UNAVAILABLE` rather than propagating a client exception.
8. [ ] Implement the `Idempotency-Key` request filter applying reserve-or-replay per `P2.7`, including the degradation path. Deliverable: filter plus registration.
9. [ ] Implement the idempotency route rule from `P2.8` as a conformance rule over the route table. Deliverable: rule with an actionable failure message.
10. [ ] Implement the error-code catalogue from `P2.9` with its initial entry set covering the platform-level errors that exist now — validation failure, not found, conflict, unauthorised, forbidden, rate limited and the generic internal error. Deliverable: catalogue. Acceptance: each entry is complete; a partially specified entry fails catalogue validation.
11. [ ] Implement the `ProblemDetailMapper` from `P2.10` as the single construction site. Deliverable: mapper plus its WebFlux error-handler registration. Acceptance: no other class constructs a `problem+json` body, asserted by `P7.4`.
12. [ ] Implement the mapper's failure tolerance from `P2.11`: catalogue miss, serialisation failure and missing correlation identifier each degrade to the generic response and increment a metric. Deliverable: three fallback paths plus their tests.
13. [ ] Implement the correlation-identifier lifecycle from `P2.13`: strict ULID validation on `X-Correlation-Id`, generation on absence or invalidity, and echo on every response. Deliverable: implementation extending `tasks.md` `P4.7`'s filter. Depends on `P0.4`.
14. [ ] Implement `SecretFieldPattern` from `P2.15` and consume it in the mapper's field-emission path. Deliverable: pattern type plus its mapper usage. Acceptance: the pattern is exported for `FEAT-OBS-001` and `FEAT-AUD-001` to consume without copying.
15. [ ] Implement the real `RequestContextPropagation` payload and register it in `tasks.md` `P4.7`'s `WebFilter` and `P4.8`'s logging bridge. Deliverable: propagation implementation.
16. [ ] **Adopt the real types in `FEAT-PLAT-001`:** replace the `tasks.md` `P4.6` placeholder carriers with `TenantId` and `ActorContext`, and **delete** the placeholder types. Deliverable: adoption change set. Acceptance: a source-wide search finds no reference to a placeholder carrier, and `tasks.md` `P4.22`'s R5 signature rule is still green.
17. [ ] **Adopt the real `TenantId` in `FEAT-PLAT-002`:** update the `SecurityContextInitializer` decorator (`tasks.md` `P4.2`–`P4.5`) to the real type. Deliverable: adoption change set. Acceptance: `tasks.md` `P7.12`'s `ARC-VERIFY-024` adversarial suite is re-run and green after the change.
18. [ ] Implement the `ActorContext`-required conformance rule: no write path exists without an `ActorContext`, and system actors come from the enumeration (`ARC-VERIFY-011`). Deliverable: rule plus its failure message.
19. [ ] Implement the no-ambient-time conformance rule as the kernel-side completion of `tasks.md` `P4.23`, scoped so that only the `SystemClock` adapter may reference an ambient time source. Deliverable: rule.
20. [ ] Implement the no-binary-floating-point rule for scoring packages, as the kernel-side completion of `tasks.md` `P4.23`. Deliverable: rule. Acceptance: `double`, `float` and their boxed forms are all rejected, and the message names the decimal convention to use instead.
21. [ ] Implement the kernel-purity conformance rule from `P2.16`. Deliverable: rule. Acceptance: a Spring, Jackson, R2DBC, Redis or module import in `shared.kernel` fails the build; verified by `P7.5`.
22. [ ] Implement the four metrics from `TASK-PLAT3-OBS-001` — `problem_detail_emitted_total{code}`, `problem_detail_unmapped_total`, `idempotency_replay_total{outcome}`, `idempotency_store_unavailable_total`. Deliverable: metric instrumentation.

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-PLAT-003` is a backend kernel feature with no user interface. It does, however, fix
two contracts the frontend volumes consume: the RFC 9457 error body with its `code` catalogue, and the
`X-Correlation-Id` request and response header. Both are published in `P10.1` and `P8.1` so the frontend
volumes have a stable input.

---

# Phase 6 – Security and Hardening

1. [ ] Verify the allowlist denies by default: an exception with no catalogue entry produces the generic non-disclosing response, and adding a new exception type without a catalogue entry does not silently expose it. Deliverable: default-denial evidence.
2. [ ] Verify no exception message, stack trace, SQL fragment, provider error text or secret value can reach a response body, by attempting each source individually. Deliverable: five adversarial leak attempts, each recorded and refused (`REQ-SEC-010`, `REQ-RSLT-041`).
3. [ ] Verify the correlation identifier carries no personal data and is not derived from an actor, a tenant or an email. Deliverable: correlation-privacy review. Acceptance: support-traceable via the log store, not by inspection of the value itself.
4. [ ] Verify a hostile `X-Correlation-Id` — over-long, control characters, newline, JSON fragment, ANSI escape — is replaced rather than echoed, and never reaches a log line or a response. Deliverable: header-hardening evidence (`TASK-PLAT3-DEFECT-006`). Acceptance: asserted against the log sink output, not only the response.
5. [ ] Verify an idempotency replay cannot cross a tenant or an actor: the stored key includes the tenant, so a replay under a different tenant is a miss, not a leak of another tenant's response. Deliverable: replay-isolation evidence.
6. [ ] Verify the idempotency store holds no personal data beyond what the original response already returned to that same caller, and that entries expire at 24 h. Deliverable: store-content review.
7. [ ] Verify `SecretFieldPattern` is defined once and that no consuming limb carries its own copy. Deliverable: single-definition review.
8. [ ] Verify the mapper cannot be bypassed: no controller, filter or exception handler writes a response body directly. Deliverable: bypass review plus the `P7.4` assertion.
9. [ ] Confirm no credential or secret exists in the kernel, the catalogue or the Redis configuration, and that the Redis credential resolves from the secret manager. Deliverable: secret-scan result plus configuration review.
10. [ ] Review the error contract against the §13.6 threat rows for information disclosure and record how each is mitigated or where it is carried. Deliverable: threat-model conformance record.

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation of this feature: the mapper is on **every** failure path, so it must be proven
against injected faults at every layer rather than against a handful of expected exceptions.

1. [ ] Implement `ARC-VERIFY-013`: inject faults at the filter, controller, handler, domain, port-adapter, database and serialisation layers, and assert every resulting response is an allowlisted `ProblemDetail` carrying a correlation identifier. Deliverable: fault-injection suite in CI stage 10. Acceptance: coverage is per layer, and a layer with no injected fault fails the suite.
2. [ ] Extend the fault-injection suite with the Reactor termination signals — error, cancellation and timeout — since a cancelled exchange is where an error handler most often leaks or hangs. Deliverable: three additional cases.
3. [ ] Implement the `ProblemDetail` allowlist test from `P2.12`, blocking in CI stage 10. Deliverable: allowlist test. Acceptance: adding an emittable body shape without a catalogue entry fails the build.
4. [ ] Assert the mapper is the only construction site: a static check plus a negative test in which a direct body write fails the build. Deliverable: assertion plus negative test.
5. [ ] Add a negative test introducing a Spring import into `shared.kernel` and assert the purity rule fails the build. Deliverable: negative test with the import reverted and the failure retained as evidence.
6. [ ] Test the canonical rounding against the `P2.4` boundary table: exactly `.5`, negative values, the `NUMERIC(12,4)` and `NUMERIC(9,6)` precision limits, and the largest representable score. Deliverable: boundary test set. Acceptance: this is the primitive `FEAT-GRD-001` inherits, so it ships proven.
7. [ ] Add a negative test introducing a second rounding helper and a `double` into a scoring package, asserting both fail the build. Deliverable: two negative tests plus evidence.
8. [ ] Implement `ARC-VERIFY-011`: assert no write path exists without an `ActorContext` and that every system actor is from the enumeration, with a negative test for a no-arg write path and for a free-text actor name. Deliverable: rule assertion plus two negative tests.
9. [ ] Add a negative test for an ambient time source outside the `SystemClock` adapter, asserting the build fails. Deliverable: negative test.
10. [ ] Test the correlation-identifier lifecycle: a valid ULID is honoured, an invalid one is replaced, an absent one is generated, and the same value appears in the response, the log line, the span and the `ProblemDetail`. Deliverable: four-way join test (`NFR-OBS-002`).
11. [ ] Test the mapper's three failure-tolerance paths from `P4.12`, each asserting the generic response, the metric increment and the absence of a second escaping exception. Deliverable: three tests.
12. [ ] Integration-test the Redis idempotency adapter against Testcontainers: reserve, replay returning the identical stored response, and expiry after the TTL. Deliverable: integration test.
13. [ ] Test the idempotency route rule with a negative case: a route creating a durable record while accepting `Idempotency-Key` fails the build. Deliverable: negative test.
14. [ ] Integration-test the Redis-unavailable degradation from `P1.7`: the store returns `UNAVAILABLE`, a generic `POST` returns a duplicate-request problem, no duplicate side effect occurs, and no candidate-path route is denied. Deliverable: degradation test. Acceptance: run with the container stopped, not with a mocked failure.
15. [ ] Test typed-identifier non-assignability: a compile-time negative fixture proving a `CandidateId` cannot be passed where a `TenantId` is expected. Deliverable: compile-fail fixture.
16. [ ] Test context propagation of the real payload across `publishOn`/`subscribeOn` and a scheduler hop, extending `tasks.md` `P4.9`. Deliverable: propagation test.
17. [ ] Re-run `tasks.md`'s conformance suite after the `P4.16` adoption and confirm R5 and every R1–R8 rule is still green. Deliverable: adoption regression record.
18. [ ] Re-run `tasks.md` `P7.12`'s `ARC-VERIFY-024` adversarial suite after the `P4.17` adoption and retain the result. Deliverable: adoption regression record. Acceptance: the retained `L9` evidence artifact reflects the real `TenantId`, not the placeholder.
19. [ ] Run the error-response limb of the CI stage 10 secret-leak scan and confirm a clean baseline. Deliverable: scan result.
20. [ ] Register the retained artifacts in the §19.9 verification evidence register: the fault-injection report and the allowlist test result. Deliverable: register entries, with `TASK-PLAT3-DEFECT-002`'s missing-identifier gap noted.
21. [ ] Run the full pipeline on a clean checkout and confirm stages 4, 8 and 10 are blocking and green for this feature's contributions. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
22. [ ] Verify each acceptance outcome in the `FEAT-PLAT-003` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

1. [ ] Publish the `X-Correlation-Id` request and response header contract as a platform-wide interface. Deliverable: header contract record consumed by the frontend volumes and `FEAT-OBS-001`.
2. [ ] Confirm the kernel adds no configuration surface that differs per runtime role, so `FEAT-PLAT-006`'s three profiles share one kernel. Deliverable: configuration review.
3. [ ] State the rollback path: the kernel is code-only with no schema change, so a rollback is a code revert; the error-code catalogue is additive, and removing a published `code` is a breaking client change requiring the §10.6 version treatment. Deliverable: rollback statement.
4. [ ] Confirm the Redis adapter's absence is tolerated at startup — the application starts and serves the candidate path with Redis unreachable. Deliverable: startup-degradation evidence.
5. [ ] Hand the `OutboxWriter` port to `FEAT-PLAT-004`, the `ActorContext` to `FEAT-AUD-001`, the decimal conventions to `FEAT-GRD-001`, the correlation identifier and `SecretFieldPattern` to `FEAT-OBS-001`, and the error contract to `FEAT-SEC-001`. Deliverable: five interface handover records.
6. [ ] Confirm the adoption seams are closed before Phase 1 begins: `tasks.md` and `tasks.md` both build against the real types with their suites green. Deliverable: seam closure record referenced by the Phase 0 exit criteria.
7. [ ] Record the deferrals with their owning features: production Redis provisioning (**unowned** — `TASK-PLAT3-DEFECT-005`, escalated in `P0.5`); the outbox implementation and relay (`FEAT-PLAT-004`); the audit port and hash chain (`FEAT-AUD-001`); the scoring evaluation sequence and fixtures A–E (`FEAT-GRD-001`); logging, metric registration and dashboards (`FEAT-OBS-001`); authorization evaluation (`FEAT-IAM-003`); the per-operation unique indexes of the §10.5 table (each owning feature). Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register `problem_detail_emitted_total` by `code` and confirm it increments per mapped response. Deliverable: metric plus evidence.
2. [ ] Register `problem_detail_unmapped_total` and confirm it increments when an exception has no catalogue entry. Deliverable: metric plus evidence. Acceptance: a non-zero value means an unanticipated failure mode is reaching clients as a generic error — documented as a defect signal, not a nuisance counter.
3. [ ] Register `idempotency_replay_total` by outcome and `idempotency_store_unavailable_total`. Deliverable: two metrics plus evidence.
4. [ ] Confirm the correlation identifier is the join key across logs, metrics exemplars and traces end to end, including across a scheduler hop. Deliverable: `NFR-OBS-002` diagnosability evidence.
5. [ ] Raise `TASK-PLAT3-OBS-001` to `FEAT-OBS-001` and `FEAT-OPS-004`: propose a **P2** alert on a sustained `problem_detail_unmapped_total` rate with "identify the unmapped exception and add a catalogue entry" as its first action, and a panel showing the top emitted error codes. Deliverable: gap record with the proposed alert and panel definitions.
6. [ ] Write the operations runbook for a rising unmapped-exception rate: how to find the exception from the correlation identifier, why the client saw a generic error, and how to add a catalogue entry safely. Deliverable: runbook.
7. [ ] Write the operations runbook for Redis unavailability: the expected symptom set, the confirmation that no side effect was duplicated, and the confirmation that the candidate path is unaffected. Deliverable: runbook.
8. [ ] Confirm the §16.1 fields this feature supplies are present on every log line and that `actorId` is a safe identifier only. Deliverable: log-field conformance record.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the error-code catalogue as the normative client contract, including the `type` URI scheme, the `code` namespace and the stability guarantee. Deliverable: `docs/error-catalogue.md` plus the generated OpenAPI component.
2. [ ] Publish the §8.4 kernel ownership table from `P1.1` as the normative component→feature map. Deliverable: `docs/shared-kernel.md`.
3. [ ] Write the kernel usage guide for slice authors: which types are required parameters, why there is no no-arg write path, and how to obtain time. Deliverable: `docs/kernel-usage.md`.
4. [ ] Write the error-authoring guide: how to add a code, why an exception message may never reach a body, and what the allowlist test will reject. Deliverable: `docs/error-authoring.md` — the guide every later API feature follows.
5. [ ] Write the idempotency authoring guide: the decision rule between a PostgreSQL unique index and the `Idempotency-Key` header, with the §10.5 table as worked examples. Deliverable: `docs/idempotency.md`. Acceptance: states plainly that a durable-record operation may not rely on Redis.
6. [ ] Write the decimal authoring guide: the conventions, the canonical rounding, the stored precisions and the prohibition on binary floating point in scoring. Deliverable: `docs/decimal-arithmetic.md`.
7. [ ] Write the correlation-identifier guide for support and operations: what the identifier is, where it appears, how to trace with it, and why it contains no personal data. Deliverable: `docs/correlation-id.md`.
8. [ ] Publish the adoption-seam record from `P8.6` so a reader of the three Phase 0 task lists can see where the placeholder types ended. Deliverable: documented seam closure.
9. [ ] Raise `TASK-PLAT3-DEFECT-001` through `-006` and `TASK-PLAT3-OBS-001` to the Architecture Owner as documentation defects for the next baseline, each with the resolution this feature adopted, and raise `-001` to the `FEAT-PLAT-001` owner as a correction to `tasks.md` `P4.23`. Deliverable: seven defect records.
10. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, verification identifiers and retained artifacts. Deliverable: updated matrix rows.
11. [ ] Run a walkthrough with the engineering team covering the required-parameter rule, the allowlist mapper, the clock and decimal prohibitions, and the idempotency decision rule. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                              | Architecture reference | Tasks                                              | Verification                                        |
|---------------------------------------------------------------------|------------------------|----------------------------------------------------|-----------------------------------------------------|
| `REQ-SEC-010` non-leaking errors with a correlation identifier       | §10.4, §16.1           | `P2.9`–`P2.13`, `P4.10`–`P4.13`, `P6.2`            | `ARC-VERIFY-013`, CI 10 allowlist                   |
| `REQ-SEC-011` boundary validation                                    | §10.4                  | `P2.1`, `P4.1`, `P4.10`                            | Construction-time rejection plus `P7.15`            |
| `REQ-RSLT-041` no internal exception text to candidates              | §10.4                  | `P2.10`, `P4.11`, `P6.2`                           | `ARC-VERIFY-013`                                    |
| `BR-IAM-001`, `SC-008` every write attributable                      | §8.4                   | `P1.4`, `P2.2`, `P4.2`, `P4.18`                    | `ARC-VERIFY-011`                                    |
| `BR-ASMT-002` server-authoritative timing                            | §8.4                   | `P2.3`, `P4.3`, `P4.19`, `P7.9`                    | `ARC-VERIFY-003` R6 limb, CI 4                      |
| `REQ-RSLT-012`, `REQ-RSLT-024`, `BR-RSLT-005` exact arithmetic        | §8.4, §8.5             | `P1.8`, `P2.4`, `P4.4`, `P4.20`, `P7.6`, `P7.7`    | `ARC-VERIFY-003` R6 limb plus boundary fixtures     |
| `NFR-OBS-002` end-to-end diagnosability                              | §16.1, §16.3           | `P2.13`, `P4.13`, `P4.15`, `P7.10`, `P9.4`         | Four-way join test                                  |
| `NFR-REL-003` no duplicate side effect                               | §10.5                  | `P2.6`–`P2.8`, `P4.6`–`P4.9`, `P7.12`–`P7.14`      | Redis-unavailable degradation test                  |
| `CONSTRAINT-PLAT-001` reactive, no `ThreadLocal`                     | §8.4                   | `P2.14`, `P4.15`, `P7.16`                          | Propagation test across scheduler hops              |
| `CONSTRAINT-PLAT-004` no cross-module coupling                       | §8.4, §11.2            | `P2.5`, `P4.5`                                     | `ARC-VERIFY-006` static limb (`tasks.md` `P4.24`) |
| `ARC-SEC-010` mapper is the only construction site, allowlist-based  | §10.4                  | `P2.10`, `P2.12`, `P4.11`, `P6.8`, `P7.3`, `P7.4`  | CI 10 BLOCK                                         |
| `ARC-PLAT-010` `Idempotency-Key` is the only Redis-dependent mechanism | §10.5                | `P1.3`, `P1.7`, `P2.6`–`P2.8`, `P4.7`–`P4.9`       | `P7.12`–`P7.14`                                     |
| `ARC-PLAT-011` / `ADR-009` outbox for async propagation              | §8.4, §11.2            | `P2.5`, `P4.5`                                     | Port only; `FEAT-PLAT-004` owns the implementation  |
| `ARC-OBS-001`/`-002` structured logging and redaction                | §16.1                  | `P1.5`, `P2.15`, `P4.14`, `P9.8`                   | Pattern defined once; limbs owned elsewhere         |
| `ADR-013` scoring is a pure function                                 | §8.5                   | `P2.4`, `P4.4`, `P4.20`                            | Decimal primitive only; `FEAT-GRD-001` owns purity  |
| `ARC-VERIFY-003` slice anatomy and `domain` purity                   | §19.8                  | `P2.16`, `P4.19`–`P4.21`, `P7.5`                   | CI 4; kernel purity added to the R6 set             |
| `ARC-VERIFY-011` no write path without an `ActorContext`             | §19.8                  | `P4.18`, `P7.8`                                    | CI 4 plus two negative tests                        |
| `ARC-VERIFY-013` injected faults yield allowlisted bodies            | §19.8                  | `P7.1`, `P7.2`                                     | CI 10 BLOCK                                         |
| P3 — correct with Redis empty or unreachable                          | §4, §14.2              | `P1.7`, `P2.7`, `P7.14`, `P8.4`, `P9.7`            | Degradation test with the container stopped         |
| Adoption seam — `FEAT-PLAT-001` placeholders retired                  | §8.4                   | `P4.16`, `P7.17`                                   | Placeholder types deleted; R1–R8 still green        |
| Adoption seam — `FEAT-PLAT-002` real `TenantId`                       | §9.4, §12.3            | `P4.17`, `P7.18`                                   | `ARC-VERIFY-024` re-run and retained                |
| Observability: the error contract is measurable                       | §16.2                  | `P4.22`, `P9.1`–`P9.3`, `P9.5`                     | Metrics live; alert raised as `TASK-PLAT3-OBS-001`  |
| `PLAN-BLOCKER-001`                                                    | plan §10, §18.3        | `P0.1`                                             | Discharged by `tasks.md` `P0.1`–`P0.7`        |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence from
oversight.

| Excluded                                                                                                    | Owner                                         |
|-------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| Module boundaries, slice anatomy, rules R1–R8, the `WebFilter` and logging bridge, `TenantScopedQuery`       | `FEAT-PLAT-001`                               |
| Schemas, roles, grants, RLS, `SecurityContextInitializer`, `TransactionalCollaboration`                      | `FEAT-PLAT-002`                               |
| The outbox table, the `OutboxWriter` adapter and the broker relay                                            | `FEAT-PLAT-004`                               |
| Expand/contract discipline and CI stage 12                                                                   | `FEAT-PLAT-005`                               |
| Three runtime roles, scheduler singletons, graceful shutdown                                                 | `FEAT-PLAT-006`                               |
| The `AuditEmitter` port, the hash chain and the in-transaction emitter                                       | `FEAT-AUD-001`                                |
| The §8.5 canonical evaluation sequence, fixtures A–E and scoring purity                                      | `FEAT-GRD-001`                                |
| Logging and tracing infrastructure, metric registration, dashboards and alert rules                          | `FEAT-OBS-001`, `FEAT-OPS-004`                |
| Authorization evaluation, the `Policy` evaluator and route-to-policy resolution (`ARC-VERIFY-008`)            | `FEAT-IAM-003`, `FEAT-SEC-001`                |
| Extending the fault-injection and leak scan to every endpoint, and the `L6` release gate                     | `FEAT-SEC-001`                                |
| The per-operation PostgreSQL unique indexes of the §10.5 table                                               | The owning feature per operation              |
| Domain types, domain exceptions and per-module error codes belonging to a module                             | The owning feature per module                 |
| The OpenAPI breaking-change diff gate (`ARC-VERIFY-012`, CI 9)                                               | The owning contract feature                   |
| **Production Redis provisioning, sizing, HA and failover — unowned in `plan.md`**                            | **Unassigned; escalated in `P0.5`**            |
| Ratification of the architecture baseline as a governance act                                                | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] The `ProblemDetail` allowlist test is **BLOCKING** and green in CI stage 10 (`P3.8`, `P7.3`).
2. [ ] The secret-leak scan across error responses is BLOCKING and green (`P7.19`); the logging and audit limbs are `FEAT-OBS-001`'s and `FEAT-AUD-001`'s.
3. [ ] The two conformance rules named by the plan — no ambient time outside the clock, no `double` in scoring — are BLOCKING and green (`P4.19`, `P4.20`, `P7.7`, `P7.9`).
4. [ ] Every error response conforms to the problem-detail model and carries a correlation identifier (`P7.1`, `P7.10`).
5. [ ] No response contains a stack trace, SQL fragment, provider error text or secret (`P6.2`, `P7.1`).
6. [ ] No scoring code uses binary floating point (`P4.20`, `P7.7`).
7. [ ] No code outside the clock abstraction reads ambient current time (`P4.19`, `P7.9`).
8. [ ] Every write is attributable to an authenticated or identifiable system actor with tenant context where applicable (`P4.18`, `P7.8`).
9. [ ] The mapper degrades to the generic non-disclosing response on its own failure rather than leaking or hanging (`P4.12`, `P7.11`).

### Universal (plan §8.0), as far as this feature can discharge it

10. [ ] All mapped acceptance outcomes verified (`P7.22`).
11. [ ] Unit, slice and integration tests pass; the conformance suite passes including the four rules added here.
12. [ ] CI stage 4 is green for the code this feature adds, and the kernel-purity rule is green (`P7.5`).
13. [ ] Error responses carry a correlation identifier and leak no internal detail (`P7.1`, `P7.10`) — this feature is the mechanism by which every other feature discharges that clause.
14. [ ] Required telemetry exists (`P9.1`–`P9.3`); the rollback path is stated (`P8.3`).
15. [ ] No credential, secret or token exists in source (`P6.9`).
16. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
17. [ ] The adoption seams are closed and both sibling suites are green after adoption (`P7.17`, `P7.18`, `P8.6`).
18. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.10`).
19. [ ] **Not dischargeable by this feature, and recorded as such:** tenant isolation coverage by the isolation matrix (no endpoint is added; `FEAT-PLAT-002` and `FEAT-SEC-001` own it); in-transaction audit emission (`FEAT-AUD-001` — this feature supplies the actor context the emitter records); the OpenAPI breaking-change diff (CI stage 9, no API surface added here beyond the error component); migration verification (CI stage 12, `FEAT-PLAT-005` — this feature makes no schema change); the unmapped-exception **alert** (`TASK-PLAT3-OBS-001`, `FEAT-OBS-001`); production Redis provisioning (**unowned**, `TASK-PLAT3-DEFECT-005`).
