# Task List — `FEAT-TENANT-001` Tenant Onboarding and Institutional Hierarchy

## Overview

|                       |                                                                                                                                                                                                                                                  |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../../plan/plan.md` §8.2 (`FEAT-TENANT-001`), §8.0 (universal DoR/DoD), §9.3, §10 Phase 1, §11.2, §19 row `FEAT-TENANT-001`                                                                                                                |
| Requirements baseline | `../../../../requirements.md` v3.7 — §9.2 (`REQ-TENANT-001/002/004`), §10 `US-TENANT-001`, `US-TENANT-002`, `US-TENANT-004`, `BR-TENANT-001/002`, §14.1 retention categories, `AC-AUD-002-04`                                                     |
| Architecture baseline | `../../../../architecture.md` v1.4 at tag `arch-v1.4` (`ci/architecture-ratification.json` `RATIFIED`) — §6.3, §6.9, §9.2, §9.4 (`ARC-DATA-018/019/021/026`), §10.2–§10.7, §12.3 (`ARC-TEN-003`, `ARC-TEST-004`), `ADR-010`, `ARC-TEN-001/003/004` |
| Delivery phase        | Phase 1 — first feature of the phase; `FEAT-IAM-002`, `FEAT-ACAD-001`, `FEAT-STUD-001`, `FEAT-QB-001` and `FEAT-TENANT-002` start after it (plan §9.3, §11.2)                                                                                   |
| Dependencies          | `FEAT-PLAT-002` (`tenancy` schema, `app_tenancy` role, RLS convention and catalogue gate, platform-scope path, isolation-matrix generator), `FEAT-PLAT-004` (`OutboxWriter`, `processed_event`, event-contract gate), `FEAT-AUD-001` (`AuditEmitter`, catalogue convention, per-tenant shard-count interface). Inherited through those: `FEAT-PLAT-001` (`Policy` deny-by-default), `FEAT-PLAT-003` (`ActorContext`, `TenantId`, error catalogue, idempotency route rule), `FEAT-PLAT-006` (sweep framework) |
| Consumed by           | `FEAT-TENANT-002` (tenant aggregate, lifecycle field), `FEAT-IAM-002` (`TenantCreated`, designated administrator, provisioning-step contract), `FEAT-ACAD-001`, `FEAT-STUD-001`, `FEAT-QB-001` (tenant existence and readiness, hierarchy references), `FEAT-NOTF-001` (`TenantReady`), `FEAT-EXAM-002` (PIN prefix, subject to `TASK-TEN1-DEFECT-008`), `FEAT-PRIV-001` (retention category), `FEAT-EXAM-007` (`SELECT` on `tenancy.tenant` under `app_txn_examentry`) |
| Generated on          | 2026-10-16                                                                                                                                                                                                                                       |
| Methodology           | Clean architecture as vertical slices (architecture §5.1). `tenancy.domain` holds the `Tenant`, `Campus` and `Department` aggregates and the readiness rule as framework-free code; each use case is one slice (`Endpoint`, `Request`, `Response`, `Policy`, `Handler`, `Queries`, `SliceTest`); persistence, outbox and event consumers live in `tenancy.infra`. The **ownership rule** (`BR-TENANT-001`) is enforced structurally — schema constraint, RLS and a catalogue gate — never by handler discipline alone |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                         |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.6` is Phase 4 task 6                                                                                                                                                                                              |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                       |

**Objective.** Onboard an institution as an isolated tenant with its profile, institution type, campuses and
departments; provision its mandatory defaults and tell it when it is ready; and establish — for this feature's
tables and, by gate, for every later table — that each business record belongs to exactly one tenant unless
explicitly global.

**Why this feature is small in code and large in consequence.** Three tables and a dozen endpoints. But every
later feature takes its `tenant_id` from a row created here, and `RISK-TENANT-001` is rated **Critical**
impact. Three things set here are expensive to change later: whether the tenant registry is subject to RLS
(`TASK-TEN1-BLOCKER-001`), what "ready for operational use" means (`TASK-TEN1-DEFECT-002`), and how a record's
ownership class is decided (`P2.4`). The hierarchy CRUD is the easy part.

### Confirmed implementation decisions

| Decision                         | Choice                                                                                                                                                                                                                                                  | Consequence                                                                                                                                                                                                                     |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tenant registry classification   | `tenancy.tenant` and `tenancy.tenant_provisioning_step` are **platform-registry** tables: enumerated, Security-approved exemptions from the RLS catalogue gate, protected by grants, `@PlatformScope` and layer-1/layer-3 checks. `tenancy.campus` and `tenancy.department` are ordinary tenant-scoped tables with forced RLS | A platform-scope slice installs the enumerated platform marker, not a tenant id, so under RLS it could neither insert a new tenant nor list tenants without the generic bypass `ARC-TEN-003` forbids (`TASK-TEN1-BLOCKER-001`). **Pending ratification at `P0.7`** |
| Onboarding scope of the create call | `POST /api/v1/tenants` creates the tenant aggregate only. Campuses and departments are created by tenant-scoped slices after the tenant is `READY`                                                                                                   | The create call runs in platform scope and cannot write RLS-protected rows. `AC-TENANT-001-02` ("created **or updated**") is met through the hierarchy slices, and `AC-TENANT-001-03`'s no-partial-tenant guarantee becomes one transaction |
| Readiness                        | A `provisioning_status` attribute — `PROVISIONING`, `READY`, `PROVISIONING_FAILED` — separate from the `ACTIVE ⇄ SUSPENDED → CLOSED` lifecycle. It is derived from the step ledger and never set directly                                              | `AC-TENANT-004-03` needs "not ready" and the ratified lifecycle has no such state (`TASK-TEN1-DEFECT-002`). Keeping them separate leaves `FEAT-TENANT-002`'s state machine exactly as ratified                                    |
| Defaults provisioning            | A per-tenant **step ledger** in `tenancy`. Each mandatory step is owned and executed by its own module, triggered by `tenancy.TenantCreated.v1` (or `TenantProvisioningRetried.v1`), and reports back through an outcome event. `READY` when every mandatory step is `COMPLETED`, then `tenancy.TenantReady.v1` is published | Cross-schema writes are prohibited (`ARC-DATA-010`), so tenancy cannot write another module's defaults and must be told they exist. §6.9 has no completion event (`TASK-TEN1-DEFECT-003`)                                  |
| Onboarding idempotency           | A client-generated `onboardingRequestId` in the request body, stored on `tenancy.tenant` under a unique index, together with a SHA-256 request fingerprint. Not the Redis `Idempotency-Key` header                                                     | `ARC-PLAT-010` and `FEAT-PLAT-003 tasks.md` `P2.8` allow Redis only for routes that create **no** durable record. A tenant is a durable record, so a PostgreSQL index must protect it (`TASK-TEN1-DEFECT-006`)                          |
| Duplicate tenant                 | `tenant_code` is platform-unique, case-insensitive, `^[a-z][a-z0-9-]{2,31}$`. Same code under a different `onboardingRequestId` → `409 TENANT_CODE_TAKEN`                                                                                               | Gives `AC-TENANT-001-03` "duplicated" a meaning a database index can enforce. **Pending confirmation at `P0.13`**                                                                                                                |
| Hierarchy shape                  | Fixed depth: `Tenant → Campus → Department`. A department belongs to exactly one campus **in the same tenant**, enforced by a composite foreign key `(tenant_id, campus_id) → campus (tenant_id, id)`                                                   | Same-tenant parentage is a database fact, not a handler check. **Working assumption pending the DoR at `P0.13`**; if Product requires configurable depth, `P2.3` is redesigned before any Phase 3 task starts                    |
| Re-ownership                     | `tenant_id` is immutable. RLS `WITH CHECK` refuses any `UPDATE` that changes it, the domain has no setter, and `Request` DTOs reject a `tenantId` body field as unknown. No cross-tenant migration process exists at MVP                                | `AC-TENANT-002-05` is enforced by three independent mechanisms, each tested on its own (`P6.4`)                                                                                                                                 |
| Soft delete                      | `deleted_at`/`deleted_by` on campus and department; reads exclude deleted rows by default; code uniqueness applies to live rows only; a campus with live departments cannot be deleted (`409`); **no restore at MVP**. `DELETE` is **revoked** from `app_tenancy` on both tables | `ARC-DATA-019` says hard delete is for the retention engine only. Revoking the privilege makes that structural, as `ARC-DATA-016` does for result versions. Needs a grant amendment to `FEAT-PLAT-002 tasks.md` (`P3.6`)                  |
| Global records                   | No mixed tables: a record type is either tenant-scoped (`tenant_id NOT NULL`, forced RLS) or global (no `tenant_id` column, enumerated with a justification). A nullable `tenant_id` meaning "global" is rejected by the gate                          | Mixed tables would need an `OR tenant_id IS NULL` RLS branch, which amounts to a scoped bypass. `AC-TENANT-002-03/04` become a classification fact the gate checks                                                                |
| Authorization                    | Each slice declares a `Policy` against named permissions; policies **deny in every environment** until `FEAT-IAM-003`'s evaluator exists, as with `FEAT-PLAT-001 tasks.md`'s reference slice. Slice tests use a test-scope evaluator                         | The plan schedules `FEAT-TENANT-001` before `FEAT-IAM-001/003`, yet requires platform- and tenant-administrator authority (`TASK-TEN1-DEFECT-005`)                                                                            |
| Integration events               | `TenantCreated.v1`, `TenantProvisioningRetried.v1`, `TenantReady.v1`. **No** campus or department integration events                                                                                                                                    | No MVP consumer needs hierarchy events. `ARC-PLAT-006` already declined to publish an event nobody consumes                                                                                                                    |
| Audit context                    | Platform-scope actions audit with `tenant_id = NULL` and the platform-context marker; tenant-scoped actions audit with the tenant. A placeholder tenant id is never used                                                                               | `AC-AUD-002-04`, `ARC-TEN-003`. Replaces the plan's `REQ-IAM-008` citation (`TASK-TEN1-DEFECT-010`)                                                                                                                             |

