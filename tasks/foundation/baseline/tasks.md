# Foundation / Baseline Tasks

## Core Application Setup

|                       |                                                                                                                                                                                                                                                                                                        |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-PLAT-001`), §8.0 (universal DoR/DoD), §10 Phase 0                                                                                                                                                                                                                  |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §5.1, §6.2, §7.3, §8.1–8.2, §8.4, §9.2, §10.1–10.7, §18.1, §18.3, §19.8, §23.1                                                                                                                                                                    |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                                                                                                       |
| Dependencies          | None. This is the programme's root feature; `FEAT-PLAT-002…006`, `FEAT-AUD-001` and `FEAT-OBS-001` all depend on it                                                                                                                                                                                    |
| Generated on          | 2026-09-02                                                                                                                                                                                                                                                                                             |
| Methodology           | Clean architecture realised as vertical slices (architecture §5.1): the `domain` package is framework-free and depends on nothing; `slice` orchestrates and owns its own transaction and queries; `infra` holds adapters; dependencies point inward only. No shared service or repository layer exists |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                                                                                |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                                                                                                  |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                                                                             |

**Objective.** Establish the single deployable application partitioned into twelve modules mapping 1:1 onto
bounded contexts, with vertical-slice organisation inside each module, and make the conformance rules
mechanically enforced from the first commit rather than documented and hoped for.

### Confirmed implementation decisions

| Decision           | Choice                                                                                                                     | Consequence                                                                                     |
|--------------------|----------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Module realisation | Single Gradle module, twelve packages under `org.meldtech.platform.<module>`, boundaries enforced by Spring Modulith + ArchUnit | Boundary breach fails CI stage 4, not compilation. Matches architecture §5.1 verbatim           |
| Reference slice    | A conformance-only shipping slice in the `platform` module                                                                 | Canonical exemplar for every later slice, and the mutation subject for the R1–R8 negative tests |
| CI platform        | GitHub Actions; every gate is a Gradle task or `ci/` script called by a reusable workflow                                  | Gates run identically on a laptop and in CI; the platform YAML stays a thin caller              |

### Assumptions

1. **"Twelve modules" means the twelve schema-owning bounded contexts of architecture §9.2**: `tenancy`,
   `iam`, `academic`, `people`, `questionbank`, `authoring`, `examaccess`, `delivery`, `grading`, `result`,
   `correction`, `notification`. §6.2 additionally draws `audit` as a context, while §9.2 and `FEAT-PLAT-002`
   list `audit`, `outbox` and `platform` as schemas *in addition to* the twelve. Resolution adopted here: the
   conformance assertion counts exactly twelve **context modules**; `shared`, `platform`, `audit` and `outbox`
   are declared as an enumerated **platform-module** category — bound by the same `api`-only import rule but
   excluded from the count. `P1.3` records this as a decision note and raises the discrepancy.
2. **Only the ratification limb of CI stage 4a is in scope.** Limb (b), the `ARC-PERF-006` connection
   envelope, is owned by `FEAT-OPS-004`/`FEAT-OPS-005` (plan §8.7).
3. **Rules R9 and R10** (transaction-local security context, role assumption) are enumerated here but
   implemented by `FEAT-PLAT-002`, because their enforcement point is the connection-factory decorator and the
   database grant matrix, neither of which exists yet.
4. Reactive context **plumbing** (Reactor `Context`, `ContextSnapshot` logging bridge, `WebFilter`) is in scope
   here; the `TenantId` and `ActorContext` **types** that travel in it belong to `FEAT-PLAT-003`. The
   propagation mechanism is therefore built against a minimal placeholder carrier and adopts the real types
   when `FEAT-PLAT-003` lands.

### Blockers carried into this task list

| ID                      | Statement                                                                                                                                                                                                                                                                                                                                                                                 | Owning task      |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| `PLAN-BLOCKER-001`      | `ci/architecture-ratification.json` is `status: PENDING` with `baseline.gitCommit`, `baseline.blobSha256` and `countersignedBy` all `null`, and carries no `temporaryArchitectureGate` block. Per plan §10 Phase 0 entry criteria, **neither** entry condition is satisfied, so production implementation is not yet authorised. Ratification is a governance action outside this feature | `P0.1`–`P0.6`    |
| `TASK-PLAT1-DEFECT-001` | Architecture §18.3 step 9 and `ci/stage-4a.md` step 9 require a "Git blob **SHA-256**", but the reference shell uses `git rev-parse <tag>:<path>`, which returns the 40-hex Git blob **SHA-1**. Implementing the reference logic verbatim yields a gate that can never match its own field. The gate must define the algorithm explicitly                                                 | `P4.24`, `P10.7` |

---

# Phase 0 – Gate Prerequisites (governance, BLOCKING)

Nothing in Phases 1–10 may be merged until either `P0.5` or `P0.6` completes. Under a `temporaryArchitectureGate`
only preparatory work is authorised (`implementationAllowed: false`): Phase 1 agreements and Phase 2 design
only — no Phase 3 or Phase 4 code.

### Approvals
#### Architecture Owner Confirmation
Architecture Owner Confirmation
I confirm that architecture version 1.4 at tag arch-v1.4, pinned to commit aa7fdc5bd7b4a558e3fea19cf9c69b65ed64582b, is the authoritative architecture baseline to be implemented.

I also confirm that no superseding ADR or architecture revision affecting this baseline is currently in flight.

Decision: APPROVED.

#### Engineering Lead Review Confirmation
I confirm that I independently reviewed architecture version 1.4 at tag arch-v1.4, pinned to commit aa7fdc5bd7b4a558e3fea19cf9c69b65ed64582b.

I confirm that the baseline is sufficiently complete and actionable for implementation and that, to my knowledge, no superseding ADR or architecture revision affecting this baseline is currently in flight.

Decision: APPROVED FOR IMPLEMENTATION.

1. [*] Confirm with the Architecture Owner and Engineering Lead that architecture v1.4 at tag `arch-v1.4` is the baseline to be implemented, and that no superseding ADR revision is in flight. Deliverable: written confirmation recorded against `PLAN-BLOCKER-001`.
2. [*] Resolve tag `arch-v1.4` to its immutable commit and record it. Deliverable: `baseline.gitCommit` value. Acceptance: `git rev-list -n 1 arch-v1.4` is reproduced by a second reviewer.
3. [*] Compute the SHA-256 digest of `student_assessment/workspace/v3/be/architecture.md` **as it exists at that commit**, using `git cat-file blob <commit>:<path> | sha256sum`. Deliverable: `baseline.blobSha256` value. Depends on `P0.2`. Acceptance: recomputed independently and matched.
4. [*] Obtain the Engineering Lead countersignature required by the `ARC-DATA-029` four-eyes construction and populate `countersignedBy` with an approver distinct from the Architecture Owner. Deliverable: completed `approvals` block.
5. [*] Update `ci/architecture-ratification.json` to `status: RATIFIED` with the values from `P0.2`–`P0.4`, validated against `ci/architecture-ratification.template.json`. Depends on `P0.2`–`P0.4`. Acceptance: every one of the twelve §18.3 evaluation steps passes against the committed artifact.
6. [*] **Alternative path only if `P0.5` cannot complete in time** — author a dated `temporaryArchitectureGate` block per architecture §18.3, with `approved: true`, a stated reason, an `allowedUntil` date, both role approvers and `implementationAllowed: false`. Acceptance: the exception is a committed, dated artifact; no implicit skip exists anywhere in the pipeline. **Not applicable: `P0.5` completed, so no temporary gate was created.**
7. [*] Record which path was taken and the resulting authorisation scope in the Phase 0 entry-criteria record. Deliverable: one paragraph in the phase log naming the tasks unblocked.

---

# Phase 1 – Discovery and Analysis

Satisfies the feature's additional Definition of Ready: the module→context→schema mapping is agreed and the
rule set is enumerated.

1. [*] Extract the twelve context modules from architecture §6.2 and cross-check each against its §9.2 schema row. Deliverable: a `module → context → schema → owned aggregates` table.
2. [*] Cross-check the same table against the §6.2 relationship patterns (conformist, customer/supplier, published language) and record, per module, which other modules it is permitted to read through `api`. Deliverable: a permitted-dependency matrix — the input to the Modulith module descriptors.
3. [*] Resolve the module-count discrepancy between §6.2 (which draws `audit` as a context) and §9.2/`FEAT-PLAT-002` (which list `audit`, `outbox`, `platform` as additional schemas). Deliverable: a decision note fixing twelve context modules plus an enumerated platform-module category, raised to the Architecture Owner as a documentation defect for the next baseline.
4. [*] Enumerate rules R1–R10 from architecture §5.1 with, for each, the statement, the enforcement mechanism, the owning feature and the `ARC-VERIFY` id. Deliverable: the R1–R10 rule card. Acceptance: R1–R8 are attributed to this feature; R9–R10 to `FEAT-PLAT-002`.
5. [*] Map each `ARC-VERIFY` scenario this feature owns (`-001`, `-003`, the static limbs of `-004`/`-005`/`-006`, `-016`, and the `-008` startup limb) to its CI stage per §18.1 and §19.8. Deliverable: a verification-ownership table; no new verification identifiers are created.
6. [*] Enumerate the §10.1–§10.3 and §10.6–§10.7 API conventions this feature must fix as the shape for all later features: resource model, HTTP semantics, URI major versioning, pagination and filtering. Deliverable: a conventions checklist for `P2.8`.
7. [*] Confirm the universal Definition of Ready (plan §8.0) holds for this feature and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 2 – Architecture and Design

1. [*] Define the package taxonomy: `org.meldtech.platform.<module>.{api,api.dto,api.event,domain,domain.policy,slice,infra,migration}` exactly as architecture §5.1 prescribes. Deliverable: taxonomy document; no deviation without an ADR.
2. [*] Design the Spring Modulith module descriptor for each of the twelve context modules, declaring `api` as the only exported package and the allowed dependencies from `P1.2`. Deliverable: one `package-info.java` specification per module.
3. [*] Design the platform-module category (`shared`, `platform`, `audit`, `outbox`) with its export rules, and state why each is exempt from the twelve-count while remaining subject to R2. Deliverable: platform-module specification.
4. [*] Design the published in-process module `api` pattern for synchronous reads and commands: immutable record DTOs, no domain type crossing the boundary, and `@Transactional(propagation = MANDATORY)` on every `api` method so a callee can neither open nor commit a transaction of its own (architecture §8.4 `TransactionalCollaboration`). Deliverable: `ModuleApi` design note plus an interface template.
5. [*] Design the vertical-slice template: `Endpoint`, `Request`, `Response`, `Policy`, `Handler`, `Queries`, `SliceTest`, with the responsibility of each file stated in one sentence and the dependency direction fixed inward. Deliverable: annotated slice template. Acceptance: the template satisfies R1, R4, R5, R6 and R8 by construction.
6. [*] Design the `Policy` contract so that a slice without a declared policy cannot serve traffic: policy discovery at startup, default outcome `DENY`, and a non-disclosing failure response. Deliverable: `Policy` interface and startup-assertion design (`ARC-VERIFY-008` structural limb).
7. [*] Design the reactive context propagation that replaces `ThreadLocal`/MDC: a `WebFilter` seeding the Reactor `Context`, a `ContextSnapshot` hook bridging into logging, and the propagation contract across `flatMap`, scheduler hops and the outbox relay. Deliverable: design note naming the placeholder carrier and the adoption point for `FEAT-PLAT-003`'s `TenantId`/`ActorContext`.
8. [*] Specify the API-first conventions from `P1.6` as the normative shape for all later slices, and specify OpenAPI generation from slice contracts. Deliverable: API conventions document plus the OpenAPI generation approach (the CI 9 breaking-change diff itself is `FEAT-OPS-*`).
9. [*] Design the tracing unit as the slice boundary: span naming `<module>.<verbNoun>`, the attributes every span carries, and the correlation-id field name. Deliverable: span contract for `FEAT-OBS-001` to consume.
10. [*] Design the conformance reference slice: `GET /api/v1/platform/conformance-reference` in the `platform` module, returning build and conformance metadata only, with a real `Policy` requiring an operator authority (denying until `FEAT-IAM-003` provides an evaluator) and a tenant-parameterised `Queries` interface backed by an in-memory adapter until `FEAT-PLAT-002` provides a schema. Deliverable: slice design note. Acceptance: contains no business behaviour and is a valid subject for every one of R1–R8.
11. [*] Design the R1–R8 conformance suite: one ArchUnit or Modulith assertion per rule, the class-selection predicate for each, and the failure message each must emit. Deliverable: conformance-suite specification.
12. [*] Design the stage 4a ratification gate to the §18.3 contract: the twelve-step evaluation order, first-failure-stops semantics, the `BLOCK` versus `FAIL` distinction, the exact output required when the artifact is absent, and the resolved hash algorithm from `TASK-PLAT1-DEFECT-001`. Deliverable: gate specification. Acceptance: `ci/stage-4a.md` is cited as reference logic and explicitly marked non-normative where it diverges.
13. [*] Design the eight-case stage 4a self-test: fixture per case, expected outcome per case, and how results are retained as release evidence per §19.9. Deliverable: self-test specification.
14. [*] Review Phases 1–2 deliverables against architecture §7.3, clause by clause, and record how the skeleton satisfies API-first, single-owner aggregates, event-driven integration and statelessness — or which clause a later feature discharges. Deliverable: `CONSTRAINT-PLAT-004` conformance record.

---

# Phase 3 – Build and Infrastructure

1. [*] Initialise the Gradle project for `cbt-platform` with a pinned Java 21 toolchain and a reproducible wrapper. Deliverable: `settings.gradle.kts`, `build.gradle.kts`, wrapper. Acceptance: build succeeds on a clean checkout with no network-resolved toolchain.
2. [*] Add the Spring Boot 4 platform with WebFlux and R2DBC, and Spring Modulith, resolved through BOMs. Deliverable: dependency block. Acceptance: no blocking JDBC driver on any compile or runtime classpath except the migration entrypoint's, per `CONSTRAINT-PLAT-001`.
3. [*] Enable Gradle dependency locking and commit the lockfiles. Deliverable: lockfiles. Acceptance: CI stage 2 fails on lockfile drift.
4. [*] Configure the compiler with `-Werror`, plus Error Prone and NullAway scoped to `org.meldtech.platform`. Deliverable: static-analysis configuration. Acceptance: a deliberate nullability violation fails the build.
5. [*] Configure Spotless and Checkstyle with the agreed rule set and a formatting check task. Deliverable: configuration plus `spotlessCheck` wired into `check`.
6. [*] Add the ArchUnit and Spring Modulith test dependencies and a dedicated `conformanceTest` source set so conformance runs as its own CI stage independent of unit tests. Deliverable: source set plus `conformanceTest` Gradle task.
7. [*] Create the `ci/` script and Gradle task entry points for each stage this feature owns — 1 checkout provenance, 2 build, 3 static analysis, 4a ratification, 4 conformance, 5 unit, 7 slice, 13 documentation conformance — each runnable standalone on a developer machine. Deliverable: one entry point per stage.
8. [*] Author the GitHub Actions reusable workflows calling those entry points, in `ARC-CICD-024` order so a failed stage 4a prevents every later stage from executing. Deliverable: `.github/workflows/`. Acceptance: a forced stage 4a failure leaves stages 4 onward unexecuted, not merely failed.
9. [*] Configure branch protection on `main`: signed commits required, and `build`, `static-analysis`, `stage-4a`, `arch-conformance`, `unit-tests`, `slice-tests` and `docs-conformance` as required checks. Deliverable: protection configuration recorded as code or as a documented setting.
10. [*] Configure the unit-test coverage gate at ≥85% line coverage overall per §18.1 stage 5. Deliverable: coverage plugin configuration and threshold.
11. [*] Configure the slice-test gate so a slice package with no `SliceTest` fails the build, per §18.1 stage 7. Deliverable: gate implementation. Acceptance: adding a slice without a test fails CI.

---

# Phase 4 – Backend Implementation

1. [*] Create the application entry point and the base configuration for the single deployable. Deliverable: `CbtPlatformApplication` plus `application.yaml`. Acceptance: the application starts with no module-specific configuration present.
2. [*] Create the twelve context module packages with the full §5.1 sub-package skeleton and a `package-info.java` Modulith descriptor each, per `P2.2`. Deliverable: twelve module roots. Acceptance: `ApplicationModules.of(...).verify()` passes and reports exactly twelve context modules.
3. [*] Create the four platform module packages (`shared`, `platform`, `audit`, `outbox`) with their descriptors per `P2.3`, declaring boundaries only — no internals. Deliverable: four platform module roots.
4. [*] Implement the `ModuleApi` marker and the `api` interface template from `P2.4`, including the `MANDATORY` propagation contract. Deliverable: `shared` API contract types.
5. [*] Implement the `TenantScopedQuery` marker supertype whose contract is that every method takes a tenant identifier, so R5 has a subject to check (architecture §8.4). Deliverable: marker type plus its Javadoc contract.
6. [*] Implement the placeholder tenant and actor carrier types needed by the propagation mechanism, marked explicitly as superseded by `FEAT-PLAT-003`. Deliverable: carrier types with a stated adoption point.
7. [*] Implement the `WebFilter` that seeds the Reactor `Context` with a correlation identifier and the request carrier. Deliverable: filter plus registration.
8. [*] Implement the `ContextSnapshot` logging bridge so the correlation identifier appears in every log line without `ThreadLocal`. Deliverable: bridge plus logging configuration.
9. [*] Implement context propagation across scheduler hops and assert it survives `publishOn`/`subscribeOn`. Deliverable: propagation configuration plus a test.
10. [*] Implement the `Policy` interface and the policy-resolution registry with a default outcome of `DENY`. Deliverable: policy contract and registry.
11. [*] Implement the startup assertion that every registered route resolves to exactly one `Policy`, failing startup otherwise (`ARC-VERIFY-008` structural limb). Deliverable: startup validator. Acceptance: a route added without a policy prevents the context from starting.
12. [*] Implement the reference slice `Endpoint` for `GET /api/v1/platform/conformance-reference`, route and HTTP concerns only. Deliverable: `Endpoint`.
13. [*] Implement the reference slice `Request` and `Response` contracts as immutable records with bean validation on input. Deliverable: contract records.
14. [*] Implement the reference slice `Policy` requiring an operator authority and denying by default until an evaluator exists. Deliverable: `Policy`. Acceptance: the endpoint returns a non-disclosing denial in every environment until `FEAT-IAM-003` lands.
15. [*] Implement the reference slice `Handler` as the single transaction boundary, calling no other handler. Deliverable: `Handler`. Acceptance: satisfies R4 under the conformance suite.
16. [*] Implement the reference slice `Queries` as a `TenantScopedQuery` with a tenant-parameterised method and an in-memory adapter in `infra`, pending `FEAT-PLAT-002`. Deliverable: `Queries` plus adapter.
17. [*] Implement the reference slice `SliceTest` exercising the handler boundary with in-memory ports and no Spring context. Deliverable: `SliceTest`. Acceptance: demonstrates the anatomy is testable at its handler boundary, per the feature's testing expectations.
18. [*] Implement the R1 conformance rule: `slice` packages are leaves and are imported by nothing. Deliverable: ArchUnit rule with an actionable failure message.
19. [*] Implement the R2 conformance rule via Spring Modulith plus an ArchUnit backstop: a module imports only other modules' `api` packages. Deliverable: rule pair (`ARC-VERIFY-001`).
20. [*] Implement the R3 conformance rule: an SQL-string scan proving no `Queries` class references a schema other than its own module's. Deliverable: SQL scan rule (`ARC-VERIFY-002` static limb).
21. [*] Implement the R4 conformance rule: exactly one transaction per handler invocation, and no handler-to-handler call. Deliverable: rule (`ARC-VERIFY-003`).
22. [*] Implement the R5 conformance rule: every `TenantScopedQuery` method signature takes the tenant identifier. Deliverable: signature rule.
23. [*] Implement the R6 conformance rules: `domain` imports no Spring, R2DBC, Jackson or infrastructure type; no ambient time source outside the clock abstraction; no `double` or `float` in scoring packages. Deliverable: three rules (`ARC-VERIFY-004`/`-005` static limbs). Acceptance: the clock and decimal rules are installed now even though `FEAT-PLAT-003` supplies the abstractions, so the first violation is impossible rather than merely reviewable.
24. [*] Implement the R7 conformance rule: asynchronous cross-module state propagation goes through the outbox port only; the sole exception is a flow present in the `ADR-023` enumerated table. Deliverable: rule plus the compile-time enumeration stub (`ARC-VERIFY-006` static limb).
25. [*] Implement the R8 conformance rule: a handler mutating a tenant-scoped aggregate emits at least one audit event in the same transaction. Deliverable: rule (`ARC-VERIFY-010` static limb). Acceptance: the rule is active and blocking now; `FEAT-AUD-001` supplies the emitter it checks for.
26. [*] Implement the stage 4a ratification gate per `P2.12`, with the hash algorithm defined as SHA-256 over the document blob bytes at the resolved commit. Deliverable: `ci/stage-4a` executable plus its unit tests. Acceptance: every one of the twelve evaluation steps is individually exercised and stops at first failure.
27. [*] Implement the required absent-artifact output and exit code exactly as `ci/stage-4a.md` §5 mandates. Deliverable: gate output. Acceptance: the forbidden "skipping / continuing" output cannot be produced on any code path.
28. [*] Implement the eight-case self-test harness per `P2.13`, with one fixture per case and retained results. Deliverable: self-test suite plus its evidence artifact.

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-PLAT-001` is a backend platform feature with no user interface. The phase is retained
so numbering stays comparable across sibling task files.

