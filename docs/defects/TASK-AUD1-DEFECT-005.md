# TASK-AUD1-DEFECT-005 Phase 0 DoD conflicts with Phase 6 evidence

Status: **OPEN FOR NEXT BASELINE — PHASE 6 GATING ADOPTED LOCALLY**

Owner: Architecture Owner; coordination with Plan Owner and FEAT-OPS-005.
Raised by: FEAT-AUD-001. Repository escalation: P10.10, 2026-10-18.

## Baseline defect

The feature card requires A6/A7 discharge in its Definition of Done while its
delivery row places full-profile evidence in Phase 6. Foundation implementation
cannot discharge a future production-shaped performance/lifecycle condition.

## Resolution adopted

Separate Phase 0 implementation, fixture design and non-load tests from A6
(`ARC-VERIFY-031`) and A7 (`ARC-VERIFY-032`) Phase 6 acceptance. Preserve the
full §14.5 warning: a run that meets the latency target without recording
contention does not discharge A6. Require lock wait, shard skew and audit share
of transaction time alongside latency and concurrent-close proof.

The [published evidence obligation](../audit-a6-a7-evidence.md) defines both
run packets, owners and gates. P7.24 remains blocked: even the measured harness
is incomplete because the answer route and telemetry are absent. Neither this
record nor passing isolated integration tests asserts the harness is complete
or waives A6/A7.

## Owner action

Align the next feature-card baseline and retain the Phase 6 acceptance gates.
No new owner acknowledgement or external delivery receipt is available;
P10.10 tracks delivery separately from this repository record.
