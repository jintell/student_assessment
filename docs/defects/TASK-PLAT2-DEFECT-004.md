# TASK-PLAT2-DEFECT-004 Pooled-Connection Verification Gate Mismatch

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Baseline Defect

The plan's feature Definition of Done places `ARC-VERIFY-024` in staging,
architecture section 19.8 places it at Integration, and launch condition `L9`
requires retained Phase 6 evidence. A staging-only interpretation would remove
the adversarial pool-reuse regression gate from every commit; a CI-only
interpretation would not discharge `L9`.

## Resolution Adopted by This Feature

`ARC-VERIFY-024` runs as a blocking PostgreSQL integration suite in CI stage 8
on every commit. It is rerun in staging, and the staging report is retained for
`L9`. The two runs use the same scenarios across tenants, roles, completion,
error, cancellation, read timeout, statement timeout, and pool saturation.

## Next-Baseline Action

State both obligations together in the verification catalogue and feature
Definition of Done: blocking CI stage 8 regression coverage plus a retained
staging rerun for `L9`. Do not substitute one gate for the other.
