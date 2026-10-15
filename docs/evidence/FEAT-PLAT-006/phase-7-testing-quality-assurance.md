# FEAT-PLAT-006 Phase 7 Testing and Quality Assurance

Date: 2026-10-15

Requested ranges: `P7.2-P7.18` and `P7.20-P7.24`.

## Completed Verification

| Task | Retained proof |
| --- | --- |
| `P7.2-P7.4` | `SweepRunnerContentionIntegrationTest` exercises repeated acquisition, contention, backend termination, batch-boundary lock loss, release and non-blocking losers against both session and transaction advisory-lock adapters. |
| `P7.5-P7.6` | `SweepSchedulingIntegrationTest` measures serialized starts for work longer than its interval and proves the relay lane ticks while the ordinary lane remains occupied. |
| `P7.7-P7.9` | `SweepRegistryLoaderTest` boots fail-closed contexts for a named missing row, a collision naming both sweeps, a 99 ms interval and a 301 s tick/shutdown bound. Values are rejected, not clamped. |
| `P7.10` | Three explicit role-context tests match API, worker and pindist entry-point beans exactly to their committed manifests; the extra-bean negative fixture fails before construction. |
| `P7.11-P7.13` | API context inventory contains no interval source or broker consumer; worker live/ready probes answer on management port 9091 while production configuration disables its business listener and its route inventory is empty; pindist permits only the reserved template and returns 404 for every tested alternate ingress form. |
| `P7.14-P7.15` | Real PostgreSQL shutdown drills commit or roll back at the batch boundary without loss or double application, release the session advisory lock, and let another replica acquire on its next 100 ms tick. |
| `P7.16` | The final-suite measurement for the in-process drain, commit and lock-release sequence was 11 ms. With the deployed 30,000 ms preStop and 60,000 ms termination grace, measured remaining margin was 29,988 ms. The test enforces a sequence below 5,000 ms and margin of at least 25,000 ms. |
| `P7.20-P7.23` | R9 bypass, all declared system actors, the simultaneous-tick connection budget and the five card outcomes are covered by executable tests. The outcome map is retained in `P7.23-acceptance-outcome-test-map.md`. |
| `P7.24` | CI stages 5, 6 and 7 pass. Stage 5 ran 644 unit tests at 87.27% line coverage; Stage 6 detected 253/314 mutations for 81% overall and enforced ≥80% on each of five runtime state machines; Stage 7 ran 13 slice tests and passed the slice inventory gate. Evidence is retained in `P7.24-suite-and-mutation-gate.md`. |

Focused verification commands passed before the final suite:

```text
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.SweepTelemetryTest'
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.SweepRegistryLoaderTest.*'
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.RuntimeManifestVerifierTest.*'
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.ApiWorkloadExclusionValidatorTest.*'
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.WorkerHttpSurfaceValidatorTest'
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.PindistRouteValidatorTest'
./gradlew integrationTest --tests 'org.meldtech.platform.platform.infra.runtime.SweepRunnerContentionIntegrationTest'
./gradlew integrationTest --tests 'org.meldtech.platform.platform.infra.runtime.SweepSchedulingIntegrationTest.*'
./gradlew integrationTest --tests 'org.meldtech.platform.platform.infra.runtime.ReferenceSweepIntegrationTest.*'
./gradlew spotlessCheck ciStage5 ciStage6 ciStage7 --rerun-tasks --no-daemon --console=plain
```

Final retained reports are green:

```text
spotlessCheck + all affected source-set compilation: PASS
test: 644 tests, 0 failures, 0 errors, 0 skipped
integrationTest: 634 tests, 0 failures, 0 errors, 0 skipped
conformanceTest: 106 tests, 0 failures, 0 errors, 0 skipped
```

## P7.17 Blocker - Deployed Affinity Values

Status: blocked and skipped; task remains open.

The signed request
`ci/provisioning/FEAT-PLAT-006/P0.8-platform-ops-provisioning-request.json`
still states `fulfillmentStatus: NOT_PROVISIONED` and explicitly gates `P7.17`.
The accessible Kubernetes context on 2026-10-15 was `docker-desktop`. Its only
application namespace was `cbt-staging-rollback`, containing one legacy
`cbt-platform` deployment and PostgreSQL. It contained no runtime Ingress and
no API, worker or pindist Service. Therefore no assertion can read actually
deployed affinity values for all three roles; checking `deploy/runtime/values.yaml`
would be template evidence and would not satisfy the task.

Resume only after Platform Ops fulfills P0.8 and deploys all three workloads,
Services and ingress in production-shaped synthetic staging. Read the live
Ingress annotations/spec and each live Service's `sessionAffinity` value, then
retain the CI 16 manifest assertion.

## P7.18 Blocker - Surrogate Replica Failover

Status: blocked and skipped; task remains open.

The accessible cluster has no deployment of the `FEAT-PLAT-001`
conformance-only reference slice, no Service routing to that slice, and no
multi-replica runtime workload. The only application deployment has one replica.
A replica therefore cannot be killed mid-request while a subsequent request is
routed to another replica, so recording a passing drill would be fabricated.

Resume after the conformance-only reference slice is deployed with at least two
replicas behind a Service in the provisioned synthetic staging environment.
Retain request/replica identity, deletion time and successful follow-up response.
That surrogate remains explicitly **not** `ARC-VERIFY-007`. The full mid-exam
scenario depends on `FEAT-DLV-001` and must be rerun as the scheduled Phase 3/6
obligation in staging, as carried forward by `P8.4`.

## P7.24 - Suite and Mutation Gate

Status: complete.

CI stages 5, 6 and 7 passed together with forced task execution. Stage 6 uses
the architecture's approved 80% state-machine threshold and verifies each
runtime state machine independently. Exact suite totals, mutation scores and
the retained command are recorded in `P7.24-suite-and-mutation-gate.md`.
