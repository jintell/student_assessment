# TASK-PLAT3-DEFECT-003 Missing Feature Verification Ownership

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-003`

## Baseline Defect

The `FEAT-PLAT-003` feature card and plan verification-ownership table omit two
section 19.8 scenarios that directly verify the feature's acceptance outcomes:
`ARC-VERIFY-011` requires an `ActorContext` on every write path and enumerated
system actors, while `ARC-VERIFY-013` requires injected faults to produce only
allowlisted problem bodies with a correlation identifier.

## Resolution Adopted by This Feature

`FEAT-PLAT-003` adopted `ARC-VERIFY-011` as an owned blocking CI stage 4
obligation and `ARC-VERIFY-013` as an owned blocking CI stage 10 obligation.
The actor-attribution conformance rule, fault-injection suite, allowlist result,
and retained evidence all use those existing identifiers.

## Next-Baseline Action

Add both scenarios to the feature card's testing expectations, Definition of
Done, section 14.4 ownership table, and section 19 traceability. Preserve their
existing section 19.8 definitions and gates.
