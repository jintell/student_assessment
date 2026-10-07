# TASK-AUD1-DEFECT-002 Audit Verification Ownership Is Inconsistent

Status: **OPEN FOR NEXT BASELINE - LOCAL OWNERSHIP RESOLUTION ADOPTED**

Owner: Architecture Owner

Raised by: `FEAT-AUD-001`

## Baseline Defect

Plan section 14.4 assigns `ARC-VERIFY-010` and `ARC-VERIFY-011` to audit
coverage and chain integrity. Section 19.8 instead defines `ARC-VERIFY-011` as
the no-write-without-`ActorContext` rule already owned and implemented by
`FEAT-PLAT-003`. The required daily audit-chain verification has no registered
`ARC-VERIFY` identifier.

## Resolution Adopted by This Feature

- `ARC-VERIFY-010` remains the audit-coverage and append-only integration
  obligation owned by `FEAT-AUD-001`.
- `ARC-VERIFY-011` remains the actor-context rule owned by `FEAT-PLAT-003` and
  is neither reimplemented nor relabelled by audit.
- Daily open-chain verification is retained as a named audit obligation with
  cadence, result, evidence, and P1 failure behavior, but its evidence-register
  entry explicitly records that the baseline assigns no identifier.
- No new `ARC-VERIFY` identifier is invented locally.

## Next-Baseline Action

Correct section 14.4 to match section 19.8 and assign a unique identifier to
daily chain verification. Until then, retained evidence must use the named
obligation and state the identifier gap explicitly.
