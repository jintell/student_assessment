# FEAT-OBS-001 Phase 0 Gate-Prerequisite Record

Date assessed: 2026-09-29; dependency reassessed: 2026-10-02

## P0.1 - Architecture Authorization

The primary ratification path from `FEAT-PLAT-001` task `P0.5` is in force:
`ci/architecture-ratification.json` is `RATIFIED`, no
`temporaryArchitectureGate` is present, and `./ci/stage-4a` passes. Production
implementation is authorized subject to feature-specific dependencies and
blocking gates; `implementationAllowed: false` does not apply.

## P0.2 - FEAT-PLAT-001 Dependency

Baseline tasks `P4.7`-`P4.9`, `P9.1`, and `P9.2` are complete. The repository
contains the production `RequestContextWebFilter`, Micrometer Context
Propagation bridge, scheduler-hop propagation, the `<module>.<verbNoun>` span
contract, and the fixed log-field contract consumed by this feature.

Verification on 2026-09-29:

```text
./gradlew test \
  --tests 'org.meldtech.platform.shared.infra.web.RequestContextWebFilterTest' \
  --tests 'org.meldtech.platform.shared.infra.web.ReactorContextPropagationConfigurationTest' \
  --tests 'org.meldtech.platform.platform.slice.getConformanceReference.SliceTest' \
  --console=plain

BUILD SUCCESSFUL
```

Contract evidence:

- `docs/architecture/reactive-context-propagation.md`
- `docs/architecture/span-contract.md`
- `docs/architecture/log-field-contract.md`
- `docs/evidence/phase-9-monitoring-operations.md`

## P0.3 - FEAT-PLAT-003 Dependency

Kernel tasks `P4.13`, `P4.14`, and `P8.5` are complete. `CorrelationId`
accepts only canonical uppercase ULIDs, `UlidCorrelationIdGenerator` generates
the server-owned value, `ActorContext` is the request and work carrier, and
`SecretFieldPattern` is defined once in `shared.kernel.security`. The
`FEAT-PLAT-003` interface-handover record explicitly assigns these contracts
to `FEAT-OBS-001` for consumption.

Verification on 2026-09-29:

```text
./gradlew test \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  --tests 'org.meldtech.platform.shared.kernel.security.SecretFieldPatternTest' \
  --tests 'org.meldtech.platform.platform.infra.context.UlidCorrelationIdGeneratorTest' \
  --console=plain

BUILD SUCCESSFUL
```

Handover evidence: `docs/evidence/FEAT-PLAT-003/P8.5-interface-handovers.md`.

## P0.4 - FEAT-PLAT-004 Dependency

Outbox tasks `P2.1` and `P4.3` are complete. Migration
`db/migration/outbox/V3__create_outbox_event.sql` defines the
`outbox.outbox_event` carrier with `correlation_id`, `traceparent`, and
`tracestate`. `ReactiveOutboxWriter` persists the values from the request
context, the relay claim preserves them in `ClaimedOutboxEvent`, and
`RabbitOutboxBrokerPublisher` maps them to broker message headers.

Verification on 2026-10-02:

```text
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.outbox.RabbitPublishersTest' \
  --tests 'org.meldtech.platform.platform.infra.outbox.OutboxContextCarrierTest' \
  --tests 'org.meldtech.platform.platform.infra.outbox.ReferenceOutboxComponentsTest' \
  --console=plain

BUILD SUCCESSFUL
```

Implementation evidence:

- `src/main/resources/db/migration/outbox/V3__create_outbox_event.sql`
- `src/main/java/org/meldtech/platform/platform/infra/outbox/ReactiveOutboxWriter.java`
- `src/main/java/org/meldtech/platform/platform/infra/outbox/OutboxClaimRepository.java`
- `src/main/java/org/meldtech/platform/platform/infra/outbox/RabbitOutboxBrokerPublisher.java`

Status: SATISFIED. The carrier exists before observability task `P4.23`
consumes it; `FEAT-OBS-001` will verify the end-to-end join and will not
rebuild the carriage.

## P0.5 - Telemetry Backend Ownership Gap

`TASK-OBS1-DEFECT-001` is raised to the Engineering Lead and Platform Ops.
Plan section 22 names Platform Ops as the owner of Phase 0 observability-stack
provisioning, while `FEAT-OBS-001` retains the application-side OTLP contract.
The backend product and production endpoint are deferred to that provisioning
track; development and CI use only the ephemeral OTLP-compatible sink owned by
`P3.7`.

