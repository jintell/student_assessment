# Module Map

Status: published module ownership and dependency map for `FEAT-PLAT-001`
(`P10.3`). Sources: architecture sections 6.2-6.8 and 9.2, plus the checked-in
Spring Modulith descriptors.

The application has exactly twelve business context modules. Each is a closed
Spring Modulith module, exports only its named `api`, and is the sole owner of
the schema and aggregate families listed below. `FEAT-PLAT-001` declares these
ownership boundaries; it does not create the schemas or aggregates. Database
implementation begins with the owning features and `FEAT-PLAT-002`.

## Context Modules

| Module / bounded context | Schema | Owned aggregate families | Permitted dependencies |
|---|---|---|---|
| `tenancy` / tenant lifecycle and institutional hierarchy | `tenancy` | `Tenant`, `Campus`, `Department` | `shared::api`, `audit::api`, `outbox::api` |
| `iam` / workforce identity and authorization | `iam` | `WorkforceUser`, `TenantMembership`, `ReconciliationItem` | `tenancy::api`, `shared::api`, `audit::api`, `outbox::api` |
| `academic` / academic catalogue | `academic` | `Programme`, `Course`, `Subject`, course-subject association | `tenancy::api`, `shared::api`, `audit::api` |
| `people` / candidate registry and eligibility | `people` | `Candidate`, `CandidateAssignment`, `BulkUploadJob` | `tenancy::api`, `shared::api`, `audit::api`, `outbox::api` |
| `questionbank` / questions, versions, keys, and media | `questionbank` | `Question`, append-only `QuestionVersion`, choices, answer key, media references | `tenancy::api`, `shared::api`, `audit::api` |
| `authoring` / assessment composition, publication, and scheduling | `authoring` | `Assessment`, `Section`, `SectionItem`, `ExamSession`, candidate assignments | `tenancy::api`, `iam::api`, `academic::api`, `people::api`, `questionbank::api`, `shared::api`, `audit::api`, `outbox::api` |
| `examaccess` / exam access and candidate principal | `examaccess` | `ExamAccessPin`, `PinValidationGuard`, `CandidatePrincipal`, `RecoveryRequest`, `CandidateIdentityVerification` | `tenancy::api`, `iam::api`, `people::api`, `authoring::api`, `delivery::api`, `shared::api`, `audit::api`, `outbox::api` |
| `delivery` / attempt lifecycle and accepted answers | `delivery` | `Attempt`, `Answer`, `AnswerOperation`, `AttemptPresentation`, `AttemptQuestionEvidence` | `tenancy::api`, `authoring::api`, `shared::api`, `audit::api`, `outbox::api` |
| `grading` / grading lifecycle and deterministic scoring | `grading` | `GradingRecord`, `GradeOutcome`, question/section scores, `GradingFailure` | `tenancy::api`, `authoring::api`, `delivery::api`, `shared::api`, `audit::api`, `outbox::api` |
| `result` / result versioning, publication, and access | `result` | `Result`, append-only `ResultVersion`, `SectionBreakdown`, `ProvisionalFeedback`, `ResultAccessGrant`, `ResultAccessGuard` | `tenancy::api`, `iam::api`, `people::api`, `grading::api`, `shared::api`, `audit::api`, `outbox::api` |
| `correction` / result correction approval and application | `correction` | `CorrectionRequest`, `CorrectionApproval`, applied-version link | `tenancy::api`, `iam::api`, `result::api`, `shared::api`, `audit::api`, `outbox::api` |
| `notification` / notification dispatch and reconciliation | `notification` | `NotificationDispatch`, `NotificationDeliveryEvent`, `NotificationTemplate`, `ContactDeliverability`, `BounceTask` | `tenancy::api`, `people::api`, `examaccess::api`, `result::api`, `correction::api`, `shared::api`, `audit::api`, `outbox::api` |

`allowedDependencies` grants Java API visibility only. It does not grant schema
access. Cross-module references are identifiers without foreign keys, queries
stay within the owning schema, and state propagation follows R7. The only
synchronous cross-module write flow currently enumerated by `ADR-023` is
`examaccess.verifyPinAndStartAttempt` through `delivery::api`.

## Platform Modules

Four additional closed modules provide cross-cutting platform capabilities and
are deliberately excluded from the twelve-context count:

| Module | Schema | Exported responsibility | Permitted dependencies | Count rationale |
|---|---|---|---|---|
| `shared` | None | Technical contracts such as request context, policy, clock/decimal conventions, and transaction markers | None | Technical kernel with no business aggregate or schema |
| `platform` | `platform` | Platform governance/operations contracts and the conformance reference slice | `shared::api`, `audit::api`, `outbox::api` | Cross-cutting platform capability |
| `audit` | `audit` | `AuditEmitter` and immutable audit input contracts | `shared::api` | Cross-cutting evidence capability |
| `outbox` | `outbox` | `OutboxWriter` and integration event contracts | `shared::api` | Cross-cutting delivery mechanism |

The count resolution is recorded in
[`decisions/0001-context-module-count.md`](decisions/0001-context-module-count.md).
All sixteen modules remain subject to R2: imports may cross a module boundary
only through an explicitly allowed named `api` interface.

## Change Control

Changing an aggregate owner, adding a context dependency, exposing another
named interface, or adding a synchronous multi-schema flow changes an
architectural boundary. Such a change requires architecture review and, where
applicable, an ADR update before changing a module descriptor. Adding a new
integration event is the prerequisite for granting `outbox::api` to a context
that does not currently have it.