### Assumptions

1. **Ratification is discharged.** `ci/architecture-ratification.json` is `RATIFIED` against `arch-v1.4`
   (commit `8ea8250`), so `PLAN-BLOCKER-001` no longer gates implementation. `P0.1` confirms that stage 4a
   is green on the commit this work branches from.
2. **The Phase 0 platform exists as its task lists describe.** References such as `FEAT-PLAT-002 tasks.md` `P4.12`
   point to Phase 0 deliverables. Where a cited task is still open, its `P0.x` confirmation fails and this
   list waits.
3. **The platform-scope marker is a value `current_setting('app.tenant_id', false)::uuid` can parse that
   matches no tenant** (`FEAT-PLAT-002 tasks.md` `P2.14`). If it turns out not to be, `TASK-TEN1-BLOCKER-001` still
   holds and is worse: platform-scope statements against RLS tables would raise instead of returning zero rows.
4. **The designated tenant administrator is onboarding data, not yet a user.** Workforce users are created
   by `FEAT-IAM-002`. This feature captures the administrator's display name and email and passes them on in
   `TenantCreated`. The invitation is `FEAT-IAM-002`'s job and the notification is `FEAT-NOTF-001`'s.
5. **`FEAT-TENANT-002` owns every lifecycle transition and the lifecycle gate (`ARC-TEN-002`).** This feature
   creates tenants `ACTIVE` and exposes no transition.
6. **The tenant table is low-volume.** Hundreds of tenants, not millions, so the list endpoint uses keyset
   pagination for consistency rather than for performance.
7. **Telemetry registration goes through `FEAT-OBS-001`** (`task-obs-1.md` naming convention and
   cardinality guard). Alert rules and panels are `FEAT-OPS-004`'s.

### Blockers and defects carried into this task list

| ID                       | Statement                                                                                                                                                                                                                                                                                                                                                                       | Owning task                |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------|
| `TASK-TEN1-BLOCKER-001`  | **The RLS model cannot express tenant onboarding.** `FEAT-PLAT-002 tasks.md` `P1.8` puts every table with a `tenant_id` column under forced RLS with policy `tenant_id = current_setting('app.tenant_id', false)::uuid`. Platform-scope slices install the platform marker instead of a tenant id (`ARC-TEN-003`). So under RLS, a platform administrator's create-tenant `INSERT` fails `WITH CHECK`, list-tenants returns zero rows, and the provisioning ledger cannot be written. The only escape RLS offers is a policy branch for the marker, and that is the generic bypass `ARC-TEN-003` prohibits. Proposed resolution: enumerated platform-registry exemptions for exactly `tenancy.tenant` and `tenancy.tenant_provisioning_step` | `P0.7`, `P1.3`, `P2.4`, `P3.7` |
| `TASK-TEN1-BLOCKER-002`  | **The platform administrator has no representation in the authorization model.** §6.3 states roles are "membership-scoped, never global", yet `ARC-TEN-003` restricts platform-scope slices to "platform-administrator", and the plan card requires an elevated platform role for tenant creation (`REQ-SEC-002`). No feature owns the construct or its bootstrap — who is the first platform administrator. Implementation can proceed against a declared authority; **release cannot**                                    | `P0.8`, `P1.4`, `P8.5`     |
| `TASK-TEN1-DEFECT-001`   | `FEAT-TENANT-001` owns `REQ-TENANT-004` (scope (d)), but the plan card and the §19 row assign `AC-TENANT-004-01…05` to `FEAT-TENANT-002`. Those criteria are about provisioning and say nothing about the lifecycle. Separately, requirements `US-TENANT-004` has `US-TENANT-003`'s title, actor and description copied over it. Adopted: this feature owns and verifies `AC-TENANT-004-01…05` | `P0.9`, `P1.1`, `P10.6`    |
| `TASK-TEN1-DEFECT-002`   | `AC-TENANT-004-03` requires a tenant that is "not presented as ready for operational use", but the ratified lifecycle (`ARC-TEN-001`) has only `ACTIVE`, `SUSPENDED` and `CLOSED`. Adopted: an orthogonal, derived `provisioning_status`                                                                                                                                                  | `P0.10`, `P2.6`            |
| `TASK-TEN1-DEFECT-003`   | §6.9 lists `TenantCreated` consumers as "iam (defaults), notification". It does not define the mandatory default set (`AC-TENANT-004-01`), and gives tenancy no completion signal, so readiness cannot be known. It also points notification at `TenantCreated`, while `AC-TENANT-004-02` notifies "when the tenant becomes available for use". Adopted: step ledger, step-outcome events, `TenantProvisioningRetried.v1` and `TenantReady.v1` — additive catalogue changes for the Architecture Owner | `P0.10`, `P1.5`, `P2.7`    |
| `TASK-TEN1-DEFECT-004`   | `AC-TENANT-004-02`'s notification cannot be delivered in Phase 1: `FEAT-NOTF-001` (Phase 5) owns delivery and also maps `REQ-TENANT-004`, and the administrator has no workforce identity until `FEAT-IAM-002`. Delivered here: capture of the designated administrator plus publication of `TenantReady`. End-to-end verification is **Phase-5-gated**                         | `P1.5`, `P7.18`, `P8.8`    |
| `TASK-TEN1-DEFECT-005`   | The plan requires platform-administrator authority for creation and tenant-administrator authority for the hierarchy. Workforce authentication (`FEAT-IAM-001`) and authorization (`FEAT-IAM-003`) are neither dependencies of this feature nor scheduled before it (§9.3, §11.2). Adopted: declared permissions plus deny-by-default policies until the evaluator lands. `AC-TENANT-001-04` is re-verified when it does | `P1.4`, `P2.12`, `P7.7`    |
| `TASK-TEN1-DEFECT-006`   | The card's "idempotent on a client-supplied key" conflicts with `ARC-PLAT-010` if the key is read as the Redis `Idempotency-Key` header: tenant creation produces a durable record, so a PostgreSQL unique index must protect it                                                                                                                                            | `P0.11`, `P1.6`, `P2.9`    |
| `TASK-TEN1-DEFECT-007`   | The card says soft-deleted records "remain subject to retention (`CR-PRIV-003`)", but no requirements §14.1 category covers tenant profile, campus or department data. The designated administrator's contact details fall under *Identity/contact*                                                                                                                     | `P0.12`, `P1.8`, `P8.9`    |
| `TASK-TEN1-DEFECT-008`   | `AC-ASMT-008-01` and `ASMT-D-002` fix the PIN format as a "**tenant-configured** 3-character alphabetic prefix", but no feature or schema owns that configuration, and nothing says whether prefixes are unique across tenants. It is a tenant default with no home                                                                                                       | `P0.12`, `P1.5`, `P2.2`    |
| `TASK-TEN1-DEFECT-009`   | Receipt side of `TASK-AUD1-DEFECT-007`: the per-tenant audit shard count `N` and the root-head seed must exist before the tenant's first tenant-scoped audit event. `app_tenancy` holds only `INSERT` on `audit.audit_event`, so tenancy cannot write them itself. Adopted: an `audit`-owned mandatory provisioning step, invoked through `FEAT-AUDIT-001 tasks.md` `P4.31`'s interface | `P0.5`, `P4.14`, `P8.2`    |
| `TASK-TEN1-DEFECT-010`   | **Low.** The card cites `REQ-IAM-008` ("administrative actions on users and roles") as the audit basis for tenant creation. The applicable requirements are `REQ-AUD-001` and `AC-AUD-002-04` (platform context, no placeholder tenant id)                                                                                                                                | `P1.9`, `P10.6`            |
| `TASK-TEN1-OBS-001`      | The card requires tenant-creation and hierarchy-mutation counts, provisioning outcomes and an alert on failed provisioning. §16.2 defines no tenancy metric and §16.4 no provisioning alert                                                                                                                                                                              | `P1.10`, `P9.1`, `P9.2`    |

