# TASK-PLAT3-DEFECT-001 Verification Identifier Mismatch

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Correction recipient: `FEAT-PLAT-001` owner

Raised by: `FEAT-PLAT-003`

## Baseline Defect

Plan section 14.4 assigns the clock, decimal, and domain-purity conformance
rules to `ARC-VERIFY-004`, `ARC-VERIFY-005`, and `ARC-VERIFY-006`. Architecture
section 19.8 defines those identifiers as the tenant-isolation matrix, omitted-
predicate RLS behavior, and composite-role/outbox enforcement respectively.
The incorrect allocation also appears in the `FEAT-PLAT-001` task list at
`P4.23`.

## Resolution Adopted by This Feature

Kernel purity, controlled-time use, and exact scoring types extend the R6
domain-purity assertion under `ARC-VERIFY-003` in blocking CI stage 4.
`FEAT-PLAT-003` added those limbs to the existing conformance harness and did
not redefine `ARC-VERIFY-004` through `-006`.

## Next-Baseline Action

Correct plan section 14.4 and every derived traceability row to cite
`ARC-VERIFY-003` for the R6 clock, decimal, domain, and kernel-purity limbs.
Correct `FEAT-PLAT-001` `P4.23` in its next task-list baseline while retaining
the completed implementation evidence. Preserve `ARC-VERIFY-004` through
`-006` for their section 19.8 meanings.
