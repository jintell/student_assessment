# TASK-PLAT6-DEFECT-001 Missing Runtime Persistence Dependency

Status: CLOSED - PLAN CORRECTED AND ACKNOWLEDGED

Date prepared: 2026-10-10

Date closed: 2026-10-10

Owner and escalation recipient: Plan Owner

Raised by: `FEAT-PLAT-006`, task `P0.5`

## Defect

The `FEAT-PLAT-006` feature card in plan section 8.1 explicitly depends on
`FEAT-PLAT-001` and `FEAT-PLAT-002`. Section 9.3 grouped the feature with
foundation work requiring only `FEAT-PLAT-001`, and section 11.2 described
runtime roles as an independent Phase 0 track after that baseline. Following
those scheduling statements could start runtime migrations and pool wiring
before the persistence prerequisites exist.

Source plan:
`/Users/josleke/workspace/project/backend/student_assessment/workspace/v3/be/plan/plan.md`.

## Correction Prepared for Acknowledgement

Section 9.3 now gives `FEAT-PLAT-006` its own hard-dependency row naming
`FEAT-PLAT-001` and `FEAT-PLAT-002`. Section 11.2 now states that the four
tracks may overlap subject to their dependencies and explicitly requires
`FEAT-PLAT-002` before the runtime-roles track.

The consumed persistence artifacts are:

- `app_api`, `app_worker` and `app_pindist` login roles;
- the `platform` schema owned by `app_migrator`;
- the existing per-replica pool budgets: API 14 with a separate 8-connection
  exam-path reservation, worker 10, PIN distribution 5.

Runtime `P0.3` verified those deliverables. Its evidence is retained in
`docs/evidence/FEAT-PLAT-006/phase-0-gate-prerequisites.md`. Persistence
`P1.6`, `P3.7`, `P3.11` and `P3.14` are the current task references;
runtime `P0.3`'s reference to persistence `P4.14` predates the numbering
change and now points to the RLS template.

The correction aligns the plan with its existing feature card. It changes
no runtime code, migration, grant or connection budget and does not discharge
runtime `P0.6`'s separate architecture/persistence-owner decisions.

## Plan Owner Acknowledgement

Decision: CORRECTION ACCEPTED.

- Identity: Josleke
- Role: Plan Owner
- Acknowledged at: 2026-10-10T21:30:47+01:00
- Accepted sequence: `FEAT-PLAT-002` completes before `FEAT-PLAT-006`
- Corrected sections: plan sections 9.3 and 11.2
- Evidence: `ci/dor/FEAT-PLAT-006/P0.5-plan-dependency-acknowledgement.json`
- Detached signature:
  `P0.5-plan-dependency-acknowledgement.plan-owner.sig`
- Signing fingerprint: `5E99BB3AAAFD87094E378E61EADF5C3816562490`

The acknowledgement binds the corrected plan's SHA-256 digest and accepts the
dependency row and track description as the authoritative Phase 0 sequence.
It does not replace the separate architecture and persistence-owner decisions
required by runtime `P0.6`.

## Completion Condition

Satisfied. The plan edits are present, the Plan Owner accepted them, and the
detached signature verifies against the retained public key. No Phase 3 task
may begin before the recorded persistence dependency is complete.