---

# Phase 0 – Gate Prerequisites

Phase 3 and later work starts only after `P0.1`–`P0.7` and `P0.13` are complete. `P0.8` gates **release**
(`P8.5`), not implementation.

1. [ ] Confirm `ci/architecture-ratification.json` is `RATIFIED` against `arch-v1.4` and that CI stage 4a is green on the commit this feature branches from. Deliverable: phase-log entry naming the commit. Acceptance: stage 4a output attached, not inferred from the artifact.
2. [ ] Confirm the Phase 0 exit criteria (plan §10) and the Phase 1 entry criterion — Keycloak realm and client configuration confirmed. Deliverable: phase-entry record.
3. [ ] Confirm `FEAT-PLAT-002` has delivered the `tenancy` schema, the `app_tenancy` role, the RLS DDL convention, the catalogue gate, the platform-scope path and the isolation-matrix generator. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-002 tasks.md` `P2.14`, `P4.6`, `P4.12`, `P4.15`.
4. [ ] Confirm `FEAT-PLAT-004` has delivered `OutboxWriter`, the `processed_event` primitive and template, and the CI stage 9 event-contract checker. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-004 tasks.md` `P4.1`, `P4.10`, `P4.14`.
5. [ ] Confirm `FEAT-AUD-001` has delivered `AuditEmitter`, the audit-event catalogue convention and the per-tenant shard-count interface, and get from its owner what the emitter does when a tenant has no chain parameters yet. Deliverable: dependency-satisfied record plus a written statement of that emitter behaviour (`TASK-TEN1-DEFECT-009`). Depends on `FEAT-AUDIT-001 tasks.md` `P2.3`, `P4.13`, `P4.31`, `P8.5`.
6. [ ] Confirm the inherited platform contracts: the `Policy` deny-by-default registry (`FEAT-PLAT-001 tasks.md` `P4.10`, `P4.11`), the `ActorContext` / `SystemActor` types, error catalogue and idempotency route rule (`FEAT-PLAT-003 tasks.md` `P2.2`, `P2.8`, `P2.9`), and the sweep framework (`FEAT-PLAT-006 tasks.md` `P2.14`, `P4.7`). Deliverable: dependency-satisfied record.
7. [ ] **Escalate `TASK-TEN1-BLOCKER-001`** to the Security Engineer, the Architecture Owner and the `FEAT-PLAT-002` owner. Deliverable: ratified classification of the four `tenancy` tables plus the agreed amendment to `FEAT-PLAT-002 tasks.md` `P1.8`/`P4.12` adding an enumerated platform-registry exemption list. Acceptance: settled before `P3.1`; the decision is written down, not inferred from the absence of an objection.
8. [ ] **Escalate `TASK-TEN1-BLOCKER-002`** to the `FEAT-IAM-003` owner and the Security Engineer: name the construct that represents a platform administrator, its owner, and the bootstrap of the first one. Deliverable: decision record. Acceptance: settled before `P8.5`; until then every platform-scope `Policy` here denies.
9. [ ] Raise `TASK-TEN1-DEFECT-001` to the plan owner and the Requirements Owner, and get the `FEAT-TENANT-002` owner to acknowledge that `AC-TENANT-004-01…05` move to this feature. Deliverable: adoption record plus the requirements erratum request for `US-TENANT-004`'s header.
10. [ ] Raise `TASK-TEN1-DEFECT-002` and `-003` to the Architecture Owner: the orthogonal readiness attribute, the step ledger, the three additional event types and the step-outcome contract. Deliverable: decision record. Acceptance: settled before `P2.6`, because readiness determines what every dependent feature may assume about a tenant.
11. [ ] Raise `TASK-TEN1-DEFECT-006` to the `FEAT-PLAT-003` owner and get confirmation that a body-carried `onboardingRequestId` under a unique index satisfies the `P2.8` route rule. Deliverable: confirmation record. Acceptance: settled before `P2.9`.
12. [ ] Raise `TASK-TEN1-DEFECT-007` to the DPO (retention category for tenant, campus and department data) and `TASK-TEN1-DEFECT-008` to Product and the `FEAT-EXAM-002` owner (who owns the PIN prefix, and whether prefixes are platform-unique). Deliverable: two decision records or two named open items with an owner and a date.
13. [ ] Obtain the feature's additional Definition of Ready from Product: the institution-type enumeration, the hierarchy depth and parent-child rules, the duplicate-tenant natural key, the mandatory MVP default set (`AC-TENANT-004-01`) and the provisioning deadline. Deliverable: signed DoR record under `ci/dor/FEAT-TENANT-001/`. Acceptance: no Phase 2 design task treats any of these as settled before it is signed.
14. [ ] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not. Note that it cites architecture **v1.3** while the ratified baseline is **v1.4**. Deliverable: signed DoR record.

---

# Phase 1 – Discovery and Analysis

1. [ ] Transcribe `REQ-TENANT-001/002/004`, `BR-TENANT-001/002` and the fourteen acceptance criteria — `AC-TENANT-001-01…04`, `AC-TENANT-002-01…05`, `AC-TENANT-004-01…05` — into an acceptance card, with the owning task for each criterion. Deliverable: acceptance card (`TASK-TEN1-DEFECT-001`). Acceptance: every criterion has an owning task in this list.
2. [ ] Transcribe `ARC-TEN-001/003/004`, the §6.3 `tenancy` rows, the §9.2 `tenancy` schema row and `ARC-DATA-018/019/021/026`. Deliverable: architecture card. Acceptance: records that `ARC-TEN-002` (lifecycle gate) is `FEAT-TENANT-002`'s and that `app_txn_examentry` holds `SELECT` on `tenancy.tenant`, so that table's name and its `id` and lifecycle columns are a consumed contract.
3. [ ] Analyse, statement by statement, the security context under which each operation on each `tenancy` table runs — create, list, get, profile update, provisioning ledger write, hierarchy CRUD — and what RLS does to each under the platform marker. Deliverable: context analysis carrying `TASK-TEN1-BLOCKER-001`. Acceptance: names every statement that would fail or return zero rows under the current convention.
4. [ ] Analyse the authorization dependency: which permission each slice needs, which role would hold it, and when `FEAT-IAM-001` and `FEAT-IAM-003` land relative to this feature. Deliverable: permission inventory carrying `TASK-TEN1-DEFECT-005` and `TASK-TEN1-BLOCKER-002`.
5. [ ] Inventory the candidate mandatory defaults with the module that owns each: audit chain parameters (`FEAT-AUD-001`), PIN prefix (`TASK-TEN1-DEFECT-008`), default role set (`FEAT-IAM-002`/`-003`), designated-administrator invitation (`FEAT-IAM-002`), administrator notification (`FEAT-NOTF-001`). Deliverable: default-set proposal for `P0.13` carrying `TASK-TEN1-DEFECT-003` and `-004`. Acceptance: marks which steps exist in the Phase 1 build and which arrive with later features.
6. [ ] Analyse the onboarding idempotency obligation against `ARC-PLAT-010`, `§10.5` and `FEAT-PLAT-003 tasks.md` `P2.8`. Deliverable: idempotency note carrying `TASK-TEN1-DEFECT-006`.
7. [ ] Classify every table §9.2 names as tenant-scoped, global or platform-registry, and identify which MVP record types actually support global scope. Deliverable: ownership-classification card (`AC-TENANT-002-03/04`). Acceptance: every global candidate has a justification, and any table that would need a nullable `tenant_id` is flagged.
8. [ ] Analyse soft-delete semantics: visibility in reads, uniqueness among live rows, a parent with live children, restore, references held by other modules, and retention under `CR-PRIV-003`. Deliverable: soft-delete card carrying `TASK-TEN1-DEFECT-007`.
9. [ ] List the audit events this feature emits against `FEAT-AUD-001`'s catalogue convention, with platform or tenant context and the retention class for each. Deliverable: audit-event inventory carrying `TASK-TEN1-DEFECT-010`. Acceptance: every state-changing slice has at least one event, and no platform-context event carries a tenant id.
10. [ ] List the telemetry the card requires and check it against §16.2 and §16.4. Deliverable: telemetry card carrying `TASK-TEN1-OBS-001`.
11. [ ] List the contract each consumer feature needs from this one — `FEAT-TENANT-002`, `FEAT-IAM-002`, `FEAT-ACAD-001`, `FEAT-STUD-001`, `FEAT-QB-001`, `FEAT-NOTF-001`, `FEAT-EXAM-002`, `FEAT-EXAM-007`, `FEAT-PRIV-001`. Deliverable: consumer-contract table with the artifact and handover task per row.
12. [ ] Map this feature's verification obligations: isolation-matrix entries (`ARC-TEST-004`, `NFR-SEC-002`), the `ARC-VERIFY-010` audit-coverage rule, the RLS zero-rows scenario (`ARC-VERIFY-005`, per `TASK-PLAT2-DEFECT-001`), and CI stages 4, 8, 9, 10 and 12. Deliverable: verification-ownership table. Acceptance: records that plan §14.4 assigns **no** `ARC-VERIFY` scenario to this feature, so its evidence goes into the shared registers.

