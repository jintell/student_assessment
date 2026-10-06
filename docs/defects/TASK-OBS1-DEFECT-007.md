# TASK-OBS1-DEFECT-007 Undefined Error Trace Sampling Location

Status: **OPEN - RAISED FOR NEXT BASELINE; COLLECTOR CONTRACT APPROVED**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Defect

Architecture section 16.3 requires retention of 100% of error traces but does
not state where the decision occurs. A head sampler cannot know whether a
trace will later fail. Applying a 10% application head sample to standard
requests can discard a trace before its eventual error is visible, making the
retention requirement impossible.

## Resolution Adopted by This Feature

The application uses a `1.0` head ratio for exam-entry, grading, and standard
route classes and marks critical versus standard priority. The mandatory
collector tail processor retains all error traces and critical route traces,
then deterministically retains 10% of the remaining complete traces. The
signed collector contract fixes the decision window, capacity rule, and
production proof. Sampling never changes propagation or query counting.

## Next-Baseline Action

State explicitly that error retention is a collector tail-sampling obligation
and that the application exports complete candidate traces. Link the rule to
the observability-backend ownership raised by `TASK-OBS1-DEFECT-001` so the
collector is a launch prerequisite rather than an optional deployment detail.

## Evidence

- `HybridRouteSampler` and `HybridRouteSamplerTest`
- `ci/dor/FEAT-OBS-001/P3.5-collector-contract.json`
- `docs/tracing-contract.md`

## Closure Criteria

- The next architecture baseline names the head and tail decision locations.
- Error and critical-route retention remain 100%.
- Collector capacity and policy are verified before production.
