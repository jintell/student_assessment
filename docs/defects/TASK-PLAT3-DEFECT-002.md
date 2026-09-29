# TASK-PLAT3-DEFECT-002 Shared-Kernel Verification Gap

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-003`

## Baseline Defect

Plan section 14.4 assigns shared-kernel and context-propagation verification to
`ARC-VERIFY-008`. Architecture section 19.8 defines `ARC-VERIFY-008` as the
requirement that every route resolve to an authorization `Policy`, with startup
failure otherwise. It does not verify the shared kernel or propagation. The
verification register contains no dedicated identifier for that combined
obligation.

## Resolution Adopted by This Feature

No replacement identifier was invented. Kernel purity and related R6 rules are
reported as contributions to `ARC-VERIFY-003`; actor attribution is reported
under `ARC-VERIFY-011`; error responses are reported under
`ARC-VERIFY-013`. Correlation propagation retains named executable tests and
evidence without claiming an unrelated architecture identifier.

## Next-Baseline Action

Remove the `ARC-VERIFY-008` shared-kernel attribution from plan section 14.4
and related traceability. Decide whether propagation needs a new, uniquely
defined architecture verification scenario or should remain named test
evidence under `NFR-OBS-002`. Do not change the authorization meaning of
`ARC-VERIFY-008`.