Evidence: `docs/defects/TASK-OBS1-DEFECT-001.md`.

Status: ESCALATED WITH OWNERSHIP AND INTERIM ENDPOINT RECORDED. Observability
task `P3.5`, production deployment, and the Phase 0 exit criterion remain
blocked until Platform Ops and the Engineering Lead approve the concrete
collector contract, including TLS, workload identity, and tail sampling.

## P0.6 - Missing Verification Ownership

`TASK-OBS1-DEFECT-002` is raised to the Architecture Owner because plan
section 14.4 assigns no verification scenario to `FEAT-OBS-001` despite the
feature card's three named testing expectations. The correction record maps
correlation propagation to CI stage 8, the log leak scan to stage 10, and
business-event completeness to stages 4 and 5. It deliberately introduces no
new `ARC-VERIFY` identifier.

Evidence: `docs/defects/TASK-OBS1-DEFECT-002.md`.

Status: RAISED WITH LOCAL CI OWNERSHIP RECORDED. Detailed alignment with the
existing section 19.8 scenarios remains assigned to observability task
`P1.7`.

## P0.7 - Secret-Pattern policy_key Conflict

`TASK-OBS1-DEFECT-003` is raised to the `FEAT-PLAT-003` owner at the pattern's
single definition site. The current `SecretFieldPattern` uses whole-segment
matching, but `policy_key` normalizes to the segments `policy` and `key`, so
the architecture-required metric label is rejected. A local observability
exception is prohibited.

Evidence: `docs/defects/TASK-OBS1-DEFECT-003.md`.

Status: RAISED WITH THE REQUIRED KERNEL CORRECTION AND PROOF RECORDED.
Observability task `P4.5` remains blocked until the kernel owner implements
the enumerated `policy_key` allowance and its positive and negative tests.

## P0.8 - Feature-Specific Definition of Ready

The Solution Architect, Security, and Platform Ops approved the structured-log
field set, business-event metric names, and trace-attribute conventions. The
signed contract fixes these six MVP metric names:

- `exam_started_total`
- `exam_finished_total`
- `pin_validation_total`
- `result_published_total`
- `correction_applied_total`
- `provisional_feedback_released_total`

It also records `SYNC_OUTCOME` and `PAYMENT_OUTCOME` as Post-MVP and absent,
forbids `tenantId` and `correlationId` as metric labels, and adopts W3C trace
context with the section 16.3 prohibited-attribute categories.

Signed evidence:

- `ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.json`
- `ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.solution-architect.sig`
- `ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.security.sig`
- `ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.platform-ops.sig`

All three detached signatures were verified with GnuPG on 2026-10-02.
Status: APPROVED FOR FEATURE DESIGN AND IMPLEMENTATION, subject to the
unresolved blockers recorded by `P0.5` and `P0.7` at their named downstream
tasks.

## P0.9 - Universal Definition of Ready

All seven plan section 8.0 readiness criteria are satisfied: requirements v3.7
and architecture v1.4 are approved baselines; acceptance outcomes are stated
and testable; architecture references resolve; `FEAT-PLAT-001`,
`FEAT-PLAT-003`, and `FEAT-PLAT-004` dependencies are delivered; no open
requirements question applies; security expectations are identified; and the
consumed interfaces are defined. The feature-specific DoR from `P0.8` is also
approved.

The record preserves two scoped blockers rather than treating them as waived:
`TASK-OBS1-DEFECT-001` blocks `P3.5` and production, and
`TASK-OBS1-DEFECT-003` blocks `P4.5`. Neither blocks Phase 1 discovery or Phase
2 design.

Signed evidence:

- `ci/dor/FEAT-OBS-001/P0.9-universal-dor.json`
- `ci/dor/FEAT-OBS-001/P0.9-universal-dor.solution-architect.sig`
- `ci/dor/FEAT-OBS-001/P0.9-universal-dor.engineering-lead.sig`

Both detached signatures were verified with GnuPG on 2026-10-02. Status:
APPROVED FOR IMPLEMENTATION WITH THE RECORDED TASK-SCOPED RESTRICTIONS;
PRODUCTION RELEASE IS NOT APPROVED.