---

# Phase 6 – Security and Hardening

1. [*] Verify that deny-by-default is structural, not configured: a slice lacking a `Policy` fails the conformance gate rather than serving traffic unprotected. Deliverable: evidence from a deliberate omission.
2. [*] Add a secret-scanning gate over the working tree and history, and confirm no credential, token or key material exists anywhere in the skeleton. Deliverable: scan configuration plus a clean baseline report.
3. [*] Confirm no environment-specific secret is required to build or start the skeleton, and that configuration placeholders resolve from a secret manager rather than committed values. Deliverable: configuration review note.
4. [*] Verify the reference slice's denial response is non-disclosing — no stack trace, SQL fragment or internal detail — pending `FEAT-PLAT-003`'s uniform error contract. Deliverable: response assertion test.
5. [*] Verify the stage 4a gate cannot be bypassed: no skip path, no advisory mode, no environment variable that downgrades it, and a failed gate prevents implementation and deployment stages from executing (`ARC-CICD-024`). Deliverable: bypass-attempt review with each attempt recorded and refused.
6. [*] Verify the `temporaryArchitectureGate` path, if in force, enforces `implementationAllowed: false` and expires on `allowedUntil` without renewal. Deliverable: expiry test. Depends on `P0.6`.
7. [*] Review the CI workflows for supply-chain exposure: pinned action versions by digest, least-privilege `GITHUB_TOKEN` permissions, no secret exposed to a pull-request-triggered workflow. Deliverable: workflow security review.

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation of this feature: every gate must be **proven to bite**, not merely to exist.

