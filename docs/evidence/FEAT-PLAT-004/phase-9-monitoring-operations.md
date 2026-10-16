# Outbox Phase 9 Monitoring and Operations

Date: 2026-10-16. Scope: P9.1–P9.10, sequential execution with blocked work recorded and skipped as requested.

## P9.1 Metric Contract

Published the TASK-PLAT4-OBS-001 [catalogue addendum](../../metric-catalogue.md#outbox-addendum--task-plat4-obs-001), cardinality budgets and worker-only telemetry bean. Four telemetry tests pass, including actual Prometheus names, bounded labels, registration scope, unknown initial backlog and rejection of invalid snapshots. `metricCardinalityGate` and `telemetrySchemaGate` pass. This defines/registers meters; the production relay/sampling assembly remains missing under P8.5.

## P9.2 Backlog Alert — Blocked for Live Acceptance

Implemented `config/alerting/outbox-backlog.rules.yml`: pending depth strictly greater than 5,000 OR oldest pending age strictly greater than 300 seconds, severity P2, no additional hold-down. Cluster/namespace/job grouping isolates deployments and `max` avoids summing duplicated cluster-wide snapshots across workers. Ops must preserve those deployment labels in scrape configuration.

`outbox-backlog.test.yml` passes with local promtool from image ID `sha256:5ccfaca220fcb8f8144d7eedd9899246f4ad8273ccfe6eb079264a612df15f92`. Tests cover exact boundaries, each independent trigger, recovery, duplicate worker snapshots, non-PENDING rows and unknown samples. Command: `docker run --rm --entrypoint /bin/promtool --mount type=bind,source=<repository>/config/alerting,target=/rules,readonly <image-id> test rules /rules/outbox-backlog.test.yml`.

**Blockers:** no production backlog sampling caller, enabled relay assembly, provisioned staging telemetry target, installed rule/routing evidence or staging notification proof. NaN/absent samples do not trigger a threshold rule and must never count as readiness; Ops must monitor collection freshness separately. P9.2 stays open until the rule is installed, real backlog/age triggers are exercised and P2 routing/recovery are retained. No deployment or staging firing is claimed.

## P9.3–P9.4 Ops Intake

Published [four alert proposals](P9.3-alert-proposals.md) and the [dashboard 6 panel request](P9.4-dashboard-request.md) for FEAT-OPS-004. These identify the baseline omissions, bounded signal contracts, missing collectors and required staging evidence. Publication is repository handover; receipt and deployment are pending.

## P9.5 Relay Span and Carrier

Added the missing `outbox relay batch` span to `RelayPublishStep.publishSequentially`, with an explicit tracer dependency. One span starts lazily for each subscription and ends on success, error or cancellation. Only a bounded outcome is recorded; driver exception messages and payloads are excluded. Success means the batch publisher completed, not that every event was delivered (handled failures may have been requeued).

A batch may contain events from different producer traces, so its operational span has no arbitrary event parent. Each event retains its original W3C headers; the batch does not replace them. The PostgreSQL/RabbitMQ `ObservabilityPropagationIntegrationTest` proves row → message → consumer continuation, stale-context behavior and preserved per-event carriers across a batch, now also asserting the exact batch span name. `RelayBatchTracingTest` proves lazy subscription, repeated subscriptions, terminal cleanup and diagnostic hygiene; `RelayPublishStepTest` still passes.

Command: `./gradlew test --tests '*RelayBatchTracingTest' --tests '*RelayPublishStepTest' integrationTest --tests '*ObservabilityPropagationIntegrationTest'` — PASS. The existing FEAT-OBS-001 end-to-end join owner is unchanged. This is local adapter/carrier evidence, not deployed relay operation; P8.5 assembly must inject the platform tracer.

## P9.6–P9.10 Recovery and Readiness

Published the [backlog](../../runbooks/outbox-backlog.md), [FAILED-row](../../runbooks/outbox-failed-rows.md), [integration DLQ](../../runbooks/integration-dlq.md) and [stalled-relay](../../runbooks/outbox-relay-stalled.md) runbooks. Procedures were checked against configuration bounds, redrive/guard/audit boundaries, version dispatch, registry locking and stale-claim adapters. They avoid invented operator endpoints, blind redrive of unsupported versions, manual state resets and guarantees unsupported by missing production wiring. The grading stale-claim reservation is explicitly not an outbox reclamation schedule.

The [Phase 6 Ops readiness handoff](P9.10-operational-readiness-handoff.md) supplies the four requested checks and evidence requirements to FEAT-OPS-001 through repository publication. External receipt, actual live checks and release approval remain pending. All ten changed/new operational documents have valid local link targets.

## Final Verification

`./gradlew compileJava compileTestJava ciStage4 ciStage5 ciStage7 ciStage8 ciStage9 spotlessCheck` — PASS on 2026-10-16 (3m 9s). Test XML reports 1,452 tests, zero failures/errors/skips:

| Suite | Tests |
|---|---:|
| Unit | 654 |
| Conformance | 106 |
| Runtime role contexts | 29 |
| Slices | 17 |
| PostgreSQL/RabbitMQ integration | 645 |
| Daily audit-chain verification | 1 |

Coverage, metric cardinality, telemetry schema, business-event completeness, slice coverage, query budget, event schema/compatibility/payload policy and formatting gates pass. The backlog promtool tests also pass against the retained image ID above. `git diff --check` and operational-document local link checks pass. No failing tests remain in the executed suites. This verification does not substitute for the blocked staging acceptance, and does not claim mutation/security/load stages were run.

## Execution Summary

- Executed range: P9.1–P9.10, in order. Each completed task marker was persisted before proceeding.
- Completed: P9.1 and P9.3–P9.10; metric contracts/registration, alert and dashboard proposals, batch span/carrier evidence, four runbooks and readiness handoff.
- Blocked/skipped: P9.2 live acceptance; its local alert rule and tests are delivered, but the production sampler/relay and staged notification evidence are missing. No staging credentials or target were supplied.
- Risks: registered metrics are not live sampling; published requests are not adopted alerts/dashboards; repository handoff is not external acknowledgement. Existing P8.5/P8.6 and other retained release gates remain open.
- Resume P9.2 with production wiring, provisioned staging scrape/routing and retained trigger/recovery evidence. Recommended next implementation range: P10.1–P10.12, subject to its prerequisites; no Phase 10 task was executed here.
