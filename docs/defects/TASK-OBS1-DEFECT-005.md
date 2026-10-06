# TASK-OBS1-DEFECT-005 Conflicting Business-Event Metric Set

Status: **OPEN - RAISED FOR NEXT BASELINE; NORMATIVE SET IMPLEMENTED**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Defect

The `FEAT-OBS-001` feature card names four business-event metric categories,
while architecture section 16.2 names six MVP events plus sync and payment
outcomes that belong to Post-MVP capabilities. A completeness assertion based
on the shorter card would certify an incomplete MVP contract; one based on all
eight would incorrectly block MVP on absent future capabilities.

## Resolution Adopted by This Feature

Section 16.2 is normative. The closed MVP enumeration is `EXAM_STARTED`,
`EXAM_FINISHED`, `PIN_VALIDATION`, `RESULT_PUBLISHED`,
`CORRECTION_APPLIED`, and `PROVISIONAL_FEEDBACK_RELEASED`. Sync and payment
outcomes are explicitly declared absent until their Post-MVP features exist.
`BusinessEventCompletenessGate` compares the approved contract, Java enum,
adapter registrations, and cardinality catalogue so removal or unregistered
addition fails the build.

## Next-Baseline Action

Update the feature card to list the six MVP events and identify sync/payment
outcomes as Post-MVP. Preserve the closed-enumeration and declared-absence
semantics rather than reducing the check to a naming convention.

## Evidence

- `BusinessEventCode`
- `MicrometerBusinessEventRecorder.registeredMetricNames()`
- `BusinessEventCompletenessGate`
- `config/observability/metric-cardinality.json`
- `docs/evidence/FEAT-OBS-001/P7.3-business-event-completeness-result.json`

## Closure Criteria

- The next plan baseline states one consistent six-event MVP set.
- Post-MVP events remain explicitly assigned rather than appearing missing.
- CI stage 4 retains the completeness assertion.
