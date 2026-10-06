# TASK-OBS1-DEFECT-006 Mis-Cited Observability Startup Verification

Status: **OPEN - RAISED FOR NEXT BASELINE; OWNERSHIP CORRECTED LOCALLY**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Defect

`ARC-OBS-004` refers to startup configuration validation in architecture
section 17.5. Plan section 14.4 assigns `ARC-VERIFY-018` to `FEAT-PLAT-002`
and describes it as row-level security returning zero rows without a tenant
predicate. That description actually belongs to `ARC-VERIFY-005`, while the
verification register defines `ARC-VERIFY-018` as rejection of out-of-range
configuration. The plan row therefore contains both an ownership and a
scenario-description error.

## Resolution Adopted by This Feature

`FEAT-OBS-001` implements only the observability-configuration limb: collector
endpoint/protocol/TLS, explicit sampling ratios, the exact redaction allowlist,
candidate-hash secret reference, bounded export queues/deadlines, trace age,
and metric catalogue ceilings. Missing or out-of-range values fail startup.
Security, authorization, tenancy, and policy configuration remain with their
owning features. No replacement `ARC-VERIFY` identifier was invented.

## Next-Baseline Action

Correct plan section 14.4 to use the registered meaning of
`ARC-VERIFY-018`, partition its configuration cases by owning feature, and
restore the RLS scenario to `ARC-VERIFY-005`/`FEAT-PLAT-002`. Keep this
feature's evidence scoped to the observability limb.

## Evidence

- `ObservabilityConfigurationValidator`
- `ObservabilityConfigurationValidatorTest`
- `ObservabilityPropertiesTest`
- `docs/evidence/FEAT-OBS-001/phase-2-architecture-design.md`, `P2.13`

## Closure Criteria

- The plan uses the correct scenario descriptions and owners.
- The observability limb remains a startup-failure contract.
- No duplicate or substitute verification identifier is introduced.
