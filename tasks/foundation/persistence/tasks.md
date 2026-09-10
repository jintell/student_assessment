# Task List — `FEAT-PLAT-002` ★ Schema-per-Module Persistence and Tenant Isolation Backstop

## Overview

|                       |                                                                                                                                                                                                                                                                                                      |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-PLAT-002`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 track (a)                                                                                                                                                                                               |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §5.1 (R3, R5, R7, R9, R10), §7.3, §8.3, §8.4, §9.2, §9.4, §9.8, §12.3, §15.2, §16.2, §16.4, §17.1, §18.1, §19.5, §19.8, §19.9, §24.3, `ADR-003`, `ADR-010`, `ADR-023`                                                                            |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                                                                                                     |
| Dependencies          | `FEAT-PLAT-001` (module boundaries). Depended on by `FEAT-PLAT-004`, `FEAT-PLAT-005`, `FEAT-PLAT-006`, `FEAT-AUD-001`, `FEAT-TENANT-001`, `FEAT-EXAM-007`, `FEAT-PRIV-004`, `FEAT-OPS-003`, `FEAT-SEC-001`                                                                                            |
| Generated on          | 2026-09-02                                                                                                                                                                                                                                                                                           |
| Methodology           | Clean architecture as vertical slices (architecture §5.1). This feature adds **no** service or repository layer: it supplies `infra`-layer adapters and database-level enforcement beneath the existing `Queries` port. `domain` stays framework-free; dependencies point inward only                 |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                                                                               |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                                                                                                |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                                                                           |

**Objective.** Give each module its own PostgreSQL schema and its own database role granted only on that
schema, and install the three independent tenant-isolation layers so that a missing tenant predicate returns
zero rows instead of another institution's data.

**Why this feature is `★`.** `SC-005` is zero cross-tenant data access; `RISK-TENANT-001`, `ARC-RISK-005`
and `ARC-RISK-026` are each rated **Critical**. One enforcement layer is a single point of failure for the
platform's core multi-tenancy promise. Three independent layers mean a defect in application code is caught
by the database, and a defect in the grant matrix is caught by row-level security.

### Confirmed implementation decisions

| Decision                     | Choice                                                                                                                                                                                            | Consequence                                                                                                                                       |
|------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Cross-cutting grant bootstrap | `ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA audit, outbox GRANT INSERT ON TABLES TO <module roles>`                                                                                 | The `INSERT` grant exists by construction the moment `FEAT-AUD-001` / `FEAT-PLAT-004` create their table. No placeholder tables, no back-fill task |
| RLS coverage                  | The DDL convention, plus a **blocking** `pg_class` catalogue gate over every table carrying a `tenant_id` column, plus one `platform.tenant_scope_probe` proving table                            | RLS is unskippable for tables that do not exist yet, and `ARC-VERIFY-005`/`-024` have a real subject inside Phase 0                                |
| PostgreSQL version            | **PostgreSQL 17**, pinned by image digest; Testcontainers for the CI stage 8 integration suite                                                                                                    | `PLAN-RECOMMENDATION` — no numbered requirement names a version. See `TASK-PLAT2-DEFECT-005`                                                       |
| Composite-role scope          | The mechanism and the closed flow table with its single MVP row (`app_txn_examentry`) ship here; the flow that uses it does not                                                                   | `FEAT-EXAM-007` consumes the role and owns `ARC-VERIFY-023`                                                                                        |
| Isolation-matrix ownership     | This feature generates the matrix from the route table and makes it a CI stage 10 gate over the endpoints that exist; `FEAT-SEC-001` extends it to every endpoint                                 | The generator, not a hand-maintained list, is the durable artifact — so a new endpoint without coverage fails the build                            |

### Assumptions

1. **Fifteen schemas, twelve module schemas.** Architecture §9.2 enumerates fifteen schemas; §7.3 asserts
   "12 modules ≡ 12 contexts ≡ 12 schemas". Resolution adopted here, consistent with `tasks.md` `P1.3`:
   twelve **module** schemas (`tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`,
   `examaccess`, `delivery`, `grading`, `result`, `correction`, `notification`) plus three **platform**
   schemas (`audit`, `outbox`, `platform`). Recorded as `TASK-PLAT2-DEFECT-003`.
2. **No business tables are created here.** Each owning feature defines its own tables (plan §8.1 out-of-scope).
   This feature creates schemas, roles, grants, default privileges, the RLS convention and its gate, and the
   single `platform.tenant_scope_probe` proving table.
3. **`TenantId` and `ActorContext` are `FEAT-PLAT-003`'s types.** The `SecurityContextInitializer` decorator is
   built against `FEAT-PLAT-001`'s placeholder carrier (`tasks.md` `P4.6`) and adopts the real types when
   `FEAT-PLAT-003` lands. Track (b) runs in parallel per plan §11.2, so the adoption point must be explicit.
4. **Flyway is the migration engine, on a separate JDBC datasource** (`ARC-PLAT-007`, plan L660). This feature
   authors its own migrations and the `--migrate-only` entrypoint's role wiring, but the expand/contract
   discipline, forbidden-operation rejection and CI stage 12 belong to `FEAT-PLAT-005`.
5. **`ARC-VERIFY-024` runs in CI stage 8 and again in staging.** §19.8 places it in Integration; the plan's DoD
   says staging; `L9` is a Phase 6 launch condition. Both, per `TASK-PLAT2-DEFECT-004`.
6. **PgBouncer is not adopted at MVP** (§15.2, `T-15`). Its two binding conditions are recorded as deferrals,
   not tasks.

### Blockers and defects carried into this task list

| ID                       | Statement                                                                                                                                                                                                                                                                                                                            | Owning task       |
|--------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|
| `PLAN-BLOCKER-001`       | `ci/architecture-ratification.json` is `status: PENDING` with `baseline.gitCommit`, `baseline.blobSha256` and `countersignedBy` all `null`. Per plan §10 Phase 0 entry criteria this gates Phase 0 **implementation**, not merely release. Discharged by `tasks.md` `P0.1`–`P0.7`; not restated here                            | `P0.1`            |
| `TASK-PLAT2-DEFECT-001`  | The plan's `FEAT-PLAT-002` card (§8.1) attributes "RLS returns zero rows when a tenant predicate is missing" to `ARC-VERIFY-018`. Architecture §19.8 defines `ARC-VERIFY-018` as *out-of-range configuration fails startup*; the RLS-backstop scenario is **`ARC-VERIFY-005`**. This task list implements `-005` and raises the mis-citation | `P1.7`, `P10.8`   |
| `TASK-PLAT2-DEFECT-002`  | The outbox table is `outbox.outbox_event` in §9.2, the grant matrix and `ARC-EXAM-014`, but `outbox.event` in §8.4 `OutboxWriter`. `outbox.outbox_event` adopted                                                                                                                                                                      | `P1.2`, `P10.8`   |
| `TASK-PLAT2-DEFECT-003`  | §7.3 asserts twelve schemas; §9.2 enumerates fifteen. Twelve module plus three platform schemas adopted                                                                                                                                                                                                                              | `P1.1`, `P10.8`   |
| `TASK-PLAT2-DEFECT-004`  | Plan DoD places `ARC-VERIFY-024` "green in staging"; §19.8 places it in Integration (CI 8); `L9` is Phase 6. Resolution: every commit in CI 8, re-run in staging for retained `L9` evidence                                                                                                                                          | `P7.12`, `P7.17`  |
| `TASK-PLAT2-DEFECT-005`  | No PostgreSQL version is pinned in requirements, architecture or plan, yet `current_setting(..., false)`, `FORCE ROW LEVEL SECURITY` and `pg_advisory_xact_lock` semantics are all version-sensitive. PostgreSQL 17 adopted as a `PLAN-RECOMMENDATION` pending Architecture Owner approval                                            | `P3.1`, `P10.8`   |
| `TASK-PLAT2-OBS-001`     | §16.4 raises `db_context_missing_total` to **P1** on any increment, but §16.5 defines no isolation dashboard panel — the nearest is panel 6 Platform health. Raised as an observability gap for `FEAT-OBS-001`                                                                                                                        | `P9.5`, `P10.8`   |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `tasks.md` `P0.1`–`P0.7` and is **not** restated here. Only this
feature's own entry conditions appear below. Under a `temporaryArchitectureGate` (`implementationAllowed:
false`) only Phase 1 and Phase 2 tasks are authorised — no Phase 3 or Phase 4 work.

1. [*] Confirm `tasks.md` `P0.5` or `P0.6` has completed and record which authorisation scope is in force. Deliverable: one-line entry in the phase log naming the tasks unblocked. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-001` has delivered the twelve module boundaries and the `Queries` / `TenantScopedQuery` marker types this feature enforces against. Deliverable: dependency-satisfied record. Depends on `tasks.md` `P4.2`, `P4.5`.
3. [*] Obtain agreement on the §9.2 ownership table and grant matrix from the Architecture Owner and Security, as the feature's additional Definition of Ready. Deliverable: signed DoR record.
4. [*] Obtain written confirmation that the `ARC-DATA-027` enumerated atomic-flow list is closed with exactly one MVP entry, and that adding a row is an ADR amendment rather than a configuration change. Deliverable: signed closure statement.
5. [*] Raise the PostgreSQL-17 `PLAN-RECOMMENDATION` (`TASK-PLAT2-DEFECT-005`) for approval before any Phase 3 task runs. Deliverable: approval record or a named alternative version.

