# TASK-PLAT5-DEFECT-002 Missing Migration Verification Identifier

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-005`

## Baseline defect

Architecture section 19.9 registers a Migration lock-duration report produced
by CI stage 12 and consumed by Change advisory, but no `ARC-VERIFY-###`
identifier owns migration verification. This leaves a retained, blocking gate
without the identifier used by the rest of the verification register.

## Resolution adopted by this feature

No identifier was invented locally. CI stage 12 remains blocking and owns
static DDL analysis, lock measurement, and N-1 compatibility. The lock report,
rollback rehearsal, and backfill rehearsal are registered in
`docs/evidence/verification-evidence-register.md` with this defect noted.

## Next-baseline action

Allocate a ratified verification identifier to the stage 12 migration gate and
the section 19.9 report, then update the verification register and traceability
matrix. Preserve the existing evidence identifiers during migration so prior
release records remain addressable.