1. [ ] Assert `ApplicationModules.verify()` passes and reports exactly the twelve context modules, matching the §6.2 context map. Deliverable: Modulith verification test (`ARC-VERIFY-001`).
2. [ ] Assert the permitted-dependency matrix from `P1.2` holds: every declared allowed dependency exists and no undeclared cross-module dependency does. Deliverable: dependency assertion test.
3. [ ] Assert the reference slice satisfies the full §5.1 anatomy, file by file. Deliverable: anatomy test (`ARC-VERIFY-003`).
4. [ ] Introduce a deliberate R1 violation — import a `slice` class from outside its slice — and assert the build fails. Deliverable: negative test with the violation reverted and the failure retained as evidence.
5. [ ] Introduce a deliberate R2 violation — import another module's `domain` — and assert the build fails. Deliverable: negative test plus evidence.
6. [ ] Introduce a deliberate R3 violation — a `Queries` SQL string naming a foreign schema — and assert the build fails. Deliverable: negative test plus evidence.
7. [ ] Introduce a deliberate R4 violation — a handler calling another handler, and a second transaction inside one handler — and assert the build fails for both. Deliverable: negative test plus evidence.
8. [ ] Introduce a deliberate R5 violation — a tenant-scoped query method without the tenant parameter — and assert the build fails. Deliverable: negative test plus evidence.
9. [ ] Introduce deliberate R6 violations — a Spring import in `domain`, an ambient time call outside the clock, and a `double` in a scoring package — and assert the build fails for each. Deliverable: three negative tests plus evidence.
10. [ ] Introduce a deliberate R7 violation — a direct cross-module write outside the enumerated flow table — and assert the build fails. Deliverable: negative test plus evidence.
11. [ ] Introduce a deliberate R8 violation — a mutating handler emitting no audit event — and assert the build fails. Deliverable: negative test plus evidence.
12. [ ] Assert startup fails when a route resolves to no `Policy`. Deliverable: startup negative test (`ARC-VERIFY-008` structural limb).
13. [ ] Assert stage 4a **BLOCKS** when `architecture-ratification.json` is absent, with the mandated output and a non-zero exit code. Deliverable: self-test case 1.
14. [ ] Assert stage 4a **FAILS** on invalid JSON. Deliverable: self-test case 2.
15. [ ] Assert stage 4a **BLOCKS** when `status` is `PENDING`. Deliverable: self-test case 3.
16. [ ] Assert stage 4a **FAILS** on a wrong Git tag. Deliverable: self-test case 4.
17. [ ] Assert stage 4a **FAILS** on a commit mismatch, simulating a moved tag. Deliverable: self-test case 5.
18. [ ] Assert stage 4a **FAILS** on a blob-hash mismatch, simulating an edit to the document at an unchanged commit. Deliverable: self-test case 6.
19. [ ] Assert stage 4a **BLOCKS** when either required approval is missing or `false`, and when the two approvers are not distinct. Deliverable: self-test case 7.
20. [ ] Assert stage 4a **PASSES** on a fully valid ratification and on nothing less. Deliverable: self-test case 8.
21. [ ] Retain the stage 4a self-test result and the green stage 4a run as release evidence per §19.9 and plan §14.6, and register both in the verification evidence register. Deliverable: two retained artifacts with their register entries. Acceptance: retained, not merely observed — this is launch condition `L11`'s evidence requirement.
22. [ ] Implement the `ARC-VERIFY-016` documentation-conformance check as CI stage 13: any occurrence of `ARCH-REVIEW-###` or `SPEC-CONFLICT-###` in `requirements.md` or `architecture.md` that is not `REV<n>-` prefixed fails the build, with the §19.8 placeholder and grandfathered allowlists enumerated, not inferred. Deliverable: check plus its allowlist. Acceptance: the allowlist cannot be extended without review.
23. [ ] Assert the `ARC-VERIFY-016` check itself bites: introduce an unqualified citation and assert the build fails. Deliverable: negative test plus evidence.
24. [ ] Run the full pipeline on a clean checkout and confirm stages 1, 2, 3, 4a, 4, 5, 7 and 13 are blocking and green. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
25. [ ] Verify each acceptance outcome in the `FEAT-PLAT-001` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

