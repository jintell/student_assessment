# Schema Ownership

Status: normative module-to-schema ownership map for `FEAT-PLAT-002`.

Source: architecture section 9.2 and `ADR-003`. The twelve bounded-context
modules own one schema each. `audit`, `outbox`, and `platform` are platform
schemas, giving fifteen schemas in total.

The principal tables below are ownership targets from the architecture. A name
in this map does not assert that its owning feature has already created the
table.

| Schema | Owning module | Owned concepts | Principal tables | Cross-module identifiers held |
|---|---|---|---|---|
| `tenancy` | `tenancy` | Tenant, campus, department | `tenant`, `campus`, `department` | None |
| `iam` | `iam` | Workforce user, identity-provider link, membership, roles, permissions, reconciliation | `workforce_user`, `idp_identity_link`, `tenant_membership`, `role`, `permission`, `role_permission`, `reconciliation_item` | `tenant_id` |
| `academic` | `academic` | Programme, course, subject | `programme`, `course`, `subject`, `course_subject` | `tenant_id` |
| `people` | `people` | Candidate, assignment, bulk upload | `candidate`, `candidate_assignment`, `bulk_upload_job`, `bulk_upload_row` | `tenant_id`, `exam_session_id`, `assessment_id` |
| `questionbank` | `questionbank` | Question, version, choice, answer key, media reference | `question`, `question_version`, `choice`, `answer_key`, `media_asset` | `tenant_id` |
| `authoring` | `authoring` | Assessment, section, item, session, marking configuration, PIN policy | `assessment`, `section`, `section_item`, `assessment_config`, `exam_session`, `session_candidate` | `tenant_id`, `question_version_id`, `course_id`, `candidate_id` |
| `examaccess` | `examaccess` | PIN, retrievable material, validation guard, candidate principal, recovery, identity verification | `exam_access_pin`, `pin_retrievable_material`, `pin_validation_guard`, `candidate_principal`, `recovery_request`, `candidate_identity_verification` | `tenant_id`, `exam_session_id`, `candidate_id`, `attempt_id` |
| `delivery` | `delivery` | Attempt, answer, operation log, presentation, question evidence | `attempt`, `answer`, `answer_operation`, `attempt_presentation`, `attempt_question_evidence` | `tenant_id`, `assessment_id`, `exam_session_id`, `candidate_id`, `question_version_id` |
| `grading` | `grading` | Grading record, outcome, failure log | `grading_record`, `grade_outcome`, `grade_question_score`, `grade_section_score`, `grading_failure` | `tenant_id`, `attempt_id`, `assessment_id` |
| `result` | `result` | Result, version, section breakdown, provisional feedback, OTP access | `result`, `result_version`, `result_section_breakdown`, `provisional_feedback`, `result_access_grant`, `result_access_guard` | `tenant_id`, `attempt_id`, `candidate_id` |
| `correction` | `correction` | Correction request, approval, applied link | `correction_request`, `correction_field`, `correction_approval` | `tenant_id`, `result_id`, `result_version_id` |
| `notification` | `notification` | Dispatch record, delivery outcome, template, bounce task | `notification_dispatch`, `notification_delivery_event`, `notification_template`, `contact_deliverability`, `bounce_task` | `tenant_id`, `candidate_id` |
| `audit` | `audit` platform capability | Immutable audit events | `audit_event`, `audit_chain_head` | References from all modules by identifier |
| `outbox` | `outbox` platform capability | Transactional outbox | `outbox_event` | References from all modules by identifier |
| `platform` | `platform` | Retention policy, legal hold, DSR register, breach register, feature configuration, advisory-lock registry | `retention_policy`, `legal_hold`, `dsr_request`, `breach_record`, `platform_config` | `tenant_id` |

## Ownership Rules

- Each module role owns data access only within its schema.
- A migration may create or alter objects only in its owning schema.
- Cross-schema foreign keys are prohibited.
- Cross-module references store opaque identifiers and are resolved through an
  exposed module API or an application event.
- `app_migrator` owns schema DDL; application pool roles have no DDL
  privilege.
- Ownership changes require an architecture decision before any grant or
  migration change.

The twelve-module versus fifteen-schema wording discrepancy is resolved as
twelve module schemas plus the three platform schemas above
(`TASK-PLAT2-DEFECT-003`).
