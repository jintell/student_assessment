# FEAT-PLAT-002 Phase 1 Discovery Record

Date: 2026-09-10
Architecture baseline: `arch-v1.4`
Source: architecture section 9.2 unless stated otherwise

This is the working artifact for persistence tasks P1.1-P1.9. Publication of
its normative subsets is deferred to the Phase 10 tasks that own those files.

## P1.1 Schema Ownership

`ARC-DATA-010` assigns one PostgreSQL schema and one database role to each
module. Cross-schema foreign keys are prohibited; cross-module references are
held as identifiers and their integrity is maintained by the owning module and
event flow.

| Schema | Owning module | Owned concepts | Principal tables | Cross-module references held by identifier |
|---|---|---|---|---|
| `tenancy` | `tenancy` | Tenant, campus, department | `tenant`, `campus`, `department` | None |
| `iam` | `iam` | Workforce user, IdP link, membership, role, permission, reconciliation | `workforce_user`, `idp_identity_link`, `tenant_membership`, `role`, `permission`, `role_permission`, `reconciliation_item` | `tenant_id` |
| `academic` | `academic` | Programme, course, subject | `programme`, `course`, `subject`, `course_subject` | `tenant_id` |
| `people` | `people` | Student/candidate, assignment, bulk upload | `candidate`, `candidate_assignment`, `bulk_upload_job`, `bulk_upload_row` | `tenant_id`, `exam_session_id`, `assessment_id` |
| `questionbank` | `questionbank` | Question, version, choice, answer key, media reference | `question`, `question_version`, `choice`, `answer_key`, `media_asset` | `tenant_id` |
| `authoring` | `authoring` | Assessment, section, item, session, marking configuration, PIN policy | `assessment`, `section`, `section_item`, `assessment_config`, `exam_session`, `session_candidate` | `tenant_id`, `question_version_id`, `course_id`, `candidate_id` |
| `examaccess` | `examaccess` | PIN, retrievable material, validation guard, candidate principal, recovery, identity verification | `exam_access_pin`, `pin_retrievable_material`, `pin_validation_guard`, `candidate_principal`, `recovery_request`, `candidate_identity_verification` | `tenant_id`, `exam_session_id`, `candidate_id`, `attempt_id` |
| `delivery` | `delivery` | Attempt, answer, operation log, presentation, question evidence | `attempt`, `answer`, `answer_operation`, `attempt_presentation`, `attempt_question_evidence` | `tenant_id`, `assessment_id`, `exam_session_id`, `candidate_id`, `question_version_id` |
| `grading` | `grading` | Grading record, outcome, failure log | `grading_record`, `grade_outcome`, `grade_question_score`, `grade_section_score`, `grading_failure` | `tenant_id`, `attempt_id`, `assessment_id` |
| `result` | `result` | Result, version, section breakdown, provisional feedback, OTP access | `result`, `result_version`, `result_section_breakdown`, `provisional_feedback`, `result_access_grant`, `result_access_guard` | `tenant_id`, `attempt_id`, `candidate_id` |
| `correction` | `correction` | Correction request, approval, applied link | `correction_request`, `correction_field`, `correction_approval` | `tenant_id`, `result_id`, `result_version_id` |
| `notification` | `notification` | Dispatch record, delivery outcome, template, bounce task | `notification_dispatch`, `notification_delivery_event`, `notification_template`, `contact_deliverability`, `bounce_task` | `tenant_id`, `candidate_id` |
| `audit` | `audit` | Immutable audit events | `audit_event`, `audit_chain_head` | All modules |
| `outbox` | `outbox` | Transactional outbox | `outbox_event` | All modules |
| `platform` | `platform` | Retention policy, legal hold, DSR register, breach register, feature configuration, advisory-lock registry | `retention_policy`, `legal_hold`, `dsr_request`, `breach_record`, `platform_config` | `tenant_id` |

`TASK-PLAT2-DEFECT-003` is resolved here as twelve module schemas plus three
platform schemas (`audit`, `outbox`, and `platform`), for fifteen total. The
architecture's statement that there are twelve schemas is interpreted as
twelve bounded-context module schemas, not the total schema count.

## P1.2 Grant Matrix

The twelve module roles are `app_tenancy`, `app_iam`, `app_academic`,
`app_people`, `app_questionbank`, `app_authoring`, `app_examaccess`,
`app_delivery`, `app_grading`, `app_result`, `app_correction`, and
`app_notification`.