---

# Phase 2 – Architecture and Design

1. [ ] Design the module layout: the `Tenant`, `Campus` and `Department` aggregates and the value objects `TenantCode`, `InstitutionType`, `PinPrefix` and `ProvisioningStatus` in `tenancy.domain`; one slice per use case; persistence and consumers in `tenancy.infra`. Deliverable: layout note. Acceptance: `tenancy.domain` imports no framework type and no other module's internals.
2. [ ] Design the `Tenant` aggregate: UUIDv7 identity; profile (legal name, display name, `tenant_code`, institution type, primary contact, designated administrator); PIN prefix if `P0.12` assigns it here; `lifecycle_state` created `ACTIVE`; `provisioning_status` created `PROVISIONING`; `version`. Deliverable: aggregate specification (`AC-TENANT-001-01`). Acceptance: neither status is accepted from a client, and the aggregate exposes no lifecycle transition.
3. [ ] Design the `Campus` and `Department` aggregates and the hierarchy rules confirmed at `P0.13`: codes unique per parent among live rows, same-tenant parentage, and no department without a live campus. Deliverable: hierarchy specification (`AC-TENANT-001-02`). Acceptance: every parent-child rule names the mechanism that enforces it — composite foreign key, partial unique index or domain invariant.
4. [ ] Design the ownership-rule mechanism for the whole programme: a classification registry (`TENANT_SCOPED`, `GLOBAL`, `PLATFORM_REGISTRY`); `TENANT_SCOPED` requires `tenant_id NOT NULL`, forced RLS and `tenant_id` leading the primary index (`ARC-DATA-021`); `GLOBAL` requires no `tenant_id` column and an entry with a justification; `PLATFORM_REGISTRY` is the enumerated exemption from `P0.7`. Deliverable: ownership-rule specification (`BR-TENANT-001`, `AC-TENANT-002-01/03/04`). Acceptance: extends `FEAT-PLAT-002 tasks.md` `P4.12`'s gate rather than adding a parallel one, and rejects mixed tables.
5. [ ] Design re-ownership prevention: RLS `WITH CHECK` on every tenant-scoped table, no domain setter for `tenant_id`, and `tenantId` rejected as an unknown `Request` field. Deliverable: design note (`AC-TENANT-002-05`). Acceptance: states that no authorized cross-tenant migration process exists at MVP, so the "unless" clause of the criterion has no subject.
6. [ ] Design the readiness model: `PROVISIONING → READY`, `PROVISIONING → PROVISIONING_FAILED`, `PROVISIONING_FAILED → PROVISIONING` on retry; the step ledger `(tenant_id, step_key)` with owner module, status, attempt count, last failure code and timestamps; and the code-declared registry of mandatory steps. Deliverable: readiness specification (`TASK-TEN1-DEFECT-002`, `AC-TENANT-004-01/03/04`). Acceptance: `READY` is derived from the ledger, never written by a slice, and a failure code always comes from the error catalogue, never exception text.
7. [ ] Design the event contracts: `tenancy.TenantCreated.v1` (tenant id, code, institution type, designated administrator display name and email, mandatory step keys), `tenancy.TenantProvisioningRetried.v1` (tenant id, attempt, pending step keys), `tenancy.TenantReady.v1` (tenant id, designated-administrator reference), and the step-outcome schema each step owner publishes. Deliverable: four JSON Schemas plus a design note (`TASK-TEN1-DEFECT-003`). Acceptance: no secret in any payload; each personal-data field is named and justified as the minimum the consumer needs (`REQ-PRIV-005`).
8. [ ] Design step execution: step consumers run on the worker role as a declared `SystemActor` with the target tenant's id installed; each is idempotent on `(tenant_id, step_key)` and through `processed_event`; each publishes its outcome in the same transaction as its effect. Deliverable: step-execution contract for owning features (`AC-TENANT-004-05`). Acceptance: a step that runs before the audit chain parameters exist fails and retries rather than writing (`TASK-TEN1-DEFECT-009`).
9. [ ] Design onboarding idempotency: `onboardingRequestId` under a unique index; a SHA-256 fingerprint over the canonical request; same key and fingerprint replays the original response; same key with a different fingerprint returns `422 IDEMPOTENCY_KEY_REUSED`; a concurrent duplicate is resolved by the index. Deliverable: idempotency design (`TASK-TEN1-DEFECT-006`). Acceptance: no Redis dependency, and the protecting index is named per `FEAT-PLAT-003 tasks.md` `P2.8`.
10. [ ] Define the API contract: `POST /api/v1/tenants`; `GET /api/v1/tenants`; `GET`/`PATCH /api/v1/tenants/{tenantId}`; `GET /api/v1/tenants/{tenantId}/provisioning`; `POST /api/v1/tenants/{tenantId}/provisioning/retries`; `POST`/`GET /api/v1/tenants/{tenantId}/campuses`; `GET`/`PATCH`/`DELETE …/campuses/{campusId}`; the same set for `…/campuses/{campusId}/departments`. Deliverable: OpenAPI draft with status codes and problem codes per operation. Acceptance: conforms to §10.2–§10.7 — sub-resource transitions, keyset pagination, explicit filter allowlist, `404` for another tenant's resource.
11. [ ] Design the security context for each slice. Platform-scope slices (create, list, platform-administrator get, profile update, provisioning view, retry) are `@PlatformScope` and enumerated in the `ARC-TEN-003` list. Tenant-scoped slices (hierarchy, member get) install the membership tenant, and a path `tenantId` that differs from it returns `404` before any query runs. Deliverable: context matrix. Acceptance: no slice installs "no tenant filter".
12. [ ] Design the permissions and `Policy` per slice — `tenancy.tenant.create` (elevated, `REQ-SEC-002`), `tenancy.tenant.read`, `tenancy.tenant.update`, `tenancy.provisioning.manage`, `tenancy.hierarchy.manage`, `tenancy.hierarchy.read` — denying in every environment until `FEAT-IAM-003`'s evaluator exists. Deliverable: permission and policy specification (`TASK-TEN1-DEFECT-005`). Acceptance: the test-scope evaluator cannot be wired in a production profile.
13. [ ] Design validation: syntactic rules per field (formats, lengths, enumerations, email syntax, unknown fields rejected, body size cap) and business invariants in the domain; one error-catalogue code per rejection class. Deliverable: validation matrix (`AC-TENANT-001-03`, `REQ-SEC-011`). Acceptance: each rejection in `AC-TENANT-001-03` — missing, malformed, duplicated, hierarchy violation — maps to a named code and status.
14. [ ] Design audit emission per slice from `P1.9`: event type, context, entity reference, and before/after values for updates and soft deletes. Deliverable: audit specification (`REQ-AUD-001`, `AC-AUD-002-04`). Acceptance: each event is emitted inside the business transaction, and an emission failure fails it.
15. [ ] Design soft delete: `DELETE` sets `deleted_at`/`deleted_by`; default `Queries` exclude deleted rows; live-row partial unique indexes; `409 CAMPUS_HAS_ACTIVE_DEPARTMENTS`; no restore; `DELETE` privilege revoked from `app_tenancy`. Deliverable: soft-delete design (`BR-TENANT-002`, `ARC-TEN-004`). Acceptance: states that only the retention role can hard-delete, and that soft deletion does not reset any retention clock (`CR-PRIV-003`).
16. [ ] Design optimistic concurrency: a `version` column on all three aggregates, the expected version required in `PATCH` and `DELETE`, and `409 CONCURRENT_MODIFICATION` on mismatch. Deliverable: concurrency note.
17. [ ] Design the provisioning deadline sweep: a step not `COMPLETED` within the DoR-confirmed deadline is marked `FAILED` with code `STEP_TIMED_OUT`, registered through `FEAT-PLAT-006`'s sweep framework. Deliverable: sweep design (`AC-TENANT-004-04`). Acceptance: the sweep only marks failure and never performs a step's work itself.
18. [ ] Design telemetry: `tenant_created_total{result}`, `tenant_hierarchy_mutation_total{entity,operation,result}`, `tenant_provisioning_step_total{step,outcome}`, `tenant_provisioning_failed` (gauge), `tenant_provisioning_duration_seconds`. Deliverable: metric specification (`TASK-TEN1-OBS-001`). Acceptance: no `tenant_id` label; it passes `task-obs-1.md` `P4.16`'s cardinality guard.
19. [ ] Design the failure matrix: audit emission fails → creation rolls back; broker unavailable → the event waits in the outbox and the tenant stays `PROVISIONING` while the request still succeeds; step fails → `PROVISIONING_FAILED` and retryable; duplicate key or code → mapped `409`/`422`; Redis unavailable → no effect. Deliverable: failure-mode table (`AC-TENANT-001-03`, `AC-TENANT-004-03/04`).
20. [ ] Produce Mermaid sequence diagrams for onboarding, provisioning to `READY` (including a failed step and a retry), and tenant-scoped hierarchy creation. Deliverable: three diagrams embedded in the `P10.3` document.

