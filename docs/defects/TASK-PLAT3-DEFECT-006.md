# TASK-PLAT3-DEFECT-006 Undefined Correlation-Identifier Form

Status: **OPEN - RAISED FOR NEXT BASELINE; LOCAL DECISION APPROVED**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-003`

## Baseline Defect

Architecture section 16.1 accepts `X-Correlation-Id` when it is
"well-formed" but does not define that term. The accepted value reaches every
response, problem body, trace, and log line, so permissive free text creates
log-injection, identifier-spoofing, and unbounded-input risks.

## Resolution Adopted by This Feature

The Solution Architect and Security approved canonical uppercase ULID with the
pattern `^[0-7][0-9A-HJKMNP-TV-Z]{25}$`. Missing or invalid input is replaced
with a server-generated ULID. Malformed, repeated, oversized, lowercase,
ambiguous-character, and control-character-bearing input is never echoed or
logged. The identifier contains no personal data.

## Next-Baseline Action

Define "well-formed" in section 16.1 using the approved ULID pattern and
replace-not-echo behavior. Carry the same rule into the error model, OpenAPI
schema, logging contract, and security guidance. Preserve Security review as a
requirement for any future identifier or validation change.

## Evidence

- `ci/dor/FEAT-PLAT-003/P0.4-correlation-identifier-approval.json`
- `ci/dor/FEAT-PLAT-003/P0.4-correlation-identifier-approval.solution-architect.sig`
- `ci/dor/FEAT-PLAT-003/P0.4-correlation-identifier-approval.security.sig`
- `docs/evidence/FEAT-PLAT-003/phase-6-security-hardening.md`