This feature produces no deployable business capability; deployment mechanics belong to `FEAT-PLAT-006` (runtime
roles) and `FEAT-OPS-*` (image signing, SBOM, canary). Only what this feature must fix is listed.

1. [ ] Verify the build is reproducible: two clean builds of the same commit produce identical artifact digests. Deliverable: reproducibility evidence.
2. [ ] Confirm the artifact is a single deployable with no module-specific packaging, preserving `ARC-PLAT-001`. Deliverable: packaging review note.
3. [ ] Confirm branch protection and required checks from `P3.9` are active on `main` and cannot be bypassed by force push. Deliverable: protection verification.
4. [ ] State the rollback path for this feature — revert the skeleton commit range; no data or schema exists to migrate — and record it in the feature's DoD evidence. Deliverable: rollback statement.
5. [ ] Record the deferrals explicitly: image signing, SBOM attachment, immutable digest tagging, the three runtime profiles and the canary sequence, each with its owning feature. Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register the span-per-slice naming convention from `P2.9` as the platform tracing contract, and confirm the reference slice emits one span at its boundary. Deliverable: span contract plus evidence from a trace.
2. [ ] Publish the correlation-identifier field name and log-field contract for `FEAT-OBS-001` to consume, and confirm it carries no personal data. Deliverable: log field contract.
3. [ ] Confirm the correlation identifier propagates end to end through the reference slice, from filter to log line to span. Deliverable: propagation evidence (Phase 0 exit criterion).
4. [ ] Route conformance-gate and stage 4a failures to the engineering channel with the failing rule and the first-failure reason in the notification. Deliverable: CI notification configuration. Acceptance: a blocking failure is visible without opening the pipeline UI.
5. [ ] Confirm statelessness is structural in the skeleton: no in-memory state survives a request, and no session affinity is configured (`ARC-VERIFY-007` static half; the staging drill is `FEAT-PLAT-006`). Deliverable: statelessness review note.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Write the slice authoring guide: how to add a slice, which files are mandatory, which rules apply, and how to run the conformance suite locally. Deliverable: `docs/slice-authoring.md` pointing at the reference slice as the worked example.
2. [ ] Publish the R1–R10 rule card from `P1.4`, stating for each rule its enforcement mechanism, owning feature and failure message. Deliverable: `docs/conformance-rules.md`.
3. [ ] Publish the module map: twelve context modules with their schemas, aggregates and permitted dependencies, plus the platform-module category. Deliverable: `docs/module-map.md`.
4. [ ] Publish the API conventions document from `P2.8` as the normative shape for every later feature. Deliverable: `docs/api-conventions.md`.
5. [ ] Write the stage 4a runbook: what BLOCK means, what FAIL means, who resolves each, and how to re-ratify after a baseline change. Deliverable: `docs/runbook-stage-4a.md`.
6. [ ] Record the `P1.3` decision note on the twelve-versus-fourteen module count where later features will find it. Deliverable: decision note in `docs/decisions/`.
7. [ ] Raise `TASK-PLAT1-DEFECT-001` — the §18.3 Git-blob-SHA-256 versus SHA-1 discrepancy — to the Architecture Owner as a documentation defect for the next baseline, with the resolution this feature adopted. Deliverable: defect record.
8. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, test ids and retained artifacts. Deliverable: updated matrix rows.
9. [ ] Run a walkthrough with the engineering team covering the slice anatomy, the conformance suite and the local gate scripts. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                       | Architecture reference       | Tasks                                              | Verification                                       |
|--------------------------------------------------------------|------------------------------|----------------------------------------------------|----------------------------------------------------|
| `CONSTRAINT-PLAT-001` Java 21, Spring Boot 4, WebFlux, R2DBC | §2.3, §8.4                   | `P3.1`, `P3.2`, `P4.7`–`P4.9`                      | Stage 2 build; no blocking driver on request paths |
| `CONSTRAINT-PLAT-004` API-first                              | §7.3, §10.1–10.3, §10.6–10.7 | `P1.6`, `P2.8`, `P10.4`                            | `ARC-VERIFY-012` (gate owned later)                |
| `CONSTRAINT-PLAT-004` single-owner aggregates                | §6.2, §7.3, §9.2             | `P1.1`, `P1.2`, `P4.2`, `P4.19`                    | `ARC-VERIFY-001`, `ARC-VERIFY-003`                 |
| `CONSTRAINT-PLAT-004` no cross-context DB access             | §7.3, §9.2                   | `P4.20`                                            | `ARC-VERIFY-002` static limb                       |
| `CONSTRAINT-PLAT-004` event-driven integration               | §7.3, §11.2                  | `P4.24`                                            | `ARC-VERIFY-006` static limb                       |
| `CONSTRAINT-PLAT-004` stateless                              | §7.3, §17.3                  | `P9.5`                                             | `ARC-VERIFY-007` static half                       |
| `NFR-MAINT-001` independently evolvable capabilities         | §5.1, §23.1 `ARC-PLAT-002`   | `P2.1`–`P2.5`, `P4.2`, `P4.18`–`P4.25`             | Stage 4 blocking                                   |
| `ADR-001` / `ARC-PLAT-001` modular monolith                  | §7.1                         | `P4.1`–`P4.3`, `P8.2`                              | Single artifact review                             |
| `ADR-002` / `ARC-PLAT-002` vertical slices                   | §5.1                         | `P2.5`, `P4.12`–`P4.17`                            | `ARC-VERIFY-003`                                   |
| `ARC-PLAT-003` no speculative extraction indirection         | §7.5                         | `P2.14`                                            | Design review — no indirection layer added         |
| `ARC-PLAT-004` URI major versioning                          | §10.6                        | `P2.8`                                             | Convention fixed for later gate                    |
| `ARC-PLAT-009` per-module migration ownership                | §9.8                         | `P2.1`, `P4.2` (`migration` package declared only) | Boundary review                                    |
| `ARC-PLAT-010`–`012` idempotency, outbox, event contracts    | §10.5, §11.2, §11.3          | `P2.4`, `P4.24` (port and rule only)               | `ARC-VERIFY-006` static limb                       |
| Rules R1–R8 mechanically blocking                            | §5.1                         | `P1.4`, `P2.11`, `P4.18`–`P4.25`, `P7.4`–`P7.11`   | Stage 4 BLOCK, one negative test per rule          |
| Rules R9–R10 enumerated, deferred                            | §5.1, §9.2                   | `P1.4`                                             | Owned by `FEAT-PLAT-002`                           |
| `ARC-CICD-020…024` stage 4a ratification limb                | §18.3, `ci/stage-4a.md`      | `P2.12`, `P2.13`, `P4.26`–`P4.28`, `P7.13`–`P7.21` | Eight-case self-test, results retained             |
| `ARC-VERIFY-016` namespace-qualified citations               | §19.8, §18.1 stage 13        | `P7.22`, `P7.23`                                   | Stage 13 BLOCK                                     |
| `ARC-VERIFY-008` policy per route (structural limb)          | §19.8                        | `P2.6`, `P4.10`, `P4.11`, `P7.12`                  | Startup failure on omission                        |
| Deny-by-default as a structural property                     | Feature card, §12.2          | `P2.6`, `P4.14`, `P6.1`                            | Conformance gate                                   |
| `ARC-RISK-013` cross-schema coupling creep                   | §22.2                        | `P4.20`, `P7.6`                                    | Blocking gate rather than review convention        |
| Observability: slice as span boundary                        | §16.3                        | `P2.9`, `P9.1`–`P9.3`                              | Trace evidence                                     |
| `PLAN-BLOCKER-001`, launch condition `L11`                   | plan §12, §17.3, §18.3       | `P0.1`–`P0.7`, `P7.21`                             | Retained green stage 4a plus self-test             |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence from
oversight.