---

# Phase 3 – Data and Infrastructure

1. [ ] Author the `tenancy.tenant` migration per the `P0.7` classification: profile columns, `lifecycle_state` and `provisioning_status` with `CHECK` constraints, `version`, timestamps, unique `lower(tenant_code)`, and unique `onboarding_request_id` plus a fingerprint column. Deliverable: migration. Depends on `P0.7`, `P2.2`, `P2.9`. Acceptance: expand-only and forbidden-operation clean.
2. [ ] Author the `tenancy.tenant_provisioning_step` migration with primary key `(tenant_id, step_key)` and a foreign key to `tenancy.tenant`. Deliverable: migration. Depends on `P2.6`.
3. [ ] Author the `tenancy.campus` migration: `tenant_id NOT NULL` leading the primary key `(tenant_id, id)`, a foreign key to `tenancy.tenant`, soft-delete columns, `version`, a partial unique index on `(tenant_id, lower(code)) WHERE deleted_at IS NULL`, and `ENABLE` plus `FORCE ROW LEVEL SECURITY` with the standard policy. Deliverable: migration.
4. [ ] Author the `tenancy.department` migration with the composite foreign key `(tenant_id, campus_id) → tenancy.campus (tenant_id, id)`, a partial unique index on `(tenant_id, campus_id, lower(code)) WHERE deleted_at IS NULL`, and forced RLS. Deliverable: migration. Acceptance: a department referencing another tenant's campus is impossible at the database level.
5. [ ] Create `tenancy.processed_event` from `FEAT-PLAT-004 tasks.md`'s `P4.10` template. Deliverable: migration.
6. [ ] Author the grant migration revoking `DELETE` on `tenancy.campus` and `tenancy.department` from `app_tenancy`, and submit the matching amendment to `FEAT-PLAT-002 tasks.md`'s grant matrix. Deliverable: migration plus sibling amendment record. Acceptance: `app_txn_examentry`'s `SELECT` on `tenancy.tenant` is unchanged.
7. [ ] Implement the classification registry and extend `FEAT-PLAT-002 tasks.md` `P4.12`'s catalogue gate per `P2.4`: every table classified; `TENANT_SCOPED` checked for `NOT NULL`, forced RLS and the leading-key position; `GLOBAL` checked for having no `tenant_id`; the `PLATFORM_REGISTRY` list fixed at exactly the two `P0.7` tables. Deliverable: registry plus gate extension, BLOCKING in CI stage 8. Acceptance: an unclassified table, a mixed table or an unapproved exemption fails the build.
8. [ ] Register the four event JSON Schemas from `P2.7` under `contracts/events/`. Deliverable: schemas checked by CI stage 9.
9. [ ] Register this feature's error codes in the error catalogue (`FEAT-PLAT-003 tasks.md` `P2.9`). Deliverable: catalogue entries with fixed `detail` strings.
10. [ ] Register the provisioning-deadline parameter with `FEAT-PLAT-006`'s range validator. Deliverable: configuration entry. Acceptance: an out-of-range value fails startup.
11. [ ] Build the Testcontainers fixture: tenants A and B, each with campuses, departments and soft-deleted rows, plus one `PROVISIONING` and one `PROVISIONING_FAILED` tenant. Deliverable: fixture — the substrate for Phase 6 and Phase 7.
12. [ ] Verify every migration in this feature against CI stage 12: applied to a production-shaped dataset, lock duration measured, no forbidden operation. Deliverable: migration verification result.

---

# Phase 4 – Backend Implementation

1. [ ] Implement the value objects `TenantCode`, `InstitutionType`, `PinPrefix` (if assigned at `P0.12`), `ProvisioningStatus` and the contact types in `tenancy.domain`. Deliverable: value objects. Acceptance: invalid construction is unrepresentable, and the domain-purity rule stays green.
2. [ ] Implement the `Tenant` aggregate: the creation factory (`ACTIVE`, `PROVISIONING`), the profile update, and readiness derived from step outcomes. Deliverable: aggregate. Depends on `P2.2`, `P2.6`.
3. [ ] Implement the `Campus` aggregate with the soft-delete invariant that refuses deletion while live departments exist. Deliverable: aggregate.
4. [ ] Implement the `Department` aggregate bound to exactly one campus of its own tenant. Deliverable: aggregate.
5. [ ] Implement the mandatory-step registry and the readiness rule as pure domain code. Deliverable: registry plus rule. Acceptance: adding a step is a code change in its owning module plus a registry entry, never runtime configuration.
6. [ ] Implement the `CreateTenant` slice: `@PlatformScope`, `Policy`, validation, aggregate creation, ledger rows, the `TenantCreated` outbox row and the `TENANT_CREATED` audit event in **one** transaction. Deliverable: slice (`AC-TENANT-001-01/03`). Acceptance: any failure leaves no tenant, no ledger row, no event and no audit record.
7. [ ] Implement the idempotent replay path from `P2.9`: fingerprint computation, original-response replay, and mapping of key-reuse and unique-violation errors. Deliverable: replay path. Acceptance: two concurrent identical requests create exactly one tenant, and both callers get the same tenant id.
8. [ ] Implement the `ListTenants` (keyset) and `GetTenant` slices, with the member-or-platform-administrator rule from `P2.11` on `GetTenant`. Deliverable: two slices.
9. [ ] Implement the `UpdateTenantProfile` slice with optimistic concurrency and a before/after audit event. Deliverable: slice. Acceptance: `tenant_code`, `lifecycle_state` and `provisioning_status` are not updatable through it.
10. [ ] Implement the `GetTenantProvisioning` slice, returning the ledger per step with status, attempts and failure code. Deliverable: slice (`AC-TENANT-004-04`). Acceptance: shows no exception text and no other module's internal detail.
11. [ ] Implement the `RetryTenantProvisioning` slice: allowed only from `PROVISIONING_FAILED`, it resets failed steps to pending, moves the tenant to `PROVISIONING`, and publishes `TenantProvisioningRetried` with an audit event. Deliverable: slice (`AC-TENANT-004-05`). Acceptance: completed steps are not reset, and a retry from any other state returns `409`.
12. [ ] Implement the tenancy step-outcome consumer: it applies outcomes to the ledger idempotently, derives readiness, and on reaching `READY` publishes `TenantReady` and emits `TENANT_READY`. Deliverable: consumer (`AC-TENANT-004-01/03`). Acceptance: a redelivered or out-of-order outcome never regresses a `COMPLETED` step.
13. [ ] Implement any tenancy-owned tenant-scoped baseline step the `P0.13` default set names, as a step consumer running in the new tenant's context. Deliverable: step consumer, or a recorded "none required" if the set has no tenancy item.
14. [ ] Integrate the audit chain-parameter step through `FEAT-AUDIT-001 tasks.md` `P4.31`'s interface as a mandatory ledgered step. Deliverable: step integration (`TASK-TEN1-DEFECT-009`). Acceptance: if the interface would require `app_tenancy` to write `audit.*` beyond `INSERT` on `audit_event`, stop and escalate rather than widen the grant.
15. [ ] Implement the provisioning deadline sweep from `P2.17` on `FEAT-PLAT-006`'s framework. Deliverable: sweep. Acceptance: runs on the worker role as a declared `SystemActor` and emits an audit event for each step it fails.
16. [ ] Implement the campus slices — create, get, list, update, soft-delete — each tenant-scoped with a `Policy`, `TenantId`-parameterised `Queries`, and an audit event on every mutation. Deliverable: five slices (`AC-TENANT-001-02`, `AC-TENANT-002-01`).
17. [ ] Implement the department slices — create, get, list, update, soft-delete — with the parent campus resolved inside the caller's tenant only. Deliverable: five slices. Acceptance: a parent campus id from another tenant yields `404`, not a constraint-violation message.
18. [ ] Implement the readiness precondition in the hierarchy mutation handlers: a tenant not `READY` gets `409 TENANT_NOT_READY`. Deliverable: precondition (`AC-TENANT-004-03`). Acceptance: reads are unaffected, and the check reads the authoritative row, not a cache.
19. [ ] Implement the path-tenant guard for tenant-scoped routes, returning `404` before any query when the path `tenantId` differs from the installed context. Deliverable: guard, reused by every tenant-scoped slice here.
20. [ ] Implement the permissions and policies from `P2.12`, denying until the evaluator exists, plus the test-scope evaluator excluded from production profiles. Deliverable: policies plus test evaluator.
21. [ ] Register the `P2.18` metrics through `FEAT-OBS-001`'s registry and increment them at each outcome. Deliverable: metric instrumentation.
22. [ ] Generate the OpenAPI document for every new route from the slices and the error catalogue. Deliverable: OpenAPI fragment. Acceptance: matches `P2.10`; any divergence goes back into the design rather than being left in the code.

