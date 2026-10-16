# Outbox Phase 8 Deployment and Release

Date: 2026-10-16. Scope: P8.1–P8.9. Evidence is local unless explicitly identified as deployed evidence.

## P8.1 Real Outbox Integration Gate

`ci/stage-8` passed. `ciStage8` depends on the complete `integrationTest` source set, daily audit-chain verification and the query-budget gate. The outbox tests use the approved PostgreSQL container and digest-pinned RabbitMQ container; there is no Docker-absent skip or in-memory broker substitution.

The `integration-tests` workflow job runs this blocking stage after slice tests, without `continue-on-error`. Its named PostgreSQL/RabbitMQ step now retains XML and HTML integration reports on success or failure for 90 days. Downstream jobs depend on its successful completion. The workflow supply-chain security check passed. This confirms the checked-in workflow and local executable gate, not a hosted Actions run or branch-protection configuration.

The full integration run executed 640 tests with zero failures/errors/skips, plus one daily audit-chain verification case. `queryBudgetGate` also passed.

## P8.2 Event Contract and Payload Gate

Added executable `ci/stage-9` and the `event-contracts` Actions job, chained between integration tests and stage 10. The existing `ciStage9` task runs schema/code consistency, baseline compatibility and payload minimisation checks. No job or step permits failure.

Verified the exact stage entrypoint with temporary schema fixtures, removed immediately after each negative run:

| Input | Exit | Blocking diagnostic |
|---|---:|---|
| Registered v1 baseline includes `legacyField`, current v1 omits it | 1 | `eventSchemaCompatibility`: `COMPAT_FIELD_REMOVED event=platform.ReferenceEvent.v1 pointer=/properties/legacyField` |
| Current schema adds optional `pinCiphertext`, classified operational | 1 | `verifyEventPayloadPolicy`: `PAYLOAD_SECRET_FIELD platform.ReferenceEvent.v1.json pinCiphertext` |
| Original schemas restored | 0 | `ci/stage-9` BUILD SUCCESSFUL |

The credential-shaped fixture contains only a field declaration, no credential. `ci/verify-workflow-security` passed with the new job. Registered contracts have no retained changes.

## P8.3 Outbox Conformance Gate

`ciStage4` already depends on the full conformance source set. Its description and workflow step now explicitly identify outbox propagation and answer-path enforcement. `R7OutboxPropagationTests` (5 cases) and `AnswerAcceptanceOutboxTests` (2 cases), including deliberate negative fixtures, run alongside R1–R8. `./gradlew ciStage4 --rerun-tasks` passed, executing all 106 conformance cases afresh.

The workflow triggers on pushes to `main`, PRs targeting `main`, and reusable workflow calls. There is no schedule-only path or test filter. The stage shell propagates the Gradle exit status through `PIPESTATUS[0]`, and downstream jobs require successful architecture conformance. Retained negative build evidence also exists in [P7.16](P7.16-outbox-conformance-negative-build.md).

## P8.4 Relay Connection Envelope

The runtime allocation supersedes P1.8's original single shared-pool description: `4 grading + 2 sweep-lock + 4 background = 10` physical connections per worker. The relay may hold one lock connection while using one background connection for a bounded transaction. The other lock permit belongs to the serialized sweep lane; the remaining three background connections support sweep/dispatcher work. The relay adds no pool, login, replica or connection allocation.

Authority: signed `ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`; matching inputs in `deploy/runtime/values.yaml` and `application.yaml`. See [runtime envelope registration](../FEAT-PLAT-006/P8.5-connection-envelope.md) and [persistence envelope definitions](../FEAT-PLAT-002/P8.4-connection-envelope-input.md).

| Operating point | allocated | allocated_with_headroom | committed | headroom_remaining |
|---|---:|---:|---:|---:|
| Baseline: 12 API / 6 worker / 2 pindist | 268 | 308 | 318 | 432 |
| Pre-warmed: 12 API / 10 worker / 2 pindist | 308 | 348 | 358 | 392 |
| HPA ceilings: 30 API / 24 worker / 2 pindist | 700 | 740 | 750 | 0 |

Recomputed using `allocated = API*14 + worker*10 + pindist*5 + 20 operations + 10 PostgreSQL reserved`, then +40 funded headroom, then +10 migration connections. `headroom_remaining = 750 - committed`; `max_connections` remains 750. Zero unallocated ceiling capacity still includes the forty funded headroom connections.

