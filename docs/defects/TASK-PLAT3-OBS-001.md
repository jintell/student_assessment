# TASK-PLAT3-OBS-001 Unmapped Problem Alert and Panel Gap

Status: **OPEN - RAISED AND HANDED OFF**

Owners: `FEAT-OBS-001` (metric registration), `FEAT-OPS-004` (alert rules,
routing, dashboard, and exercise evidence)

Architecture documentation owner: Architecture Owner

Raised by: `FEAT-PLAT-003`

## Gap

The kernel emits `problem_detail_unmapped_total` when an exception reaches the
client boundary without an intentional catalogue mapping, but the approved
observability baseline does not register an alert or dashboard panel for it.
Without those downstream controls, clients receive the safe generic response
while an unanticipated failure class may remain operationally invisible.

## Resolution Adopted by This Feature

`FEAT-PLAT-003` emits `problem_detail_emitted_total{code}` and
`problem_detail_unmapped_total{reason}` from the final mapper outcome, plus
`idempotency_replay_total{outcome}` and the untagged
`idempotency_store_unavailable_total`. Labels are closed and bounded, and
problem counters retain trace exemplars without using correlation identifiers
as metric labels. The feature also publishes both operations runbooks.

## Proposed P2 alert

Name: `SustainedUnmappedProblemDetails`

```promql
sum(rate(problem_detail_unmapped_total[10m])) > 0
```

Pending production traffic calibration, require the expression to remain true
for ten minutes and route it as P2. The alert must preserve the bounded
`reason` breakdown in linked diagnostics but must not add exception class,
message, route instance, correlation, actor, or tenant labels.

First action: **identify the unmapped exception and add a catalogue entry**.
Start from a recent problem metric exemplar, follow its trace to the
`correlationId`, retrieve the matching protected logs, and determine whether
the exception needs an intentional fixed public mapping or represents a defect
in the calling path. Do not expose the exception message or suppress the
counter as noise.

## Proposed platform-health panel

Add an **Error contract** group to the platform-health dashboard:

| View | PromQL | Purpose |
|---|---|---|
| Unmapped error rate | `sum by (reason) (rate(problem_detail_unmapped_total[$__rate_interval]))` | Detect unanticipated failure paths and mapper fallback modes |
| Top emitted error codes | `topk(10, sum by (code) (rate(problem_detail_emitted_total[$__rate_interval])))` | Show the stable client-visible codes currently dominating failures |

The panel links to `docs/runbook-problem-detail-unmapped.md`, the error
catalogue, and the P9.4 diagnosability evidence.

## Closure criteria

- `FEAT-OBS-001` registers both metric contracts and retains exemplar support.
- `FEAT-OPS-004` installs and routes the P2 rule and adds both panels.
- A synthetic unmapped exception sustains the rule, produces retained
  notification evidence, and resolves through the runbook to the originating
  protected log.
- The alert clears after the exception receives an intentional mapping or the
  defective path is removed; it is never closed by muting the signal.

## Next-Baseline Action

Add the four metric contracts to architecture section 16.2. Add the P2 alert
and both platform-health views to the observability baseline, naming
`FEAT-OBS-001` as catalogue owner and `FEAT-OPS-004` as alert, routing,
dashboard, and exercise owner.