---

# Phase 5 – Frontend Implementation

**Not applicable.** This is the backend volume. The workforce frontend volume consumes the contract published
at `P8.1`. Platform-administrator onboarding screens and tenant-administrator hierarchy screens are built there
against that contract, not here.

---

# Phase 6 – Security and Hardening

1. [ ] Attempt tenant creation unauthenticated, with a candidate exam token, as an ordinary workforce user and as a tenant administrator; confirm each is refused with the correct non-disclosing status. Deliverable: four-case denial matrix (`AC-TENANT-001-04`, `REQ-SEC-002`).
2. [ ] As a tenant-B administrator, attempt to read, list, update and soft-delete tenant-A campuses and departments, and to create a tenant-B department under a tenant-A campus; confirm `404` in every case. Deliverable: cross-tenant attempt matrix (`AC-TENANT-002-02`) plus isolation-matrix entries.
3. [ ] Remove the tenant predicate from a hierarchy query in a test build and confirm RLS returns zero rows; run the same statement with no context installed and confirm it raises. Deliverable: RLS backstop evidence (`ARC-VERIFY-005` contribution).
4. [ ] Attempt re-ownership three ways — a `tenantId` field in a `PATCH` body, a direct `UPDATE … SET tenant_id` as `app_tenancy`, and re-parenting a department under another tenant's campus — and confirm each mechanism refuses on its own. Deliverable: three independent refusals (`AC-TENANT-002-05`).
5. [ ] Attempt to insert a campus with a null and with a mismatched `tenant_id` as `app_tenancy`, and confirm both are refused. Deliverable: two refusals (`AC-TENANT-002-04`).
6. [ ] Attempt a hard `DELETE` on campus and department as `app_tenancy`, and confirm the privilege refusal. Deliverable: refusal evidence (`BR-TENANT-002`).
7. [ ] Confirm the platform-registry exemption covers exactly the two approved tables: add a test table with a `tenant_id` column and no RLS, and confirm the gate fails the build. Deliverable: negative gate evidence (`TASK-TEN1-BLOCKER-001`).
8. [ ] Confirm every platform-scope slice here is annotated, appears in the enumerated `ARC-TEN-003` list, is restricted by `Policy`, and audits with the platform marker and `tenant_id = NULL`. Deliverable: platform-scope review.
9. [ ] Run adversarial input against every new endpoint: oversized bodies, unknown fields, malformed UUIDs, homoglyph and mixed-case tenant codes, injection strings, out-of-range enumerations. Deliverable: input-validation report (`REQ-SEC-011`). Acceptance: every case returns `400` with a catalogue code; none returns `500`.
10. [ ] Confirm the event payloads carry no secret, that their personal-data fields are exactly those justified at `P2.7`, and that CI stage 10's leak scan covers the new schemas and audit payloads. Deliverable: payload review plus scan result.
11. [ ] Confirm the error responses disclose nothing: `TENANT_CODE_TAKEN` is reachable only by platform administrators, and `IDEMPOTENCY_KEY_REUSED` reveals nothing about the original request's content. Deliverable: non-disclosure review (`REQ-SEC-010`).
12. [ ] Review the feature against the §13.6 threat rows — elevation through a platform-scope slice (row **E**), tampering and repudiation — and record how each is mitigated. Deliverable: threat-model conformance record.
13. [ ] Confirm no credential or secret exists in source or configuration for this feature. Deliverable: secret-scan result.

---

# Phase 7 – Testing and Quality Assurance

1. [ ] Unit-test the value objects and the three aggregates, including every invariant from `P2.2`, `P2.3` and `P2.15`. Deliverable: domain test suite with no framework or database.
2. [ ] Unit-test the readiness rule: all steps completed → `READY`; any failed → `PROVISIONING_FAILED`; retry → `PROVISIONING`; out-of-order and duplicate outcomes. Deliverable: readiness test suite.
3. [ ] Write a slice test for every slice in Phase 4 using the test-scope evaluator and the in-memory `OutboxWriter` double. Deliverable: slice test suite.
4. [ ] Test `AC-TENANT-001-01`: a valid onboarding creates a unique tenant id with `ACTIVE`, `PROVISIONING` and the full profile. Deliverable: integration test.
5. [ ] Test `AC-TENANT-001-02`: hierarchy created and updated within the confirmed parent-child rules, with each rule violation refused. Deliverable: integration tests.
6. [ ] Test `AC-TENANT-001-03`: the full rejection matrix, plus forced failures after the tenant insert and at audit emission, each leaving no tenant, ledger row, outbox row or audit record. Deliverable: rejection and atomicity tests.
7. [ ] Test `AC-TENANT-001-04` with the test evaluator now, and re-run it against `FEAT-IAM-003`'s real evaluator when that lands. Deliverable: denial test plus a re-verification record (`TASK-TEN1-DEFECT-005`).
8. [ ] Test `AC-TENANT-002-01`: every tenant-scoped row persisted here carries exactly one tenant id, and the `P3.7` gate is green across all schemas. Deliverable: integration test plus gate run.
9. [ ] Test `AC-TENANT-002-03/04` against the classification registry: a global-classified test table accepts a row without tenant ownership; a tenant-scoped table refuses one. Deliverable: two tests.
10. [ ] Retain `P6.2`, `P6.4` and `P6.5` as permanent integration tests for `AC-TENANT-002-02/04/05`. Deliverable: tests in CI stage 8.
11. [ ] Test soft-delete semantics: deleted rows are excluded from reads and lists, a code can be reused after deletion, deleting a campus with live departments returns `409`, and the audit record carries before/after. Deliverable: soft-delete tests (`BR-TENANT-002`).
12. [ ] Test idempotency: replay with the same key, the same key with a changed payload, and two concurrent identical requests — each yielding exactly one tenant, one `TenantCreated` and one audit event. Deliverable: three tests (`TASK-TEN1-DEFECT-006`).
13. [ ] Test the duplicate-code race: two concurrent creates with different keys and the same code produce one tenant and one `409`. Deliverable: concurrency test.
14. [ ] Test `AC-TENANT-004-01`: every mandatory step registered in the build completes, the tenant becomes `READY`, and `TenantReady` is published exactly once. Deliverable: end-to-end provisioning test over the real broker in Testcontainers.
15. [ ] Test `AC-TENANT-004-03`: a forced step failure leaves the tenant `PROVISIONING_FAILED`, refuses hierarchy mutation with `TENANT_NOT_READY`, and publishes no `TenantReady`. Deliverable: integration test.
16. [ ] Test `AC-TENANT-004-04`: the failure is visible through the provisioning endpoint with step, attempt count and catalogue code, and no duplicate defaults exist. Deliverable: integration test.
17. [ ] Test `AC-TENANT-004-05`: retry after a partial failure, plus broker redelivery of `TenantCreated` and of each step outcome, creates no duplicate ledger row, configuration or baseline record. Deliverable: retry and redelivery tests.
18. [ ] Test the Phase 1 limb of `AC-TENANT-004-02`: `TenantReady` carries the designated-administrator reference and is consumable against its registered schema. Deliverable: contract test. Acceptance: records that delivery of the notification is **Phase-5-gated** on `FEAT-NOTF-001` (`TASK-TEN1-DEFECT-004`).
19. [ ] Test the deadline sweep: an overdue step is marked `STEP_TIMED_OUT`, the tenant becomes `PROVISIONING_FAILED`, and a completed step is never touched. Deliverable: sweep test using the controlled clock.
20. [ ] Test a broker outage during onboarding: the create request succeeds, the tenant stays `PROVISIONING`, and provisioning completes after the outbox drains. Deliverable: resilience test.
21. [ ] Run CI stage 9 over the four event schemas and the OpenAPI document, and prove the gate with one deliberate breaking change that fails. Deliverable: compatibility result plus negative evidence.
22. [ ] Confirm the `ARC-VERIFY-010` audit-coverage rule is green for every mutating handler here, and that platform-context events carry `tenant_id = NULL` while tenant-scoped events carry the tenant. Deliverable: coverage result plus context assertions (`AC-AUD-002-04`).
23. [ ] Confirm the isolation matrix has entries for every new tenant-scoped endpoint and passes, and that removing one entry fails the build. Deliverable: CI stage 10 result (`ARC-TEST-004`, `NFR-SEC-002`).
24. [ ] Confirm every new error path maps to a catalogue code and that no response carries stack traces, SQL or exception text. Deliverable: error-mapper test (`REQ-SEC-010`).
25. [ ] Assert each metric from `P2.18` increments on its outcome, with no `tenant_id` label. Deliverable: telemetry test.
26. [ ] Confirm the list endpoints use their indexes under a fixture of 1,000 tenants and 10,000 campuses, using `EXPLAIN`. Deliverable: query-plan evidence.
27. [ ] Run the full pipeline on a clean checkout and confirm stages 4, 8, 9, 10 and 12 are blocking and green for this feature's contributions. Deliverable: pipeline run record.
28. [ ] Verify each of the fourteen acceptance criteria and each feature-card acceptance outcome against a named task and its evidence. Deliverable: completed acceptance verification table.
29. [ ] Register the retained artifacts in the §19.9 evidence register: the isolation-matrix entries, the re-ownership refusals, the provisioning end-to-end report and the idempotency race evidence. Deliverable: register entries.