Verified `RuntimeDeploymentValuesTest`, `WorkloadConnectionPoolConfigurationTest`, `SweepConnectionBudgetIntegrationTest` and `WorkerPoolIsolationIntegrationTest`. The focused run exposed an order-dependent fixture: `WorkerPoolIsolationIntegrationTest` created `app_worker` as the cluster owner before migrations, preventing the migrator from altering it. The test now uses the existing migrated outbox fixture, preserving migration-owned roles and production grants. The same focused command then passed.

This registers the unchanged input to stage 4a's connection-envelope limb. The repository's stage 4a executes architecture ratification; deployed-envelope enforcement and ARC-VERIFY-033 remain owned by FEAT-OPS-004/005. No deployed capacity claim is made.

## P8.5 Background-Only Relay Profile — Blocked

The worker profile alone imports broker credentials and configures broker TLS, vhost and prefetch. `IntegrationTopologyConfiguration`, `BrokerCredentialConfiguration` and `BrokerTransportConfiguration` are worker-profile configurations. Existing `OutboxRoleIsolationTest`, `IntegrationTopologyConfigurationTest`, broker credential/TLS configuration tests and `RuntimeManifestVerifierTest` pass.

However, the positive production-relay prerequisite is absent: `RelaySingleton`, `RelayPublishStep`, the claim/publication adapters and loader are exercised by test wiring, but have no production relay assembly or `SweepDefinition`. `contracts/runtime-roles/worker.json` contains no entry points, and the persisted `outbox-relay` reservation is disabled. [The runtime adoption record](../../runtime-relay-adoption.md) explicitly distinguishes adapter delivery from production scheduling. Tests showing absent beans in request/isolated contexts do not prove an enabled background relay.

P8.5 stays open. The outbox/runtime owners must provide the reviewed relay assembly, worker manifest entry, secured transaction integration and enabled registry migration, then prove worker startup/publication and request/isolated-role exclusion with the actual assembly. Do not enable a reservation without its implementation or manufacture a second scheduler to pass this task. No profile values are changed merely to conceal this prerequisite.

## P8.6 Staging Provisioning — Blocked

The [provisioning record](P8.6-staging-broker-provisioning.md) records the missing target/identity/Ops evidence, proposed three-replica quorum target, existing restricted permission template and required policy/queue verification. No staging broker was provisioned, no user/policy was applied and no Ops acknowledgement was fabricated.

## P8.7 Rollback and P8.8 Deployment Ordering

The [release contract](../../outbox-release.md) documents additive migration preservation, stopping/draining the relay during code rollback, the independent audit record and the prohibition on partition detach until P7.18 has passed in staging. It explicitly separates the operational detach gate from existing database grants.

Migration Job success must precede worker rollout. The new worker-only `OutboxDatabasePrerequisites` bootstrap check fails startup for a missing relay role, missing partitioned outbox table or missing ability to assume the role, with a bounded ten-second read and sanitized error. It uses the existing secured worker pool and coordination transaction. Five real-PostgreSQL integration cases verify these conditions, successful startup, connection cleanup and exclusion from request/isolated profiles. This check does not resolve P8.5's missing relay assembly or enable publication.

## P8.9 Sibling Regression

[Retained regression evidence](P8.9-sibling-suite-regression.md) records the complete passing compile/CI 4/5/7/8/9/format run and all four ARC-VERIFY-024 cases. The final integration suite contains 645 passing cases, including the five startup checks. Workflow security review, shell syntax validation and whitespace checks also pass.

## Execution Summary

Executed P8.1–P8.9 in order, persisting each completed marker immediately and following the requested skip-and-record policy.

- Complete: **P8.1, P8.2, P8.3, P8.4, P8.7, P8.8, P8.9**.
- Blocked/open: **P8.5** (production relay assembly/registration) and **P8.6** (staging provisioning and Ops evidence).
- Changed artifacts: stage 8 workflow evidence retention; blocking stage 9 wrapper/job; stage 4/8 descriptions; worker startup prerequisite component and integration tests; worker-pool fixture ownership fix; release, envelope, provisioning and regression records; task markers.
- Expected negative runs: incompatible schema and credential-shaped payload both exited 1, followed by a passing restored-contract run. No invalid fixtures remain.
- Encountered test failures: fixed the worker fixture's role ownership/order dependency and the new membership test's grantor selection. The final gates pass without failed/skipped tests.
- Not performed: hosted Actions run, branch-protection changes, staging deployment/provisioning, live rollback or partition detach.

The feature is not production-ready while the relay and staging prerequisites remain open; earlier P7.20/P7.22 blockers are unchanged. The startup check protects migration ordering but cannot replace missing publication wiring or live observability.

Next planned chunk: P9.1–P9.10. Resolve P8.5/P8.6 before claiming deployed relay behavior; retain staging retention and backlog-alert evidence before closing the related release gates.
