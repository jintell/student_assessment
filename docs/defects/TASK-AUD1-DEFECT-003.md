# TASK-AUD1-DEFECT-003 Secret Pattern Conflicts With policy_key

Status: **CLOSED - JOINT KERNEL CORRECTION VERIFIED**

Owner: `FEAT-PLAT-003`

Raised jointly by: `FEAT-AUD-001` and `FEAT-OBS-001`

Related record: `docs/defects/TASK-OBS1-DEFECT-003.md`

## Baseline Defect

The architecture requires audit disposition evidence to include `policy_key`,
while its secret-field rule classifies arbitrary `key` fields as secret. A
literal application of the rule would reject the mandatory
`AUDIT_EPOCH_DISPOSED` payload.

## Resolution

`SecretFieldPattern` remains the single owner of the security decision. Its
immutable permitted-key catalogue contains exactly `policy_key` and accepts
the canonical snake-case, camel-case, hyphenated, and nested-path spellings.
Secret parent paths and every other `*_key` remain rejected.

The observability configuration validator now consumes
`SecretFieldPattern.permittedKeyFields()` rather than maintaining a second
copy of the catalogue. Audit must consume the same kernel API and may not add
an audit-local exception or profile switch.

## Verification

The focused suite proves the approved spellings, near misses, secret parent
paths, redaction behavior, and configuration validation. A source-wide check
finds the `policy_key` catalogue only in `SecretFieldPattern` production code.

The remaining next-baseline documentation correction in the observability
record does not block this feature's use of the implemented kernel contract.
