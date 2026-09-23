# TASK-PLAT5-DEFECT-001 Undefined Migration Lock Thresholds

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-005`

## Baseline defect

CI stage 12 is blocking on a lock held beyond a threshold, but the approved
requirements, architecture, and delivery plan do not define that threshold. A
blocking verification cannot be deterministic while its bound is absent.

## Resolution adopted by this feature

Platform Ops and the Engineering Lead approved:

- 100 ms warning for a lock touching an exam-critical relation;
- 250 ms failure for a lock touching an exam-critical relation; and
- 2,000 ms failure for a lock touching a non-critical relation.

The signed source is `ci/dor/P0.3-lock-duration-thresholds.json`; rationale and
change governance are in `docs/migration-lock-thresholds.md`. Stage 12 verifies
the signatures and exact configuration before measuring locks.

## Next-baseline action

Add the three values, inclusive failure semantics, relation classification,
and approval/change authority to the architecture. Replace the undefined
"beyond threshold" wording without weakening the implemented gate.
