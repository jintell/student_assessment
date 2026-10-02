# TASK-OBS1-DEFECT-002 Missing Observability Verification Ownership

Status: **OPEN - RAISED FOR NEXT PLAN BASELINE; LOCAL CI OWNERSHIP RECORDED**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Defect

Plan section 8.1 gives `FEAT-OBS-001` three explicit testing expectations,
but section 14.4 assigns none of the `ARC-VERIFY` register to this feature.
The omission leaves the feature Definition of Done without a verification
owner even though the pipeline already has suitable blocking stages.

The three omitted expectations are:

- correlation propagation across HTTP, transaction, outbox, broker, and
  consumer;
- the CI stage 10 log-limb secret-leak scan; and
- completeness of the six MVP business-event metrics.

## Proposed Correction

Record the expectations against the existing pipeline stages and retain their
results in the section 19.9 evidence register. Do not create a new
`ARC-VERIFY` identifier solely to repair plan attribution.

| Expectation | Owning feature | Existing gate | Retained evidence |
|---|---|---|---|
| End-to-end correlation and W3C trace-context propagation | `FEAT-OBS-001`, consuming `FEAT-PLAT-003` and `FEAT-PLAT-004` carriers | CI stage 8 integration tests | Propagation test report |
| Log-limb secret-leak scan | `FEAT-OBS-001`, consumed by `FEAT-SEC-001` | CI stage 10 security suite | Log leak-scan result |
| Six-event metric-set completeness | `FEAT-OBS-001` | CI stage 4 structural assertion, with negative proof in stage 5 | Business-event completeness result |

The detailed mapping to existing section 19.8 scenarios remains owned by
observability task `P1.7`; this record does not broaden or rename those
scenarios.

## Next-Baseline Action

Amend plan section 14.4 to include the table above and section 19.9 to name the
three retained artifacts. Preserve the feature card's blocking semantics and
do not assign dashboard, alert, or performance-run ownership to this feature.

## Closure Criteria

- The next plan baseline attributes all three expectations to
  `FEAT-OBS-001` and their existing gates.
- CI retains the three named results.
- The verification catalogue gains no duplicate scenario identifier.
