# TASK-OBS1-OBS-001 Missing Per-Route Query-Budget Gate

Status: **OPEN - RAISED FOR NEXT BASELINE; CI STAGE 8 GATE IMPLEMENTED**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Observation

Architecture section 16.1 mandates `dbQueryCount` and section 15.2 defines
per-route budgets, but the approved pipeline assigns no stage that fails when
a route exceeds its budget. Without an executable assertion, excess round
trips surface late as a capacity or latency regression.

## Resolution Adopted by This Feature

`TracingConnectionFactory` counts every executed R2DBC statement in the
request's Reactor context independently of trace sampling. The final count is
emitted on every terminal path. `config/observability/query-budgets.json`
registers exact per-route totals and `QueryBudgetGate` checks unique compiled
route identity, coherent fixed/slice components, and actual counts. The gate
is blocking in CI stage 8; a negative test proves one added query fails.

## Next-Baseline Action

Add the per-route query-budget assertion to the pipeline definition at stage
8 and assign every route owner the obligation to register and test an exact
budget. Assign `FEAT-OPS-005` the aggregate stage 17 regression and capacity
evidence without weakening the per-request gate.

## Evidence

- `RequestQueryContext`
- `TracingConnectionFactory`
- `QueryBudgetGate`
- `config/observability/query-budgets.json`
- `ObservabilityContractGatesTest.queryBudgetGateRejectsAnAddedReferenceSliceQuery`

## Closure Criteria

- The next baseline names CI stage 8 as the blocking query-budget owner.
- Every request route has an exact executable budget.
- `FEAT-OPS-005` retains capacity evidence and does not average away a
  per-request violation.
