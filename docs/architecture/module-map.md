# Context Module Map

Status: agreed discovery baseline for `FEAT-PLAT-001` (`P1.1`). Sources: architecture §§6.2-6.8 and §9.2.

The context-module count is exactly twelve. Each context module owns the schema with the same name and is the sole owner of the aggregates listed below.

| Module | Bounded context | Classification | Schema | Owned aggregates and aggregate children |
|---|---|---|---|---|
| `tenancy` | Tenant lifecycle and institutional hierarchy | Supporting | `tenancy` | `Tenant`; `Campus`; `Department` |
| `iam` | Workforce identity, membership, authorization and reconciliation | Supporting | `iam` | `WorkforceUser` with IdP link; `TenantMembership` with roles and permissions; `ReconciliationItem` |
| `academic` | Academic catalogue | Supporting | `academic` | `Programme`; `Course`; `Subject`; course-subject association |
| `people` | Authoritative candidate registry and eligibility | Supporting | `people` | `Candidate`; `CandidateAssignment`; `BulkUploadJob` with per-row results |
| `questionbank` | Questions, immutable versions, keys and media | Supporting | `questionbank` | `Question` with append-only `QuestionVersion`, choices, answer key and media references |
| `authoring` | Assessment composition, publication and scheduling | Supporting | `authoring` | `Assessment` with `Section`, `SectionItem` and configuration; `ExamSession` with candidate assignments |
| `examaccess` | PIN access, validation guard and candidate principal | Core | `examaccess` | `ExamAccessPin`; `PinValidationGuard`; `CandidatePrincipal`; `RecoveryRequest`; `CandidateIdentityVerification` |
| `delivery` | Attempt lifecycle, accepted answers and presentation evidence | Core | `delivery` | `Attempt`; `Answer`; `AnswerOperation`; `AttemptPresentation`; `AttemptQuestionEvidence` |
| `grading` | Grading lifecycle and deterministic scoring outcome | Core | `grading` | `GradingRecord`; `GradeOutcome` with question and section scores; `GradingFailure` |
| `result` | Result versioning, publication, feedback and OTP access | Core | `result` | `Result` with append-only `ResultVersion`; `SectionBreakdown`; `ProvisionalFeedback`; `ResultAccessGrant`; `ResultAccessGuard` and recovery |
| `correction` | Result correction approval and application | Core | `correction` | `CorrectionRequest` with corrected fields; `CorrectionApproval`; applied-version link |
| `notification` | Notification dispatch and delivery reconciliation | Generic | `notification` | `NotificationDispatch`; `NotificationDeliveryEvent`; `NotificationTemplate`; `ContactDeliverability`; `BounceTask` |

Cross-module references are identifiers without foreign keys. This table does not make `audit`, `outbox`, or `platform` context modules; their separate platform-module status is decided by `P1.3`.

## Permitted Context Dependencies

Status: agreed input to the Spring Modulith descriptors (`P1.2`). A dependency means importing only the named module's exported `api` package. It never permits importing internals or reading the other module's schema.

| Consumer module | Synchronous `api` reads or commands permitted | Event-schema `api` imports permitted | Basis |
|---|---|---|---|
| `tenancy` | None | None | Lifecycle source for the other contexts |
| `iam` | `tenancy` | `tenancy` | Conformist tenant lifecycle; tenant-created defaults |
| `academic` | `tenancy` | `tenancy` | Conformist tenant lifecycle |
| `people` | `tenancy` | `tenancy` | Conformist tenant lifecycle |
| `questionbank` | `tenancy` | `tenancy` | Conformist tenant lifecycle |
| `authoring` | `tenancy`, `iam`, `academic`, `people`, `questionbank` | `tenancy` | Authorization context, catalogue references, candidate eligibility, and frozen question versions |
| `examaccess` | `tenancy`, `iam`, `people`, `authoring`, `delivery` | `tenancy`, `people`, `authoring` | Authoritative lifecycle/identity/session reads; the exam-entry flow commands `delivery` under `ADR-023` |
| `delivery` | `tenancy`, `authoring` | `tenancy`, `authoring` | Frozen snapshot/session facts; no reverse dependency on `examaccess` is permitted |
| `grading` | `tenancy`, `authoring`, `delivery` | `tenancy`, `authoring`, `delivery` | Frozen scoring configuration and `AttemptSubmitted` |
| `result` | `tenancy`, `iam`, `people`, `grading` | `tenancy`, `grading` | Current authorization/candidate identity and `AttemptGraded` |
| `correction` | `tenancy`, `iam`, `result` | `tenancy`, `result` | Current approver authority and `applyCorrection`/result version contract |
| `notification` | None | `tenancy`, `people`, `examaccess`, `result`, `correction` | Dispatch is driven by versioned events; notification does not query producer schemas |

`authoring` reads `PeopleApi` when assigning only eligible candidates (§6.3), although the §6.2 diagram does not draw that relationship explicitly. Conversely, the diagram's unlabeled `examaccess` event edge into `delivery` is not a versioned event in §6.9 and does not authorize a reverse Java dependency: `examaccess` already depends on `DeliveryApi` for atomic entry, so `delivery -> examaccess` would form a forbidden Modulith cycle. Architecture §6.4's authoritative principal-read obligation therefore requires an Architecture Owner resolution before the state-changing delivery slices are implemented; it may not be solved by a reverse import or cross-schema SQL.

All context modules may additionally depend on the platform-module APIs defined by `P2.3`: `shared::api`, `audit::api`, and `outbox::api` as applicable. Those dependencies do not create context-to-context authority.
