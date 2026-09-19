# Exam-Critical Table Registry

`migration/exam-critical-tables.yaml` is the canonical input for migration
analysis and lock-threshold decisions. It starts with `delivery.answer`,
`delivery.answer_operation`, `delivery.attempt`, and `audit.audit_event`.

When an owning feature adds a relation to the exam acceptance, attempt,
submission, or audit write path, that feature must add its schema-qualified
lower-case name to the registry in the same change. The change must include
the migration analyser and lock-verdict tests that prove the relation receives
the exam-critical policy. Removing a relation requires Platform Ops and
Engineering Lead review because it weakens a release gate.
