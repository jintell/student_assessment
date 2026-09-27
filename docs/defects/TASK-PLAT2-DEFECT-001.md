# TASK-PLAT2-DEFECT-001 RLS Verification Identifier Mismatch

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Baseline Defect

The delivery plan's `FEAT-PLAT-002` card attributes the omitted-tenant-
predicate RLS proof to `ARC-VERIFY-018`. The architecture verification
catalogue assigns that behavior to `ARC-VERIFY-005`; `ARC-VERIFY-018` covers
out-of-range configuration failing at startup. The conflicting identifiers
make traceability and gate ownership ambiguous.

## Resolution Adopted by This Feature

`FEAT-PLAT-002` implements and reports the RLS proof as `ARC-VERIFY-005` in CI
stage 8. The test deliberately omits the tenant predicate and proves forced RLS
returns zero rows for the foreign tenant. No new verification identifier was
created.

## Next-Baseline Action

Correct the feature card, testing expectations, Definition of Done, and all
traceability rows so omitted-predicate RLS consistently names
`ARC-VERIFY-005`. Preserve `ARC-VERIFY-018` exclusively for configuration-range
startup failure.