| Excluded                                                                                                                         | Owner                                                       |
|----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| Any business behaviour or domain rule                                                                                            | Phase 1–4 feature set                                       |
| Module internals beyond the boundary declaration                                                                                 | The owning feature per module                               |
| Stage 4a limb (b), the `ARC-PERF-006` connection envelope and its four capacity figures                                          | `FEAT-OPS-004`, `FEAT-OPS-005`                              |
| Schemas, tables, database roles, grant matrix, row-level security, transaction-local context (R9, R10)                           | `FEAT-PLAT-002`                                             |
| `TenantId`/`ActorContext` types, controlled clock, exact decimal type, uniform problem-detail contract, idempotency-key handling | `FEAT-PLAT-003`                                             |
| Transactional outbox implementation and broker relay                                                                             | `FEAT-PLAT-004`                                             |
| Expand/contract migration pipeline and CI stage 12                                                                               | `FEAT-PLAT-005`                                             |
| Three runtime roles, scheduler singletons, deployment topology                                                                   | `FEAT-PLAT-006`                                             |
| Audit store, hash chain, audit emitter                                                                                           | `FEAT-AUD-001`                                              |
| Logging, metrics, tracing infrastructure                                                                                         | `FEAT-OBS-001`                                              |
| Authorization evaluation                                                                                                         | `FEAT-IAM-003`                                              |
| Image signing, SBOM, digest tagging, canary and rollback automation                                                              | `FEAT-OPS-*`                                                |
| Ratification of the baseline as a governance act (as distinct from the gate that verifies it)                                    | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] CI stage 4 is BLOCKING and green.
2. [ ] `ARC-VERIFY-001` is green.
3. [ ] `ARC-VERIFY-003` is green.
4. [ ] A deliberately introduced violation fails the build — the gate is proven to bite, not merely to exist — for every one of R1 through R8.
5. [ ] CI stage 4a is implemented to the §18.3 contract, with the hash algorithm unambiguously defined.
6. [ ] The eight-case stage 4a self-test passes and its result is retained as release evidence.
7. [ ] Twelve module boundaries exist and match the §6.2 context map exactly, under the `P1.3` resolution.
8. [ ] No module imports another module's internals.
9. [ ] Every slice satisfies the §5.1 anatomy.
10. [ ] `CONSTRAINT-PLAT-004` is satisfied clause by clause per §7.3, or the discharging feature is named.

### Universal (plan §8.0), as far as this feature can discharge it

11. [ ] All mapped acceptance outcomes verified (`P7.25`).
12. [ ] Unit and slice tests pass; the conformance suite passes.
13. [ ] No credential, secret or token exists in source (`P6.2`).
14. [ ] The rollback path is stated (`P8.4`).
15. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
16. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.8`).
17. [ ] **Not dischargeable by this feature, and recorded as such:** tenant isolation and the isolation matrix (`FEAT-PLAT-002`); in-transaction audit emission (`FEAT-AUD-001` — the R8 *rule* is green here, the emitter it checks for is not yet built); the error-contract correlation identifier (`FEAT-PLAT-003`); the OpenAPI breaking-change diff (CI 9); migration verification (CI 12).