---

# Phase 8 – Deployment and Release

1. [ ] Publish the tenancy API contract and its OpenAPI components for the workforce frontend volume. Deliverable: contract publication record. Acceptance: passes the CI stage 9 diff as a new, additive surface.
2. [ ] Hand the `TenantCreated` contract and the step-execution contract to `FEAT-IAM-002`, and confirm receipt of the audit chain-parameter step to the `FEAT-AUD-001` owner. Deliverable: two handover records, the second closing `TASK-AUD1-DEFECT-007` and `TASK-TEN1-DEFECT-009`.
3. [ ] Hand the `Tenant` aggregate, the lifecycle field and the readiness semantics to `FEAT-TENANT-002`, together with the `AC-TENANT-004` ownership record. Deliverable: handover record. Acceptance: the lifecycle gate is told that a tenant not `READY` is also not operational.
4. [ ] Hand the hierarchy reference semantics — campus and department ids held by reference with no foreign key, and soft-deleted ids still resolvable for history — to `FEAT-ACAD-001`, `FEAT-STUD-001`, `FEAT-QB-001` and `FEAT-IAM-002`. Deliverable: handover record.
5. [ ] Confirm `TASK-TEN1-BLOCKER-002` is closed before tenant creation is enabled in any non-test environment; until it is, the platform-scope policies stay denying. Deliverable: release gate record.
6. [ ] Confirm the step consumers and the deadline sweep run on the worker role and the sweep as a scheduler singleton (`FEAT-PLAT-006`), with none on the request role. Deliverable: role placement review.
7. [ ] State the rollback path: migrations are expand-only, so a code rollback to *N−1* runs safely against *N*'s schema; a tenant created by *N* stays valid under *N−1*; unknown event types are ignored by older consumers under the §11.3 rules. Deliverable: rollback statement.
8. [ ] Record the deferrals with their owners: lifecycle transitions and gate (`FEAT-TENANT-002`); administrator invitation (`FEAT-IAM-002`); notification delivery (`FEAT-NOTF-001`, Phase 5); retention execution for tenancy data (`FEAT-PRIV-001`); real authorization evaluation (`FEAT-IAM-003`). Deliverable: deferral register.
9. [ ] Hand the retention category decided at `P0.12` to `FEAT-PRIV-001` for registration as a `platform.retention_policy` key. Deliverable: handover record (`TASK-TEN1-DEFECT-007`), or the open item carried with its owner.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register the `P2.18` metrics in `FEAT-OBS-001`'s registry and confirm they appear on a staging scrape. Deliverable: registration plus scrape evidence (`TASK-TEN1-OBS-001`).
2. [ ] Hand the alert definitions to `FEAT-OPS-004`: `tenant_provisioning_failed > 0` sustained beyond the provisioning deadline, and step-timeout occurrences, each with proposed severity and first action. Deliverable: alert handover record. Acceptance: the plan's "failed provisioning is alertable" is met by a defined rule, not by the existence of a metric.
3. [ ] Request a tenancy panel on dashboard 6 (Platform health): creation rate, provisioning duration, and failed or overdue tenants. Deliverable: panel request to `FEAT-OPS-004` (§16.5).
4. [ ] Confirm the structured logs for tenancy carry identifiers only — never the designated administrator's email or name. Deliverable: log review against `FEAT-OBS-001`'s field set.
5. [ ] Write the provisioning-failure runbook: read the ledger, identify the owning module from the step key, fix the cause, then retry through the endpoint. Deliverable: `docs/runbooks/tenant-provisioning-failed.md`. Acceptance: it says plainly that defaults are **never** inserted by hand, because a manual row bypasses the step's idempotency key and its audit event.
6. [ ] Write the tenant-onboarding operating procedure for platform administrators: generating and keeping the `onboardingRequestId`, safe retry after a timeout, and reading readiness. Deliverable: `docs/runbooks/tenant-onboarding.md`.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the ownership rule: the three classes, why there are no mixed tables, how a later feature classifies a new table, and what the gate rejects. Deliverable: `docs/tenancy-ownership-rule.md` — the normative input for every later table.
2. [ ] Publish the hierarchy model: shape, parent-child rules, soft-delete semantics, and how other modules reference campuses and departments. Deliverable: `docs/tenancy-hierarchy.md`.
3. [ ] Publish the provisioning contract for step-owning features: registering a step, the trigger events, the outcome event, idempotency obligations, and the three sequence diagrams from `P2.20`. Deliverable: `docs/tenancy-provisioning.md`.
4. [ ] Document the platform-registry exemption and its reasoning, so a reader understands why `tenancy.tenant` has no RLS and why no further exemptions are allowed. Deliverable: `docs/tenancy-registry-isolation.md` (`TASK-TEN1-BLOCKER-001`).
5. [ ] Document the platform-scope slices this feature adds, as entries in the enumerated `ARC-TEN-003` register. Deliverable: register update.
6. [ ] Raise `TASK-TEN1-BLOCKER-001`, `-002`, `TASK-TEN1-DEFECT-001` through `-010` and `TASK-TEN1-OBS-001` to their owners as baseline defects, each with the resolution this feature adopted; raise the grant amendment and the gate amendment to the `FEAT-PLAT-002` owner. Deliverable: thirteen defect records plus two amendment records.
7. [ ] Update the plan §19 traceability matrix: add `AC-TENANT-004-01…05` to this feature's row, and record the task ranges, Phase-5-gated limbs and retained artifacts. Deliverable: updated matrix rows.
8. [ ] Run a walkthrough with the Phase 1 feature teams on the ownership rule, readiness, the step contract and the hierarchy reference semantics. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                              | Architecture reference          | Tasks                                                    | Verification                                                     |
|---------------------------------------------------------------------|---------------------------------|----------------------------------------------------------|------------------------------------------------------------------|
| `REQ-TENANT-001` onboarding with profile, type, campuses, departments | §6.3, §9.2, `ARC-TEN-001`        | `P2.2`, `P2.3`, `P3.1`–`P3.4`, `P4.2`–`P4.4`, `P4.6`, `P4.16`, `P4.17` | `P7.4`, `P7.5`                                                   |
| `AC-TENANT-001-01` unique id, status, attributes                    | §6.3                            | `P2.2`, `P4.6`                                           | `P7.4`                                                           |
| `AC-TENANT-001-02` hierarchy within parent-child rules              | §6.3                            | `P2.3`, `P3.4`, `P4.16`, `P4.17`                         | `P7.5`                                                           |
| `AC-TENANT-001-03` rejection, no partial tenant                     | §10.3, §10.4                    | `P2.13`, `P2.19`, `P4.6`, `P4.7`                         | `P7.6`, `P7.12`, `P7.13`                                         |
| `AC-TENANT-001-04` denial without authority                         | §12.3 layer 3                   | `P2.12`, `P4.20`                                         | `P6.1`, `P7.7` (re-verified on `FEAT-IAM-003`)                   |
| `REQ-TENANT-002`, `BR-TENANT-001` one tenant unless global           | §9.4, §12.3, `ARC-TEN-003`       | `P2.4`, `P3.7`                                           | `P7.8`, `P7.9`                                                   |
| `AC-TENANT-002-01` exactly one tenant id                            | `ARC-DATA-018/021`              | `P2.4`, `P3.3`, `P3.4`, `P3.7`                           | `P7.8`                                                           |
| `AC-TENANT-002-02` cross-tenant denied without exposure             | §10.3, §12.3                    | `P2.11`, `P4.19`                                         | `P6.2`, `P6.3`, `P7.23`                                          |
| `AC-TENANT-002-03/04` global vs tenant-scoped persistence           | `ARC-TEN-003`                   | `P1.7`, `P2.4`, `P3.7`                                   | `P6.5`, `P7.9`                                                   |
| `AC-TENANT-002-05` no re-ownership                                  | `ARC-DATA-018`                  | `P2.5`, `P3.4`                                           | `P6.4`, `P7.10`                                                  |
| `REQ-TENANT-004` defaults and administrator notification            | §6.9                            | `P2.6`–`P2.8`, `P4.5`, `P4.12`–`P4.15`                   | `P7.14`–`P7.20`                                                  |
| `AC-TENANT-004-01` mandatory defaults created                       | §6.9                            | `P1.5`, `P2.6`, `P4.12`–`P4.14`                          | `P7.14`                                                          |
| `AC-TENANT-004-02` administrator notified on readiness              | §6.9                            | `P2.7`, `P4.12`                                          | `P7.18` — **Phase-5-gated** (`TASK-TEN1-DEFECT-004`)             |
| `AC-TENANT-004-03` not ready on failed step                         | —                               | `P2.6`, `P4.18`                                          | `P7.15`                                                          |
| `AC-TENANT-004-04` failure recorded for support or retry            | —                               | `P2.6`, `P2.17`, `P4.10`, `P4.15`                        | `P7.16`, `P7.19`                                                 |
| `AC-TENANT-004-05` retry creates no duplicates                      | §10.5, §11.4                    | `P2.8`, `P4.11`, `P4.12`                                 | `P7.17`                                                          |
| `BR-TENANT-002`, `ARC-TEN-004` soft delete                           | §9.4 `ARC-DATA-019`             | `P2.15`, `P3.3`, `P3.4`, `P3.6`                          | `P6.6`, `P7.11`                                                  |
| `REQ-SEC-002` elevated role for creation                            | §12.3                           | `P2.12`, `P4.20`                                         | `P6.1`; release gated on `TASK-TEN1-BLOCKER-002`                 |
| `REQ-SEC-003`, `NFR-SEC-002` isolation and matrix                    | §12.3, `ADR-010`, `ARC-TEST-004` | `P2.11`, `P4.19`                                         | `P6.2`, `P6.3`, `P7.23`                                          |
| `REQ-SEC-010/011` validation and non-disclosing errors               | §10.3, §10.4                    | `P2.13`, `P3.9`                                          | `P6.9`, `P6.11`, `P7.24`                                         |
| `REQ-AUD-001`, `AC-AUD-002-04` in-transaction audit, platform context | §9.5, `ARC-TEN-003`             | `P1.9`, `P2.14`                                          | `P7.22`                                                          |
| Plan reliability — idempotent onboarding                            | §10.5, `ARC-PLAT-010`           | `P2.9`, `P3.1`, `P4.7`                                   | `P7.12`, `P7.13`                                                 |
| Plan observability — counts, outcomes, alertable failure            | §16.2 (gap)                     | `P2.18`, `P4.21`                                         | `P7.25`, `P9.1`, `P9.2`                                          |
| `TASK-TEN1-BLOCKER-001` registry vs RLS                              | `ARC-TEN-003`, `ARC-DATA-018`    | `P0.7`, `P1.3`, `P2.4`, `P3.7`                           | `P6.7`                                                           |
| `TASK-AUD1-DEFECT-007` / `TASK-TEN1-DEFECT-009` shard count `N`      | §9.5 `ARC-AUD-005`              | `P0.5`, `P4.14`                                          | `P7.14`, `P8.2`                                                  |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence
from oversight.