---

# Phase 1 – Discovery and Analysis

Satisfies the feature's additional Definition of Ready: the ownership table and grant matrix are agreed and
the atomic-flow list is confirmed closed.

1. [*] Transcribe the architecture §9.2 schema-ownership table into this task list's working artifact: for each of the fifteen schemas, its owning module, principal tables and the cross-module references it holds by identifier. Deliverable: ownership table. Acceptance: twelve module schemas plus `audit`, `outbox`, `platform`; the `TASK-PLAT2-DEFECT-003` resolution is recorded against it.
2. [*] Transcribe the §9.2 grant matrix: `app_<module>` (twelve), `app_txn_<flow>` (one), the pool login roles `app_api` / `app_worker` / `app_pindist`, `app_migrator` and `app_readonly_ops`, with the exact grant set of each. Deliverable: grant matrix — the feature's durable artifact. Acceptance: pool login roles are recorded as holding **no direct object grants of any kind**, only role membership (`ARC-DATA-026`); the outbox table is named `outbox.outbox_event`.
3. [*] Transcribe the `ARC-DATA-027` composite-role row for exam entry: `app_txn_examentry` with its explicit minimum privilege list across `examaccess`, `delivery`, `people`, `authoring`, `tenancy`, `audit` and `outbox`, and the enumerated list of what it is deliberately **not** granted. Deliverable: composite-role grant card. Acceptance: the card states the set is strictly narrower than the union of the four module roles it replaces.
4. [*] Enumerate the §12.3 three isolation layers with, for each, its mechanism, the failure mode it defeats, and the task that implements it. Deliverable: three-layer card. Acceptance: layer 1 is `FEAT-PLAT-001`'s R5 signature rule, layer 2 is this feature's forced RLS, layer 3 is `FEAT-IAM-003`'s object-level check — ownership is explicit per layer.
5. [*] Enumerate rules R3, R5, R7, R9 and R10 from §5.1 with, for each, the verbatim statement, its enforcement mechanism and its owning task. Deliverable: rule card. Acceptance: R9 and R10 are attributed to this feature, per `tasks.md` assumption 3; R3, R5 and R7 are `FEAT-PLAT-001` rules that this feature backs with database grants.
6. [*] Enumerate the per-workload connection-pool figures from §17.1 and `ARC-PERF-003`: `cbt-api` pool 14 with 8 reserved for exam-path routes, `cbt-worker` pool 10, `cbt-pindist`, and the login role each uses. Deliverable: pool-to-role mapping — the input `FEAT-PLAT-006` consumes.
7. [*] Map each `ARC-VERIFY` scenario this feature owns or contributes to — `-002` (owned, CI 4 + integration), `-005` (owned, integration), `-024` (owned, integration + staging), `-004` (contributed, CI 10, co-owned with `FEAT-SEC-001`), `-006` (contributed, the granted-composite-roles limb), `-023` (contributed, grant list only; owned by `FEAT-EXAM-007`) — to its CI stage. Deliverable: verification-ownership table. Acceptance: no new verification identifier is created, and `TASK-PLAT2-DEFECT-001` is recorded against the `ARC-VERIFY-018` mis-citation.
8. [*] Enumerate the tenant-scoped versus platform-scoped table distinction per `ARC-TEN-003`, and the criterion the RLS gate uses to classify a table. Deliverable: classification rule. Acceptance: the criterion is mechanical (presence of a `tenant_id` column), not a maintained list, and platform-scope slices are enumerated rather than inferred.
9. [*] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 2 – Architecture and Design

