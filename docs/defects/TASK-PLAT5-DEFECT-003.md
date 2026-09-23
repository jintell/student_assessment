# TASK-PLAT5-DEFECT-003 Unqualified Exam-Critical Relations

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-005`

## Baseline defect

Architecture section 9.8 and `ADR-019` name `answer`, `attempt`, and
`audit_event` without schema qualification, while section 9.2 assigns those
relations to module schemas. The unqualified list is ambiguous and cannot be
used safely by an automated analyser.

## Resolution adopted by this feature

The gate uses `delivery.answer`, `delivery.attempt`, and `audit.audit_event`.
It also includes `delivery.answer_operation` because that relation is on the
same critical write path. The list is a declarative registry that each owning
feature extends when it introduces another exam-critical relation.

## Next-baseline action

Schema-qualify the original names and define the list as an extensible
exam-critical relation registry, with the owning feature responsible for each
addition. Align `ADR-019`, sections 9.2 and 9.8, and the risk-register wording.