| Excluded                                                                                 | Owner                                    |
|------------------------------------------------------------------------------------------|------------------------------------------|
| Tenant lifecycle transitions, the lifecycle gate filter and the `TENANT-D-001` carve-out | `FEAT-TENANT-002`                        |
| Workforce users, memberships, roles and the designated administrator's invitation        | `FEAT-IAM-002`                           |
| Authorization evaluation and the platform-administrator construct                         | `FEAT-IAM-003` (`TASK-TEN1-BLOCKER-002`) |
| Notification dispatch, templates and delivery of the onboarding notification             | `FEAT-NOTF-001` (Phase 5)                |
| Schemas, module roles, the RLS convention and the base catalogue gate                     | `FEAT-PLAT-002`                          |
| Outbox, relay, `processed_event` primitive and the event-contract gate                    | `FEAT-PLAT-004`                          |
| `AuditEmitter`, the chain, and the shard-count parameter itself                           | `FEAT-AUD-001`                           |
| Retention execution and the policy-version store                                          | `FEAT-PRIV-001`                          |
| PIN generation using the tenant prefix                                                    | `FEAT-EXAM-002`                          |
| Subscriptions, plans and billing for tenants                                              | Post-MVP (`DEP-003`)                     |
| Cross-tenant record migration                                                             | Not provided at MVP (`AC-TENANT-002-05`) |
| Restoring a soft-deleted campus or department                                             | Not provided at MVP (`P2.15`)            |
| Frontend onboarding and hierarchy screens                                                 | Workforce frontend volume                |
| Dashboards, alert rules and routing                                                       | `FEAT-OPS-004`                           |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.2)

1. [ ] Isolation-matrix entries exist and pass for every new tenant-scoped endpoint (`P7.23`).
2. [ ] Tenant creation is idempotent on a client-supplied key protected by a PostgreSQL unique index (`P4.7`, `P7.12`, `P7.13`).
3. [ ] The one-tenant-per-record constraint and soft-delete semantics are proven by integration tests (`P7.8`–`P7.11`).
4. [ ] Failed provisioning is measurable and alertable (`P9.1`, `P9.2`).
5. [ ] Institution-type enumeration and hierarchy depth were confirmed with Product before design (`P0.13`).

### Universal (plan §8.0), as far as this feature can discharge it

6. [ ] All fourteen mapped acceptance criteria verified, with `AC-TENANT-004-02` verified to its Phase 1 limb (`P7.28`).
7. [ ] Unit, slice, integration and contract tests pass (`P7.1`–`P7.21`).
8. [ ] CI stage 4 is green for the code added, including R1–R8, the `TenantId` signature rule and domain purity (`P7.27`).
9. [ ] Tenant isolation is enforced and covered by the isolation matrix (`P6.2`, `P6.3`, `P7.23`).
10. [ ] Every state change emits an audit event inside the business transaction, covered by `ARC-VERIFY-010` (`P7.22`).
11. [ ] Error responses carry a correlation identifier and leak no internal detail (`P7.24`).
12. [ ] The API contract and event schemas are updated and pass the CI stage 9 diff (`P7.21`, `P8.1`).
13. [ ] Database changes are expand/contract-compliant and pass CI stage 12 (`P3.12`).
14. [ ] Required telemetry exists (`P9.1`); the rollback path is stated (`P8.7`).
15. [ ] Peer or AI review complete; no unresolved Critical or High defect remains — **`TASK-TEN1-BLOCKER-001` must be closed** (`P0.7`).
16. [ ] The plan §19 traceability matrix is updated, including the `AC-TENANT-004` reassignment (`P10.7`).
17. [ ] **Not dischargeable by this feature, and recorded as such:** delivery of the administrator notification (`FEAT-NOTF-001`, Phase 5, `TASK-TEN1-DEFECT-004`); authorization against the real evaluator (`FEAT-IAM-003`, re-verified at `P7.7`); release-time closure of `TASK-TEN1-BLOCKER-002` (`P8.5`); retention execution for tenancy data (`FEAT-PRIV-001`, `TASK-TEN1-DEFECT-007`).