1. [*] Design the schema and role naming taxonomy: schema names exactly as §9.2 spells them, `app_<module>` for module roles, `app_txn_<flow>` for composite roles, `app_api` / `app_worker` / `app_pindist` for pool login roles, `app_migrator`, `app_readonly_ops`. Deliverable: naming specification. Acceptance: no deviation without an ADR.
2. [*] Design the grant matrix as an executable artifact — a declarative source of truth from which both the migration DDL and the verification gate are derived. Deliverable: grant-matrix format specification. Acceptance: a grant can be added in exactly one place, so DDL and gate cannot diverge.
3. [*] Design the `ALTER DEFAULT PRIVILEGES` bootstrap for the `audit` and `outbox` schemas so the `INSERT` grant applies automatically to tables created later by `FEAT-AUD-001` and `FEAT-PLAT-004`. Deliverable: default-privileges design note. Acceptance: names `app_migrator` as the creating role, because default privileges are keyed on the creator; states that no `UPDATE` or `DELETE` on `audit.*` is ever granted.
4. [*] Design the `SecurityContextInitializer` R2DBC connection-factory decorator per §8.4 and `ARC-DATA-026`: (a) hand out pool connections under a login role with no direct object grants, (b) refuse to execute any statement outside a transaction that has installed its context, (c) issue `RESET ROLE; RESET ALL` on release including cancellation, timeout and exception paths. Deliverable: decorator design note. Acceptance: the release path is specified for every Reactor termination signal — `onComplete`, `onError` and `cancel` — because that is where R2DBC pool defects live.
5. [*] Design the transaction-local context installation per R9 and `ARC-DATA-018`: exactly `SET LOCAL ROLE <role>` then `SET LOCAL app.tenant_id <tenant>` as the transaction's first two statements, in that order, never at connection checkout. Deliverable: installation design note. Acceptance: states that `SET LOCAL` reverts at `COMMIT`/`ROLLBACK`, so no explicit restore path exists to be missed.
6. [*] Design the RLS convention: `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` **and** `FORCE ROW LEVEL SECURITY` on every tenant-scoped table, with policy `tenant_id = current_setting('app.tenant_id', false)::uuid`. Deliverable: RLS DDL template. Acceptance: the `false` second argument is mandatory so a missing setting **raises** rather than returning null; `FORCE` is mandatory so a table owner cannot bypass the policy.
7. [*] Design the blocking RLS catalogue gate: a query over `pg_class` / `pg_namespace` / `pg_attribute` asserting that every table in a module schema carrying a `tenant_id` column has `relrowsecurity AND relforcerowsecurity` and at least one policy referencing `app.tenant_id`. Deliverable: gate specification. Acceptance: the gate is derived from the catalogue, not a maintained list, so a future feature cannot add a tenant table without RLS.
8. [*] Design the `pg_roles` / `information_schema` grant-diff gate asserting the live database grants equal the declared matrix from `P2.2`, with no extra grant and no missing grant. Deliverable: gate specification (`ARC-VERIFY-002` database limb).
9. [*] Design the `platform.tenant_scope_probe` proving table: the minimum tenant-scoped table needed to exercise `ARC-VERIFY-005` and `ARC-VERIFY-024` inside Phase 0, carrying no business meaning and no personal data. Deliverable: probe design note. Acceptance: it is a conformance artifact, explicitly not a business table, and is a valid subject for every RLS assertion.
10. [*] Design the `TransactionalCollaboration` component per §8.4 and `ARC-EXAM-014`: the initiating handler owns exactly one connection for the whole transaction, carried in the Reactor `Context` and never a `ThreadLocal`; it installs the composite role and tenant context as the first statements; module `api` methods it invokes are `Propagation.MANDATORY`. Deliverable: component design note. Acceptance: the permitted `(flow, composite role)` pairs are a **compile-time enumeration, not a parameter**.
11. [*] Design the R9 conformance rule (context installed as the transaction's first statements; connection reset on release) and the R10 conformance rule (a handler assumes either its own module role or a composite role in the `ADR-023` table, with no role switch thereafter). Deliverable: two rule specifications with their failure messages.
12. [*] Design the `search_path`-per-module configuration and state how it interacts with the module role's grants so a cross-schema query fails at the database as well as at the conformance gate (§7.3). Deliverable: `search_path` design note.
13. [*] Design the cross-schema foreign-key prohibition check: a catalogue query asserting no `pg_constraint` of type `f` spans two schemas. Deliverable: gate specification. Acceptance: complements `FEAT-PLAT-001`'s static SQL scan with a runtime assertion, so `ARC-VERIFY-002` holds at both levels.
14. [*] Design `@PlatformScope` handling per `ARC-TEN-003`: the enumerated platform-scope marker installed in place of a tenant id, audit rows written with `tenant_id = NULL` plus an explicit platform-context marker. Deliverable: platform-scope design note. Acceptance: states explicitly that no generic "bypass tenant filter" facility exists.
15. [*] Design the tenant-isolation matrix generator: enumerate tenant-scoped endpoints from the route table and emit, per endpoint × {read, write, enumerate}, the assertion that an actor in tenant A cannot reach a tenant B resource and receives `404`, never `403`. Deliverable: generator specification. Acceptance: generated from the route table so a new endpoint without coverage fails the build; `FEAT-SEC-001` extends coverage, this feature owns the generator.
16. [*] Design the migration layout: per-module Flyway locations `classpath:db/migration/<module>` per `ARC-PLAT-009`, and the separate `--migrate-only` JDBC entrypoint running as `app_migrator` per `ARC-PLAT-007`. Deliverable: migration layout note. Acceptance: states that the application's R2DBC pool has **no DDL privilege at all**; expand/contract discipline and CI 12 are `FEAT-PLAT-005`.
17. [*] Design the four isolation metrics and their two alerts per §16.2 and §16.4, and name the emission point of each inside the decorator. Deliverable: telemetry design note.

---

# Phase 3 – Data and Infrastructure

1. [*] Pin PostgreSQL 17 by image digest for local development, CI and staging, and record the digest as build configuration. Deliverable: pinned image reference. Depends on `P0.5`. Acceptance: the same digest is used in all three environments; the version is not resolved by tag at runtime.
2. [*] Add a local `docker-compose` PostgreSQL service on the pinned digest, with the settings the isolation model depends on made explicit rather than defaulted. Deliverable: compose file. Acceptance: a developer can run the integration suite with one command.
3. [*] Add the Testcontainers harness for PostgreSQL 17 as the CI stage 8 substrate, reusing a single container across the suite where test isolation permits. Deliverable: test harness. Acceptance: integration tests run against real PostgreSQL with real migrations and real RLS, per plan §14.1.
4. [*] Add Flyway on a dedicated JDBC datasource in a separate `--migrate-only` entrypoint per `ARC-PLAT-007`. Deliverable: migration entrypoint. Acceptance: the reactive request path carries no JDBC driver and no DDL privilege; the entrypoint runs to completion before any pod serves traffic.
5. [*] Configure the per-module Flyway locations `classpath:db/migration/<module>` per `ARC-PLAT-009`, with independent versioning per module. Deliverable: Flyway configuration. Acceptance: each module's migration history is independent of every other's.
6. [*] Author the migration creating the twelve module schemas with `AUTHORIZATION app_migrator`. Deliverable: schema migration. Acceptance: `app_migrator` owns every schema, so it is the only DDL-capable principal.
7. [*] Author the migration creating the three platform schemas `audit`, `outbox` and `platform`, also owned by `app_migrator`. Deliverable: platform-schema migration.
8. [*] Author the migration creating the twelve `app_<module>` roles as `NOLOGIN` roles, granting `SELECT, INSERT, UPDATE, DELETE` on the module's own schema only. Deliverable: module-role migration. Acceptance: no module role holds any grant on any other module's schema.
9. [*] Author the `ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA audit, outbox GRANT INSERT ON TABLES` statements per `P2.3`, so tables created later by `FEAT-AUD-001` and `FEAT-PLAT-004` are insertable by every module role without a back-fill. Deliverable: default-privileges migration. Acceptance: no `UPDATE` or `DELETE` default privilege is granted on `audit`; verified by `P7.6`.
10. [*] Author the migration creating the `app_txn_examentry` composite role with the explicit minimum privilege list from `P1.3`. Deliverable: composite-role migration. Acceptance: the role is **not** a member of any module role, and the grant list is written out statement by statement rather than derived from a union.
11. [*] Author the migration creating the pool login roles `app_api`, `app_worker` and `app_pindist` with `LOGIN`, **no direct object grants**, and only `GRANT`ed membership of the module and composite roles each workload may assume. Deliverable: login-role migration. Acceptance: `app_api` cannot read any table until a transaction installs a role — verified by `P7.4`.
12. [*] Author the migration creating `app_migrator` (DDL-capable, used only by the migration entrypoint) and `app_readonly_ops` (`SELECT` on read-replica diagnostic views, no PII-bearing columns). Deliverable: operational-role migration.
13. [*] Author the migration creating `platform.tenant_scope_probe` per `P2.9`, with `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY` and the `current_setting('app.tenant_id', false)::uuid` policy. Deliverable: probe migration. Acceptance: the table is documented in-migration as a conformance artifact, not a business table.
14. [*] Configure the per-workload connection pools from `P1.6` — `cbt-api` 14 connections with 8 reserved for exam-path routes (`ARC-PERF-003`), `cbt-worker` 10, `cbt-pindist` — each bound to its own login role. Deliverable: pool configuration. Acceptance: the reserved exam-path pool is a separate pool, not a soft reservation; the figures are the input `FEAT-PLAT-006` and `FEAT-OPS-005` consume.
15. [*] Configure the `search_path` per module datasource per `P2.12`. Deliverable: datasource configuration.
16. [*] Source every role password from the external secret manager, with no credential in source or in a committed configuration file. Deliverable: secret-resolution configuration. Acceptance: `P6.6` confirms the tree is clean.
17. [*] Add the CI stage 8 entry point running the integration suite against Testcontainers PostgreSQL, runnable standalone on a developer machine. Deliverable: Gradle task plus `ci/` script, consistent with `tasks.md` `P3.7`.

---

# Phase 4 – Backend Implementation

1. [ ] Implement the declarative grant-matrix source of truth from `P2.2`, from which both the migration DDL and the `P4.16` gate are derived. Deliverable: grant-matrix artifact plus its parser.
2. [ ] Implement the `SecurityContextInitializer` connection-factory decorator skeleton wrapping the R2DBC `ConnectionFactory` per `P2.4`. Deliverable: decorator plus its registration.
3. [ ] Implement transaction-local context installation in the decorator: `SET LOCAL ROLE <role>` then `SET LOCAL app.tenant_id <tenant>` as the transaction's first two statements, in that order (R9). Deliverable: installation path. Acceptance: no statement can execute before them; installation happens inside the transaction, never at connection checkout.
4. [ ] Implement the pre-context statement refusal: a statement attempted on a connection whose transaction has not installed its context is refused by the decorator. Deliverable: refusal path. Acceptance: the refusal is defence in depth — the login role's absence of grants means the database would refuse it anyway, and `P7.4` asserts both.
5. [ ] Implement `RESET ROLE; RESET ALL` on connection release across every termination signal — completion, error and cancellation — plus read-timeout and statement-timeout paths. Deliverable: release path. Acceptance: a unit test drives each signal individually; this is the `ARC-RISK-026` mitigation point.
6. [ ] Implement the platform-scope installation path per `P2.14`: the enumerated platform-scope marker in place of a tenant id, restricted to platform-administrator or an enumerated system actor. Deliverable: platform-scope path. Acceptance: no code path installs "no tenant filter"; the marker is enumerated and audited.
7. [ ] Implement `TransactionalCollaboration` per `P2.10`: single connection owned by the initiating handler and carried in the Reactor `Context`, single transaction, composite role and tenant installed as the first statements, `Propagation.MANDATORY` on invoked module `api` methods. Deliverable: component. Acceptance: exactly one role assumption per transaction, with no role switching thereafter (R10).
8. [ ] Implement the compile-time `(flow, composite role)` enumeration containing its single entry, exam entry → `app_txn_examentry`, such that adding an entry is a source change reviewed as an ADR amendment. Deliverable: enumeration type. Acceptance: the pair cannot be supplied as configuration or as a runtime parameter.
9. [ ] Implement the R9 conformance rule: every transaction installs its context as its first statements, and no code path opens a transaction without going through the decorator. Deliverable: ArchUnit rule with an actionable failure message.
10. [ ] Implement the R10 conformance rule: a handler assumes only its own module role or a composite role present in the `P4.8` enumeration, and no role switch occurs after the transaction's first statement. Deliverable: ArchUnit rule.
11. [ ] Implement the R10 database limb: a `pg_roles` grant audit asserting the composite roles actually granted in the database exactly match the `ADR-023` enumerated table. Deliverable: grant-audit check (`ARC-VERIFY-006` contribution).
12. [ ] Implement the blocking RLS catalogue gate from `P2.7`. Deliverable: gate implementation. Acceptance: a table with a `tenant_id` column and no forced RLS fails the build; verified by `P7.9`.
13. [ ] Implement the cross-schema foreign-key prohibition check from `P2.13`. Deliverable: gate implementation. Acceptance: a foreign key spanning two schemas fails the build; verified by `P7.7`.
14. [ ] Implement the RLS DDL template from `P2.6` as a reusable migration fragment every later feature applies to its own tenant-scoped tables. Deliverable: migration template plus its usage note.
15. [ ] Implement the tenant-isolation matrix generator from `P2.15`, emitting the per-endpoint × {read, write, enumerate} assertion set from the route table. Deliverable: generator. Acceptance: an endpoint absent from the matrix fails the build rather than passing silently.
16. [ ] Implement the grant-diff gate from `P2.8`: the live database grant set equals the declared matrix, with no extra and no missing grant. Deliverable: gate implementation (`ARC-VERIFY-002` database limb).
17. [ ] Implement the four isolation metrics from `P2.17` — `db_context_install_failure_total` by role, `db_context_missing_total`, `db_role_assumption_total` by role, `db_connection_reset_failure_total` — emitted from the decorator. Deliverable: metric instrumentation.
18. [ ] Implement the composite-role narrowness assertion as executable code: the `app_txn_examentry` grant set is strictly narrower than the union of the `examaccess`, `delivery`, `people` and `authoring` module roles. Deliverable: assertion. Acceptance: asserted rather than assumed, per plan §8.4 `FEAT-EXAM-007`.

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-PLAT-002` is a backend persistence and security feature with no user interface. The
phase is retained so numbering stays comparable across sibling task files.

---

# Phase 6 – Security and Hardening

1. [ ] Verify layer 1 in isolation: with RLS temporarily disabled on the probe table, a query missing its tenant predicate returns another tenant's row — demonstrating that layer 1 alone is insufficient and that the R5 signature rule is what prevents it. Deliverable: layered-defence evidence with RLS restored. Acceptance: run in an ephemeral container only, never against a shared database.
2. [ ] Verify layer 2 in isolation: with the tenant predicate deliberately removed from a query, forced RLS returns zero rows (`ARC-VERIFY-005`). Deliverable: backstop evidence.
3. [ ] Verify layer 3 boundary: a cross-tenant reference returns a non-disclosing `404`, never `403`, so existence is not leaked. Deliverable: response assertion. Acceptance: the assertion is on the status code and the body, and names `FEAT-IAM-003` as the owner of the evaluator it exercises.
4. [ ] Confirm no generic tenant-filter bypass facility exists anywhere in the codebase — no flag, no configuration value and no test-only hook reachable in a non-test profile (`ARC-TEN-003`). Deliverable: bypass-attempt review with each attempt recorded and refused.
5. [ ] Confirm the composite role's deliberate exclusions hold: no `UPDATE`/`DELETE` on `delivery.answer` or `delivery.answer_operation`; no write on `people`, `authoring` or `tenancy`; no access to `grading`, `result`, `correction`, `notification`, `questionbank`, `iam` or `platform`; no `UPDATE`/`DELETE` on `audit.*`. Deliverable: exclusion verification, one assertion per clause.
6. [ ] Add the secret-scanning gate over the working tree and history for database credentials, and confirm every role password resolves from the secret manager. Deliverable: scan configuration plus a clean baseline report.
7. [ ] Confirm `app_readonly_ops` cannot reach a PII-bearing column: the granted views expose none, and the role holds no direct table grant. Deliverable: read-only role review.
8. [ ] Confirm `app_migrator` is reachable only from the migration entrypoint and that no application profile can assume it. Deliverable: DDL-privilege review (`ARC-PLAT-007`).
9. [ ] Review the isolation model against the §13.6 threat rows for cross-cutting tenant isolation and record how each is mitigated or where it is carried. Deliverable: threat-model conformance record.

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation of this feature: every isolation layer must be **proven to bite adversarially**,
not merely observed to pass. All integration tests run against real PostgreSQL 17 with real migrations and
real RLS (plan §14.1).

1. [ ] Assert `ARC-VERIFY-002` static limb holds with the composite role in place: no slice SQL references a foreign schema. Deliverable: CI stage 4 assertion, extending `tasks.md` `P4.20`.
2. [ ] Assert `ARC-VERIFY-002` database limb: no module role holds any foreign-schema grant, and the live grant set equals the declared matrix. Deliverable: integration assertion using `P4.16`.
3. [ ] Assert `ARC-VERIFY-005`: with the tenant predicate deliberately removed from a probe-table query, the result set is empty. Deliverable: integration test.
4. [ ] Assert a statement issued before its transaction installs context is **refused for want of privilege**, not silently executed — tested at the database level with the decorator's own refusal disabled, so the grant matrix is proven to be the backstop. Deliverable: integration test.
5. [ ] Assert `current_setting('app.tenant_id', false)` **raises** rather than returning null when no context is installed. Deliverable: integration test. Acceptance: the raised error is asserted by class, not by message text.
6. [ ] Assert `FORCE ROW LEVEL SECURITY` defeats an owner-role read of the probe table. Deliverable: integration test.
7. [ ] Assert no cross-schema foreign key exists, via the `P4.13` catalogue check, and that introducing one fails the build. Deliverable: assertion plus negative test.
8. [ ] Assert every module role's grant set is exactly its own schema plus `INSERT` on `outbox.outbox_event` and `audit.audit_event`, and that no module role holds `UPDATE` or `DELETE` on `audit.*`. Deliverable: integration test.
9. [ ] Introduce a deliberate violation — a table with a `tenant_id` column and no forced RLS — and assert the `P4.12` gate fails the build. Deliverable: negative test with the violation reverted and the failure retained as evidence.
10. [ ] Introduce a deliberate R9 violation — a statement executed before context installation, and a connection returned without reset — and assert the build fails for both. Deliverable: two negative tests plus evidence.
11. [ ] Introduce a deliberate R10 violation — a handler assuming a role not in the enumeration, and a role switch after the first statement — and assert the build fails for both. Deliverable: two negative tests plus evidence.
12. [ ] Implement `ARC-VERIFY-024`, the **adversarial pooled-connection reuse suite**: interleave transactions for tenants A and B and for different module roles across success, cancellation, read-timeout, statement-timeout and exception paths, forcing pool reuse of the same physical connection. Assert no statement ever executes with a stale, absent or foreign tenant or role context; a statement attempted outside an initialised transaction is refused for want of privilege; `current_setting(..., false)` raises; `FORCE ROW LEVEL SECURITY` defeats an owner-role read. Deliverable: adversarial suite in CI stage 8. Acceptance: the suite forces physical connection reuse rather than assuming it — pool size is constrained to one where a case requires it.
13. [ ] Extend `ARC-VERIFY-024` with a small-pool saturation case proving reset-on-release holds when every connection is recycled under contention. Deliverable: saturation case.
14. [ ] Assert the composite-role narrowness property from `P4.18` in an integration test against the live grant set. Deliverable: narrowness test. Acceptance: this is the `ARC-VERIFY-023` grant-list contribution; the atomicity proof itself is `FEAT-EXAM-007`.
15. [ ] Assert the `ALTER DEFAULT PRIVILEGES` bootstrap works end to end: create a table in `audit` as `app_migrator` in a test migration, and confirm a module role can `INSERT` into it without any further grant and cannot `UPDATE` or `DELETE` it. Deliverable: integration test. Acceptance: this is the contract `FEAT-AUD-001` and `FEAT-PLAT-004` rely on.
16. [ ] Assert the tenant-isolation matrix from `P4.15` passes for every tenant-scoped endpoint that currently exists, and that removing an endpoint's coverage fails the build. Deliverable: CI stage 10 assertion plus negative test (`ARC-VERIFY-004` contribution).
17. [ ] Re-run `ARC-VERIFY-024` in staging and retain the result as the `L9` evidence artifact, per `TASK-PLAT2-DEFECT-004`. Deliverable: retained "Pooled-connection security-context adversarial report" (§19.9). Acceptance: retained, not merely observed — a passing run does not discharge `L9` without the artifact.
18. [ ] Register the retained artifacts in the §19.9 verification evidence register: the adversarial report and the tenant-isolation matrix. Deliverable: two register entries.
19. [ ] Run the full pipeline on a clean checkout and confirm stages 4, 8 and 10 are blocking and green for this feature's contributions. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
20. [ ] Verify each acceptance outcome in the `FEAT-PLAT-002` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

1. [ ] Confirm the migration entrypoint runs to completion as a Kubernetes `Job` before any pod serves traffic, and that no application replica holds DDL privilege. Deliverable: deployment ordering evidence (`ARC-PLAT-007`).
2. [ ] Confirm each workload deploys with its own login role and pool size from `P3.14`, and that no workload can assume a role outside its granted membership. Deliverable: workload-to-role verification. Acceptance: the mapping is the input `FEAT-PLAT-006` consumes for its three profiles.
3. [ ] State the rollback path: schemas, roles and grants are additive, so a rollback reverts the application without dropping a schema; role and grant revocation is a separate, reviewed migration. Deliverable: rollback statement. Acceptance: no rollback path drops a schema containing data.
4. [ ] Verify the connection-envelope figures this feature contributes — pool sizes per workload — are reported for `ARC-PERF-006`, and record that the envelope inequality itself is enforced by CI stage 4a limb (b), owned by `FEAT-OPS-004`/`FEAT-OPS-005`. Deliverable: envelope input record.
5. [ ] Record the deferrals explicitly with their owning features: expand/contract discipline and CI stage 12 (`FEAT-PLAT-005`), runtime profiles and advisory-lock singletons (`FEAT-PLAT-006`), PgBouncer adoption and its two binding conditions (§15.2, not at MVP). Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register `db_context_install_failure_total` by role and confirm it increments when a slice attempts a role it is not a member of. Deliverable: metric plus evidence.
2. [ ] Register `db_context_missing_total` and confirm it increments when a statement is refused for want of context. Deliverable: metric plus evidence. Acceptance: a non-zero value is a latent isolation defect, not a nuisance — documented as such.
3. [ ] Register `db_role_assumption_total` by role and `db_connection_reset_failure_total`. Deliverable: two metrics plus evidence.
4. [ ] Configure the §16.4 alerts: **P1** on any increment of `db_context_missing_total`, **P2** on `db_context_install_failure_total > 0`. Deliverable: alert rules. Acceptance: both fire in a drill, not merely on paper.
5. [ ] Raise `TASK-PLAT2-OBS-001` to `FEAT-OBS-001`: a P1 alert exists with no dashboard panel to triage it. Deliverable: gap record with a proposed panel definition for panel 5 or 6.
6. [ ] Write the operations runbook for a `db_context_missing_total` P1: what the alert means, why the backstop held, how to find the defective calling path, and why it is not silenceable. Deliverable: runbook.
7. [ ] Confirm isolation-violation attempts and context-installation failures are observable and alertable rather than silent, as the feature's observability expectation requires. Deliverable: observability conformance record.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the §9.2 ownership table from `P1.1` as the normative module→schema→tables map. Deliverable: `docs/schema-ownership.md`.
2. [ ] Publish the grant matrix from `P1.2` as the feature's durable artifact, including the pool-login-roles-hold-nothing rule. Deliverable: `docs/grant-matrix.md`.
3. [ ] Publish the three-layer isolation model from `P1.4` with the failure mode each layer defeats and the feature owning each. Deliverable: `docs/tenant-isolation.md`.
4. [ ] Publish the R9/R10 rule card from `P1.5` with enforcement mechanism and failure message per rule, extending `tasks.md`'s `docs/conformance-rules.md`. Deliverable: updated rule card.
5. [ ] Write the composite-role amendment procedure: adding a flow is an ADR amendment with a Solution Architect, Engineering Lead and Security approval, not a configuration change. Deliverable: `docs/composite-role-amendment.md`. Acceptance: states that a second composite role is a meaningful architectural event.
6. [ ] Write the tenant-scoped table authoring guide: the RLS DDL template, the `tenant_id` column convention, and what the catalogue gate will reject. Deliverable: `docs/tenant-scoped-tables.md` — the guide every later feature follows.
7. [ ] Write the migration authoring note for module owners: own-schema only, per-module Flyway location, no cross-schema foreign key, no DDL from the application. Deliverable: `docs/module-migrations.md`.
8. [ ] Raise `TASK-PLAT2-DEFECT-001` through `-005` and `TASK-PLAT2-OBS-001` to the Architecture Owner as documentation defects for the next baseline, each with the resolution this feature adopted. Deliverable: six defect records.
9. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, verification ids and retained artifacts. Deliverable: updated matrix rows.
10. [ ] Run a walkthrough with the engineering team covering the grant matrix, the R9 installation protocol, the reset-on-release hazard and how to author a tenant-scoped table. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                          | Architecture reference     | Tasks                                    | Verification                                            |
|-----------------------------------------------------------------|----------------------------|------------------------------------------|---------------------------------------------------------|
| `REQ-SEC-003` tenant isolation on every access                  | §12.3, §3.1                | `P1.4`, `P4.3`–`P4.6`, `P4.12`, `P6.1`–`P6.3` | `ARC-VERIFY-004`, `-005`, `-024`                        |
| `REQ-SEC-011` ownership before modification                      | §12.3 layer 3              | `P6.3`                                   | Non-disclosing `404`; evaluator owned by `FEAT-IAM-003` |
| `REQ-TENANT-002`, `BR-TENANT-001` tenant scoping                 | §9.2, §12.3                | `P1.1`, `P1.8`, `P4.14`                  | RLS catalogue gate (`P4.12`)                            |
| `NFR-SEC-001` isolation enforcement                              | §12.3                      | `P4.2`–`P4.5`                            | `ARC-VERIFY-024`                                        |
| `NFR-SEC-002` isolation suite as a release gate                  | §19.5, §18.1 stage 10      | `P4.15`, `P7.16`                         | CI stage 10 BLOCK                                       |
| `CONSTRAINT-PLAT-002` PostgreSQL persistence                     | §9.2                       | `P3.1`–`P3.7`                            | Stage 8 integration                                     |
| `CONSTRAINT-PLAT-004` no cross-context DB access                 | §7.3, §9.2                 | `P3.8`, `P4.13`, `P4.16`, `P7.1`, `P7.2` | `ARC-VERIFY-002` both limbs                             |
| `SC-005` zero cross-tenant data access                           | §12.3, §19.5               | `P4.15`, `P7.16`                         | Tenant-isolation matrix                                 |
| `ADR-003` schema per module, per-module roles                    | §9.2                       | `P3.6`–`P3.8`, `P4.1`, `P4.16`           | `ARC-VERIFY-002`                                        |
| `ADR-010` RLS as the isolation backstop                          | §9.4, §12.3                | `P2.5`–`P2.7`, `P4.3`, `P4.12`, `P4.14`  | `ARC-VERIFY-005`, `-024`                                |
| `ADR-023` enumerated composite-role collaboration                | §8.3, §9.2 `ARC-DATA-027`  | `P1.3`, `P2.10`, `P3.10`, `P4.7`, `P4.8`, `P4.18` | `ARC-VERIFY-006`, `-023` (grant limb), `P7.14`  |
| `ARC-DATA-010` one schema, one role, grants on own schema only   | §9.2                       | `P1.1`, `P1.2`, `P3.6`, `P3.8`           | `ARC-VERIFY-002`                                        |
| `ARC-DATA-010` cross-schema FK prohibited                        | §9.2                       | `P2.13`, `P4.13`, `P7.7`                 | Catalogue check plus negative test                      |
| `ARC-DATA-018` `ENABLE` + `FORCE` RLS, transaction-local setting | §9.4, §12.3                | `P2.6`, `P3.13`, `P4.3`, `P4.14`         | `ARC-VERIFY-005`, `P7.5`, `P7.6`                        |
| `ARC-DATA-026` login roles hold no object grants; reset on release | §9.4                     | `P3.11`, `P4.4`, `P4.5`, `P7.4`, `P7.13` | `ARC-VERIFY-024`                                        |
| `ARC-DATA-027` closed enumerated flow table                      | §9.2                       | `P0.4`, `P4.8`, `P4.11`                  | `ARC-VERIFY-006` granted-roles limb                     |
| `ARC-PLAT-007` Flyway on a separate JDBC entrypoint, no DDL on the pool | §9.8                | `P3.4`, `P6.8`, `P8.1`                   | Deployment ordering evidence                            |
| `ARC-PLAT-009` per-module migration ownership                    | §9.8                       | `P3.5`, `P10.7`                          | Own-schema-only review                                  |
| `ARC-PERF-003` reserved exam-path pool                           | §15.2, §17.1               | `P1.6`, `P3.14`                          | Pool configuration review                               |
| `ARC-TEN-003` platform scope enumerated, no generic bypass       | §12.3                      | `P1.8`, `P2.14`, `P4.6`, `P6.4`          | Bypass-attempt review                                   |
| Rule R9 transaction-local context installation                   | §5.1, §8.4                 | `P1.5`, `P2.5`, `P4.3`, `P4.9`, `P7.10`  | Decorator plus `ARC-VERIFY-024`                         |
| Rule R10 role assumption                                          | §5.1                       | `P1.5`, `P2.11`, `P4.10`, `P4.11`, `P7.11` | ArchUnit plus `pg_roles` grant audit                  |
| Rules R3, R5, R7 database backstop                                | §5.1, §7.3                 | `P1.5`, `P3.8`, `P4.16`                  | Grants make the static rules bite at runtime            |
| `ARC-RISK-005` isolation defect leaking data (Critical)          | §22.2                      | `P6.1`–`P6.3`, `P7.3`, `P7.16`           | Three independent layers, each proven separately        |
| `ARC-RISK-026` pooled connection retaining context (Critical)    | §22.2                      | `P4.5`, `P7.12`, `P7.13`, `P7.17`        | `ARC-VERIFY-024` adversarial, retained                  |
| `ARC-RISK-013` cross-schema coupling creep                        | §22.2                      | `P4.13`, `P4.16`, `P7.1`, `P7.2`         | Blocking gate rather than review convention             |
| Observability: isolation failures are not silent                  | §16.2, §16.4               | `P4.17`, `P9.1`–`P9.4`                   | P1 and P2 alerts fired in a drill                       |
| Launch condition `L9`                                             | §24.3                      | `P7.17`, `P7.18`                         | Retained adversarial report                             |
| `PLAN-BLOCKER-001`                                                | plan §10, §18.3            | `P0.1`                                   | Discharged by `tasks.md` `P0.1`–`P0.7`            |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence from
oversight.

| Excluded                                                                                                     | Owner                                        |
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| Table definitions for business entities in any module schema                                                 | The owning feature per module                |
| Module boundaries, the slice anatomy and rules R1–R8                                                          | `FEAT-PLAT-001`                              |
| `TenantId` and `ActorContext` types, controlled clock, exact decimal type, problem-detail mapper              | `FEAT-PLAT-003`                              |
| The `outbox.outbox_event` table definition and the broker relay                                               | `FEAT-PLAT-004`                              |
| Expand/contract discipline, forbidden-operation rejection, lock-duration measurement, CI stage 12             | `FEAT-PLAT-005`                              |
| Three runtime profiles, scheduler advisory-lock singletons, graceful shutdown sequencing                      | `FEAT-PLAT-006`                              |
| The `audit.audit_event` table definition, hash chain and the in-transaction audit emitter                     | `FEAT-AUD-001`                               |
| The exam-entry flow itself and `ARC-VERIFY-023` atomicity under fault injection                               | `FEAT-EXAM-007`                              |
| Authorization evaluation and the object-level tenant check of §12.3 layer 3                                   | `FEAT-IAM-003`                               |
| Extending the tenant-isolation matrix to every endpoint and making it the `L6` release gate                   | `FEAT-SEC-001`                               |
| Logging, metrics and tracing infrastructure; dashboard panels                                                 | `FEAT-OBS-001`                               |
| CI stage 4a limb (b), the `ARC-PERF-006` envelope inequality and its four capacity figures                    | `FEAT-OPS-004`, `FEAT-OPS-005`               |
| Read-replica and 3-AZ replication topology provisioning                                                       | `FEAT-OPS-003`, Platform Ops                 |
| Retention, partitioning and legal-hold behaviour of the `platform` schema tables                              | `FEAT-PRIV-004`                              |
| Tenant provisioning and lifecycle                                                                             | `FEAT-TENANT-001`                            |
| PgBouncer adoption, and its two binding conditions — re-running `ARC-VERIFY-024` through the pooler, and moving advisory locks to a non-pooled path or `pg_advisory_xact_lock` | Post-MVP (§15.2, `T-15`) |
| Ratification of the architecture baseline as a governance act                                                 | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] `ARC-VERIFY-002` is green in CI — both the static SQL limb and the database grant limb.
2. [ ] `ARC-VERIFY-005` is green in CI — RLS returns zero rows when a tenant predicate is missing. *(Implemented as `-005`, not `-018`; see `TASK-PLAT2-DEFECT-001`.)*
3. [ ] `ARC-VERIFY-024` is green in CI stage 8 **and** re-run green in staging, with the adversarial report retained.
4. [ ] The tenant-isolation matrix is a BLOCKING release gate at CI stage 10 for the endpoints that exist, generated from the route table.
5. [ ] A deliberately omitted tenant predicate is demonstrated to return zero rows.
6. [ ] Every module reads and writes only its own schema.
7. [ ] No cross-schema foreign key exists.
8. [ ] A query issued without an installed tenant context returns zero rows or raises.
9. [ ] A statement issued before the transaction installs its context has no privilege and fails.
10. [ ] A pooled connection never carries another request's tenant or module context.
11. [ ] The composite-role grant set is strictly narrower than the union of the module roles it replaces.

### Universal (plan §8.0), as far as this feature can discharge it

12. [ ] All mapped acceptance outcomes verified (`P7.20`).
13. [ ] Unit, slice and integration tests pass; the conformance suite passes.
14. [ ] CI stage 4 is green for the code this feature adds, including R9 and R10.
15. [ ] Tenant isolation is enforced and covered by the isolation matrix (`P4.15`, `P7.16`).
16. [ ] Required telemetry exists (`P9.1`–`P9.4`); the rollback path is stated (`P8.3`).
17. [ ] No credential, secret or token exists in source (`P6.6`).
18. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
19. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.9`).
20. [ ] **Not dischargeable by this feature, and recorded as such:** in-transaction audit emission (`FEAT-AUD-001` — this feature grants the `INSERT` and forbids `UPDATE`/`DELETE`, but the emitter does not yet exist); the correlation identifier in error responses (`FEAT-PLAT-003`); the OpenAPI breaking-change diff (CI stage 9); migration verification and expand/contract compliance (CI stage 12, `FEAT-PLAT-005`); matrix coverage of endpoints that do not yet exist (`FEAT-SEC-001`); exam-entry atomicity under fault injection (`FEAT-EXAM-007`, `ARC-VERIFY-023`, architecture condition `A3`).