| Principal | Exact grants |
|---|---|
| Each `app_<module>` role above | `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on its own schema only; `INSERT` on `outbox.outbox_event`; `INSERT` on `audit.audit_event` |
| `app_txn_examentry` | The explicit minimum privileges in P1.3; `INSERT` on `outbox.outbox_event`; `INSERT` on `audit.audit_event`; no `UPDATE` or `DELETE` on `audit.*` |
| `app_api` | No direct object grants of any kind; role membership only, limited to the module and composite roles this workload may assume |
| `app_worker` | No direct object grants of any kind; role membership only, limited to the module and composite roles this workload may assume |
| `app_pindist` | No direct object grants of any kind; role membership only, limited to the module and composite roles this workload may assume |
| `app_migrator` | Sole DDL-capable role, used only by the migration entrypoint |
| `app_readonly_ops` | `SELECT` on non-PII-bearing diagnostic views on the read replica |

Under `ARC-DATA-026`, a pool login role has no direct schema, table, sequence,
function, or other object grant. Before a transaction assumes an allowed role,
its statements therefore fail for want of privilege. The durable outbox table
name is `outbox.outbox_event`, resolving `TASK-PLAT2-DEFECT-002` in favour of
the section 9.2 ownership and grant matrices.

## P1.3 Exam-Entry Composite Role

`ARC-DATA-027` and `ADR-023` define one closed MVP atomic collaboration:
`examaccess ▸ verifyPinAndStartAttempt`, using `app_txn_examentry`.

| Schema | Explicit minimum privileges |
|---|---|
| `examaccess` | `SELECT`, `INSERT`, `UPDATE` on `exam_access_pin`, `pin_validation_guard`, `candidate_principal`, `candidate_identity_verification` |
| `delivery` | `INSERT` on `attempt`, `attempt_question_evidence`, `attempt_presentation`; `SELECT` on `attempt` |
| `people` | `SELECT` on `candidate`, `candidate_assignment` |
| `authoring` | `SELECT` on `exam_session`, `session_candidate`, `assessment`, `assessment_config`, `section`, `section_item` |
| `tenancy` | `SELECT` on `tenant` |
| `audit` | `INSERT` on `audit_event` |
| `outbox` | `INSERT` on `outbox_event` |

The role is deliberately not granted:

- `UPDATE` or `DELETE` on `delivery.answer` or `delivery.answer_operation`;
- any write on `people`, `authoring`, or `tenancy`;
- any access to `grading`, `result`, `correction`, `notification`,
  `questionbank`, `iam`, or `platform`; or
- `UPDATE` or `DELETE` on any `audit` object.

`app_txn_examentry` is not a member of any module role and is never generated
as a union. Its explicit grant set is strictly narrower than the union of the
`app_examaccess`, `app_delivery`, `app_people`, and `app_authoring` roles that
it replaces for this transaction.

## P1.4 Three-Layer Tenant Isolation

| Layer and owner | Mechanism | Failure mode defeated | Implementation task |
|---|---|---|---|
| 1 - Structural, `FEAT-PLAT-001` | Rule R5 requires every `Queries` method for a tenant-scoped table to take the distinct `TenantId` type; ArchUnit checks method signatures | A developer forgets the `WHERE tenant_id = ?` predicate | Baseline `P4.22` implements the R5 conformance rule; baseline `P4.5` supplies its `TenantScopedQuery` subject |
| 2 - Database, `FEAT-PLAT-002` | Every tenant-scoped table has PostgreSQL RLS enabled and forced; transaction-local `app.tenant_id` drives the policy | A query that escapes layer 1 can expose another tenant; absent context could otherwise permit an unscoped query | Persistence `P3.13` creates the forced-RLS probe, `P4.3` installs context, and `P4.12` enforces catalogue coverage |
| 3 - Authorization, `FEAT-IAM-003` | The resolved membership tenant must equal the resource tenant; cross-tenant access returns `404`, never `403` | An actor requests a correctly tenant-scoped resource that the actor is not allowed to see | `FEAT-IAM-003` owns the future authorization resolver and object-level check; its implementation task is not part of this feature's task list |

The layers are independent controls. Layer 1 prevents a missing predicate,
layer 2 backstops defective query code and rejects missing transaction context,
and layer 3 makes an object-level authorization decision without disclosing
whether a foreign-tenant resource exists.

## P1.5 Persistence-Relevant Conformance Rules

The statement column below is transcribed verbatim from architecture section
5.1. `FEAT-PLAT-001` owns rules R3, R5, and R7; this feature supplies their
database-grant and RLS backstops. `FEAT-PLAT-002` owns rules R9 and R10.

| Rule | Verbatim statement | Enforcement mechanism | Owning implementation task |
|---|---|---|---|
| R3 | `Queries` SHALL NOT reference a table outside its own module's schema. | ArchUnit SQL-string scan plus per-module database role grants | Baseline `P4.20`; persistence backstop `P3.8` and grant-diff gate `P4.16` |
| R5 | Every `Queries` method on a tenant-scoped table SHALL take `TenantId` as a parameter. | ArchUnit signature rule plus forced RLS | Baseline `P4.22`; persistence backstop `P3.13` and catalogue gate `P4.12` |
| R7 | **Asynchronous** cross-module state propagation SHALL go through the `infra` outbox, never a direct write. The **only** exception is a synchronous atomic collaboration listed in the `ADR-023` enumerated-flow table, which writes inside the initiating handler's single transaction. | ArchUnit plus schema grants | Baseline `P4.24`; persistence role and grant implementation `P3.8`-`P3.10` |
| R9 | Every transaction SHALL install its security context as its **first** statements — `SET LOCAL ROLE <role>` then `SET LOCAL app.tenant_id` (or the enumerated platform-scope marker). No statement may execute before them. A connection returned to the pool without a completed transaction SHALL be reset. | Connection-factory decorator plus `ARC-VERIFY-024` | Persistence `P4.3`, `P4.5`, and `P4.9` |
| R10 | A `Handler` SHALL assume either its own module role or a **composite role listed in the `ADR-023` table**. There is no other role a handler may assume, and there is no role switch after the transaction's first statement. | ArchUnit plus `pg_roles` grant audit and `ARC-VERIFY-023` | Persistence `P4.7`, `P4.10`, and `P4.11` |

## P1.6 Workload Pool-to-Role Mapping

| Workload | Runtime profile | Pool allocation per replica | Pool login role | Notes |
|---|---|---|---|---|
| `cbt-api` | `api` | 14 total; 8 are reserved for exam-path routes in a separate pool | `app_api` | The separate reservation prevents administrative traffic from starving answer acceptance (`ARC-PERF-003`) |
| `cbt-worker` | `worker` | 10 | `app_worker` | Four connections fund grading concurrency; six remain for the outbox relay, schedulers, and notification dispatch |
| `cbt-pindist` | `pindist` | 5 | `app_pindist` | Fixed at two replicas; serves only coordinator PIN distribution and has its own workload identity |

These are pool login roles, so the P1.2 no-direct-object-grants rule applies.
The mapping is an input to `FEAT-PLAT-006`; it does not configure the pools in
this discovery phase.

## P1.7 Verification Ownership

CI stage 8 is the real-PostgreSQL integration gate for this feature. Staging
reruns are retained where an architecture launch condition requires them.

| Scenario | FEAT-PLAT-002 responsibility | Owner | Gate |
|---|---|---|---|
| `ARC-VERIFY-002` | Own both the static foreign-schema SQL scan and live database grant limb | `FEAT-PLAT-002` | CI stage 4 for static analysis; CI stage 8 integration for live grants |
| `ARC-VERIFY-005` | Own proof that forced RLS returns zero rows when a tenant predicate is omitted | `FEAT-PLAT-002` | CI stage 8 integration |
| `ARC-VERIFY-024` | Own adversarial physical-connection reuse across tenants, roles, and every Reactor termination path | `FEAT-PLAT-002` | CI stage 8 integration; rerun in staging and retain for launch condition `L9` |
| `ARC-VERIFY-004` | Generate and gate the isolation matrix for routes that exist; `FEAT-SEC-001` later extends it to every endpoint | `FEAT-SEC-001` and `FEAT-PLAT-002` | CI stage 10 |
| `ARC-VERIFY-006` | Contribute the live granted-composite-role limb and the closed enumeration match | `FEAT-PLAT-001` owns the static R7 limb; `FEAT-PLAT-002` owns the database contribution | CI stage 4 static analysis plus CI stage 8 integration |
| `ARC-VERIFY-023` | Contribute only the explicit grant list and its narrowness assertion | `FEAT-EXAM-007` owns the exam-entry atomicity proof | CI stage 4 plus CI stage 8 Testcontainers integration |

No new `ARC-VERIFY` identifier is introduced. `TASK-PLAT2-DEFECT-001` records
that the plan's FEAT-PLAT-002 card mis-cites the omitted-predicate RLS outcome
as `ARC-VERIFY-018`. In architecture section 19.8, that outcome is
`ARC-VERIFY-005`; `ARC-VERIFY-018` instead covers out-of-range configuration
failing at startup. This feature implements and reports the architecture's
existing `ARC-VERIFY-005` identifier.

## P1.8 Tenant-Scope Classification

Table scope and operation scope are separate classifications.

| Classification | Mechanical rule | Consequence |
|---|---|---|
| Tenant-scoped table | A table in a module schema has a `tenant_id` column | The catalogue gate requires enabled and forced RLS plus a policy that reads the transaction-local `app.tenant_id` setting |
| Non-tenant-scoped platform table | A table legitimately has no `tenant_id` column under its owning design | It is not selected by the tenant-RLS catalogue query; absence of the column alone does not create a tenant-filter bypass or make any caller platform-scoped |
| Platform-scope operation | The slice appears in the closed platform-scope enumeration and declares `@PlatformScope` | Access is limited to a platform administrator or an enumerated system actor and is audited with `tenant_id = NULL` plus an explicit platform-context marker |

The RLS gate discovers tenant-scoped tables from `pg_attribute` by the presence
of `tenant_id`; it does not depend on a hand-maintained table list or on the
schema name. Consequently, a later migration cannot silently add a tenant
table outside the gate.

`ARC-TEN-003` currently enumerates these platform-scope slice categories:

1. Platform administration.
2. Retention sweeps.
3. Reconciliation.

The enumeration is explicit and deny-by-default. A new platform-scope slice
must be added to the architecture-controlled enumeration and annotated; scope
is never inferred from its package, schema, missing `tenant_id`, or caller.
There is no generic "bypass tenant filter" facility.

## P1.9 Definition of Ready

The universal and feature-specific readiness assessment is recorded in
`P1.9-definition-of-ready.md`. Its status is **READY**, with no unmet item or
applicable blocker. That record relies on the repository's verified detached
signatures; it does not claim a new signature or alter an approved source.
