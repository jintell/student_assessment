# TASK-AUD1-OBS-001 Audit measurement cardinality and mutation scope

Status: **PARTIAL — INTEGRITY METRICS REGISTERED; CONTENTION AND PIT OPEN**

Owner: Architecture Owner, with FEAT-OBS-001 and platform CI owners.
Raised by: FEAT-AUD-001. Repository escalation: P10.10, 2026-10-18.

## Baseline defect

The baseline names integrity metrics without registration, asks for lock-wait
percentiles by shard without a bounded-series design, and omits pure chain
algebra from the mutation-testing rationale. Tenant × 64-shard labels violate
the observability cardinality guard. Symmetric producer/verifier bugs can pass
examples while proving the wrong history.

## Resolution adopted and remaining work

P9.1 registers eight bounded integrity metrics with a tested Micrometer adapter;
unknown sampled gauges remain NaN. This is not live provider/alert wiring.
See [registration evidence](../evidence/FEAT-AUD-001/P9.1-integrity-metric-registration.md).

P9.2 remains blocked on true lock-wait observation, complete windowed shard
sampling and protected exemplar/export integration. Aggregate percentiles
without tenant/shard series; retain shard context only through approved bounded
evidence/exemplars. Agree zero-median/empty-inventory semantics and prove exact
series budgets. Do not relabel total query duration as lock wait or add a third
audit SQL statement. See the [measurement blocker](../evidence/FEAT-AUD-001/P9.2-contention-instrumentation-blocker.md).

The [audit PIT proposal](../../config/audit/stage-6-pitest-proposal.yaml) remains
PROPOSED: pure `audit.domain`, golden/algebra tests, 90% mutation and coverage
thresholds, no exclusions. Stage 6 now exists for runtime state machines at
80%, but its configured targets still exclude audit. The older P3.14 record's
statement that Stage 6/domain do not exist is historical, not current status.
Adding audit requires the approved CI scope/resource decision and retained
mutation evidence, not silently claiming the runtime gate covers it.

## Owner action

Resolve the measurement contract jointly with observability and approve/reject
the audit mutation extension with CI ownership. Retain decisions and actual run
evidence before closure. No external delivery or new acknowledgement is claimed.
