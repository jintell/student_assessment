# CONSTRAINT-PLAT-004 Conformance Record

- Review result: **CONFORMS AT DESIGN LEVEL; IMPLEMENTATION EVIDENCE DEFERRED TO NAMED FEATURES**
- Reviewed on: 2026-09-04
- Scope: `FEAT-PLAT-001` Phase 1-2 deliverables (`P2.14`)
- Normative source: architecture §7.3

| Clause | Phase 1-2 structural design | Implementation/evidence owner | Current disposition |
|---|---|---|---|
| API-first | `api-conventions.md` fixes REST/JSON, `/api/v1`, audience boundaries, HTTP semantics, immutable slice DTO contracts, deterministic OpenAPI generation, and no hand-written parallel schema. `module-api.md` fixes published in-process contracts. | `FEAT-PLAT-001` implements the reference slice/generation; every feature supplies its contract; `FEAT-OPS-*` supplies CI stage 9's released-baseline breaking diff (`ARC-VERIFY-012`). | Design satisfied; runtime/generated evidence deferred. |
| DDD bounded contexts and single-owner aggregates | `module-map.md` maps exactly twelve contexts to twelve same-named schemas and sole-owned aggregates. Closed descriptors export only `api`; Decision 0001 separates four platform modules without weakening R2. | `FEAT-PLAT-001` creates/verifies module boundaries (`ARC-VERIFY-001`, `-003`); each business feature implements only its module's aggregates. | Design satisfied; module source/test evidence deferred. |
| Event-driven integration through the outbox | `platform-modules.md` exposes only `outbox::api`; `conformance-rules.md` and `conformance-suite.md` make R7 blocking. `module-api.md` preserves the single `ADR-023` synchronous exam-entry exception and forbids implicit expansion. | `FEAT-PLAT-001` installs the static R7 rule; `FEAT-PLAT-004` implements transactional outbox/relay; `FEAT-PLAT-002` implements enumerated composite roles; `ARC-VERIFY-006` provides combined evidence. | Design satisfied; outbox and database evidence deferred. |
| No cross-context database access | The module/schema map is one-to-one; R3 selects query adapters/resources and uses structured SQL relation extraction; module API visibility grants no schema access. | `FEAT-PLAT-001` implements the static scan; `FEAT-PLAT-002` supplies per-module roles, grants, RLS, and integration evidence (`ARC-VERIFY-002`). | Design satisfied; database backstop deferred. |
| Stateless services | `reactive-context-propagation.md` keeps per-request state in immutable Reactor context and reconstructs it across messages; the reference slice retains no request/session state; the slice/span contracts tolerate scheduler and replica changes. | `FEAT-PLAT-001` proves propagation; `FEAT-PLAT-006` supplies runtime roles/no-affinity configuration and staging failover evidence (`ARC-VERIFY-007`). | Design satisfied; replica-kill staging evidence deferred. |

## Review Findings

1. No Phase 1-2 deliverable introduces a second deployable, shared business service/repository layer, cross-schema query permission, in-memory session/timer state, or direct asynchronous broker publication.
2. The only synchronous cross-module write remains the closed `ADR-023` exam-entry flow. A Java `allowedDependencies` entry is not database authority.
3. The ratified architecture permits numerous synchronous `ModuleApi` relationships but enumerates only one multi-schema composite role while also prohibiting `REQUIRES_NEW`, second connections, and role switching. `module-api.md` therefore blocks any additional database-backed synchronous collaboration until the Architecture Owner supplies an ADR/grant resolution. This is a future-feature architecture gap, not permission to weaken R9/R10.
4. Architecture §6.4 requires delivery mutations to read authoritative `examaccess` principal state, while the atomic-entry flow requires `examaccess -> delivery`; declaring the reverse import would create a forbidden Modulith cycle. The descriptors retain the ratified entry direction and prohibit `delivery -> examaccess` until the Architecture Owner defines an acyclic contract/ownership mechanism.
5. The API breaking-change diff, outbox runtime, database grants/RLS, audit emitter, and stateless replica drill are correctly deferred and must not be claimed green from these documents alone.

No clause is contradicted by the skeleton design. Full conformance remains contingent on the named implementation and verification tasks.

- Reviewed by: OpenAI Codex execution agent
- Signed: `Codex / CONSTRAINT-PLAT-004 / P2.14 / 2026-09-04`
