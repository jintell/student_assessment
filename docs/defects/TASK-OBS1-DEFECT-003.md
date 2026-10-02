# TASK-OBS1-DEFECT-003 Secret Pattern Conflicts With policy_key

Status: **OPEN - RAISED TO FEAT-PLAT-003 OWNER; BLOCKS OBSERVABILITY P4.5**

Owner: `FEAT-PLAT-003`

Raised by: `FEAT-OBS-001`

## Baseline Defect

Architecture section 16.1 includes `key` in the canonical secret-field
pattern, while section 16.2 requires `policy_key` on retention-disposition
telemetry. The shared `SecretFieldPattern` correctly matches normalized whole
segments rather than arbitrary substrings, but currently splits `policy_key`
into `policy` and `key` and therefore classifies the required label as secret.

Building a private exception into the logging, tracing, or metrics adapters
would create multiple definitions of the security rule and violate the
kernel's ownership contract.

## Required Correction

Correct the single definition in
`shared.kernel.security.SecretFieldPattern` before observability task `P4.5`:

- keep whole-field and word-boundary matching for `pin`, `otp`, `token`,
  `secret`, `password`, `key`, and `authorization`;
- add an enumerated exact-name allowlist for approved non-secret key fields,
  initially only the architecture-required `policy_key` / `policyKey` form;
- apply the allowance to the leaf field name so a structured path such as
  `retention.policy_key` behaves consistently;
- continue to reject `api_key`, `encryption_key`, `privateKey`, and unknown
  `*_key` names; and
- keep the rule in the kernel so logging, spans, metric labels, audit, errors,
  and event payload checks consume one decision.

## Required Proof

Kernel tests must demonstrate that `policy_key`, `policyKey`, and a nested
`retention.policy_key` path are permitted, while secret key forms and the
other canonical secret segments remain rejected. A source-wide single-
definition check must remain green.

## Closure Criteria

- The kernel owner implements and tests the enumerated allowance.
- Every consumer continues to call `SecretFieldPattern` rather than carrying
  a local exception.
- Observability task `P4.5` verifies the corrected contract before building
  its redactor.
- The next architecture baseline documents the permitted-key exception next
  to `ARC-OBS-002`.
