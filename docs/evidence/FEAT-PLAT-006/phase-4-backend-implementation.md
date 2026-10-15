# FEAT-PLAT-006 Phase 4 Execution

## P4.26 Completed on 2026-10-14

Requested and executed range: P4.26 only. P4.25's existing containment closure
was present before execution; earlier changes and completion markers were preserved.

Added the [canonical test-only reference sweep](../../reference-sweep.md), its
secured-pool test wiring, and four PostgreSQL integration tests. The body imports
only platform API/shared contracts and R2DBC/Reactor APIs, declares bounded metadata
and the worker role, and persists one row per transaction without retaining a cursor.
The registry row, RLS-protected work table and explicit worker manifest overlay
are test fixtures, not production migrations or deployed configuration.

Verified real registry loading and missing-row rejection, the actual worker login
and installed module role/platform scope, missing-context rejection, immediate
lock contention, graceful commit with no next batch, final-phase lock release,
timeout rollback and a fresh runner completing the durable pending work. Timeout
metrics and return of acquired connections are asserted. The production class
output and all committed role manifests exclude the reference. Inspection of the
built Boot JAR independently confirmed that the production runner is present and
the reference sweep and its wiring are absent.

Initial compilation diagnostics, a missing worker profile in the manifest-test
context, and one SQL line-length violation were corrected without changing any
production behavior or weakening checks.

Final verification passed:

```sh
./gradlew compileJava compileTestJava test integrationTest ciStage4 \
  checkstyleMain checkstyleTest checkstyleIntegrationTest checkstyleConformanceTest \
  spotlessCheck bootJar --console=plain
```

All 618 integration tests ran successfully, including the four new tests. Gradle
confirmed the unchanged root, migration-verifier and conformance suites up-to-date;
their reports contain 634, 74 and 106 passing tests respectively. Across these
reports: 1,432 tests, zero failures, errors or skips. Stage-4 event/schema gates,
compilation, Checkstyle, formatting and packaging passed. `git diff --check` passed.

P4.26 is marked complete. No requested task remains or is blocked. P10.1 remains
separate and should link to this exemplar; no future task was executed. The next
phase begins at P6.1 and requires Platform Ops workload-identity provisioning.

## P4.21-P4.26 Execution on 2026-10-14

### P4.21 Completed

Added explicit JVM-cap/cgroup validation, bounded one-second operational memory
sampling, monotonic continuous pressure windows, and a latched drain/liveness
failure with a bounded restart-reason counter and log. See
`docs/runtime-memory-control.md` for deployment inputs and restart behavior.
Controlled-time tests cover threshold persistence, reset, and latching;
monitor tests cover availability events, one-shot metrics, and cgroup v1/v2.
Actual Kubernetes restart timing remains P7.16, not a unit-test claim.

Also repaired the three previously failing context tests: supplied explicit
test-only external observability inputs, made the drain event listener
proxyable, and recognized Boot's exact empty additional-health mapping without
allowing nonempty/unknown mappings. The production security policy is unchanged.
An initial conformance failure was corrected by using the existing Micrometer
monotonic clock instead of ambient time in the monitor.

Passed: compilation, 12 focused tests (including both formerly failing full
contexts), 58 conformance tests, and main/test Checkstyle. Command:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*MemoryPressurePolicyTest' --tests '*RuntimeMemoryMonitorTest' \
  --tests '*RuntimeAdmissionConfigurationTest' --tests '*StudentAssessmentApplicationTests' \
  --tests '*TenantIsolationMatrixGateTest' checkstyleMain checkstyleTest conformanceTest --console=plain
```

### P4.22 Completed

Bootstrap installs validated uppercase log role identity after profile expansion
and before logging initialization. Framework JSON and local console logs do not
depend on request MDC. The application encoder uses the resolved process role.
Micrometer applies `runtime_role` without replacing database `role` labels;
the existing three SDK resources retain `service.runtime.role`. A mismatch
between the active role and configured telemetry role fails startup.
`docs/logging-contract.md` documents these representations.

Passed compilation, 28 focused role/encoder/resource tests, and main/test
Checkstyle. Tests exercise all three roles, actual Boot JSON encoding of a
background event, incorrect caller labels, and role/configuration disagreement.
The full-context smoke test also passed with the initial P4.22 wiring.

### P4.23 Completed

The runner records `sweep.tick.outcome`, `sweep.tick.duration`,
`sweep.last.success.timestamp`, and `sweep.batch.size`. Outcomes distinguish
success, contention, lost ownership, timeout, failure, and interruption. Duration
ends after lock cleanup; successful timestamps use the injected kernel clock.
Adapters report typed ownership loss, including inability to verify a retained
connection. Labels contain only bounded sweep/outcome values plus the common
runtime role; the catalogue is capped at 128 names and contains no tenant ID.

Passed focused runner/telemetry/adapter tests, compilation, 58 conformance tests,
and main/test Checkstyle. A runner regression exercises contention, lost
ownership, and success through actual tick execution; controlled-clock tests
cover all six outcomes, duration, batch counts, and last-success semantics.

### P4.24 Completed

Added an ArchUnit condition in the existing blocking `conformanceTest`/`ciStage4`
path, reusing the production candidate validator. It checks inactive profiles,
named slice Endpoints, actual route/consumer/sweep types and bean factories;
requires one direct closed role declaration; rejects meta-declaration ambiguity,
domain declarations and invalid role/category combinations. PIN routes require
the reserved mapping or committed functional-route inventory, with actual router
verification still performed at boot. Added the missing API declaration to the
unregistered audit Endpoint without making it a component or enabling its route.

Positive fixtures cover all roles, shared support, worker sweeps and unique
factories. Eighteen negative fixtures cover missing/bare-profile declarations,
duplicates, empty/invalid values, meta-annotations, wrong categories, factories,
domain declarations and unregistered functional PIN routes. A dedicated Endpoint
factory fixture proves that declarations on both the type and factory fail.

Passed 631 root tests and stage 4 after fixing a telemetry-bootstrap regression
in the non-serving migration launcher. A final targeted rerun after tightening
functional PIN inventory checks passed 11 manifest/catalogue tests, all 77
conformance tests, both stage-4 schema/completeness gates, compilation and
main/conformance Checkstyle. The existing migration-role assertion is preserved.

Final verification after all P4.24 edits passed with no skipped tests:
631 root tests, 78 conformance tests, and 612 integration tests. Compilation,
stage-4 business-event/schema gates, and main/test/conformance/integration
Checkstyle also passed. Commands:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test ciStage4 \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
./gradlew integrationTest checkstyleIntegrationTest --console=plain
```

The separate migration-verifier suite also passed all 74 tests via
`./gradlew :migration-verify:test spotlessJavaCheck --console=plain`.
Total verified: 1,395 tests, zero failures/errors/skips. `git diff --check` passed.

### P4.25 Initial Blocker; Resolved 2026-10-14

P2.15 permits advisory SQL only in the two sweep adapters and explicitly includes
SQL resources. Inspection found existing non-scheduler advisory locks in:

- `db/migration/audit/V4__create_audit_event_partitions.sql:50`,
  `audit.provision_audit_month`.
- `db/migration/audit/V6__create_audit_chain_head.sql:125`,
  `audit.provision_audit_epoch_heads`.
- `db/migration/outbox/V8__create_outbox_partition_maintenance.sql:25,103`,
  `outbox.attach_week_partition` and `outbox.detach_expired_partition`.
- `db/migration/outbox/V9__secure_outbox_partitions.sql:59`, the secured replacement
  of `outbox.attach_week_partition`.

These locks protect database provisioning/partition operations. Removing them
or rewriting applied migrations would violate existing correctness and migration
constraints. Silently excluding SQL resources or adding a permanent exception
would weaken the specified gate. A scope decision was requested: explicitly
permit only these existing migration definitions, or amend their design before
continuing. No approval has been recorded. The known relay-local acquisition
still also needs migration together with gate activation, as P2.15 requires.

At that stop, no P4.25 gate, exemption, relay adoption or P4.26 implementation
had been introduced. The closure below supersedes the P4.25 blocked status.

### P4.25 Containment and Relay Adoption Closure

Date: 2026-10-14. The rule now runs in the blocking stage-4 conformance suite.
It pins the central periodic-source methods and the two lock adapters, rejects
alternate scheduling and unresolved runtime SQL, and scans SQL resources.
The four existing database-integrity migrations are preserved by exact
path/SHA-256 baselines; no applied migration was rewritten. The P2.15 record
documents this explicit historical boundary and reviewed SQL-forwarding code.

Relay-local lock SQL and key derivation were replaced with `SweepLock` in the
same change. Negative fixtures, real relay contention and lock-loss/rollback,
and four runners over four ticks with both PostgreSQL lock modes passed.
Runtime P4.25, P7.1 and P7.19, and outbox P7.17 are complete. P4.26 was open at
this closure and is completed in the subsequent record above.

See [implementation and verification evidence](P4.25-containment-closure.md).

## P4.14-P4.16 Execution on 2026-10-14

### P4.14 Completed

Added a tick-scoped `SweepBatchCleanup` acknowledgement. Managed transactions
register acquired connections and acknowledge only after their asynchronous
commit/rollback and close sequence ends. The runner awaits that acknowledgement
on timeout or normal completion before releasing its handle and disposing the
tick scheduler. This fixes cancellation reporting drain completion too early.
The timeout regression now models the same acknowledgement as the real adapter;
an adapter regression separately delays rollback and close to verify both are
awaited. Existing normal commit/rollback and discarded-pending-tick tests remain.

Verification passed after correcting a compiler nullability diagnostic:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*SecurityContextInitializerTest' --tests '*ApiWorkloadExclusionValidatorTest' \
  conformanceTest checkstyleMain checkstyleTest --console=plain
```

40 focused tests and 58 conformance tests passed. P4.14's marker was persisted
before starting P4.15. Full-context and real PostgreSQL verification remain
separate from this focused evidence. P4.16 owns final-phase lock retention.

### P4.15 Completed

Worker-only broker drain configuration uses Spring AMQP's existing lifecycle
containers and endpoint registry at Boot's graceful-web phase minus one.
Containers finish the active delivery, requeue buffered deliveries, and close
unresponsive channels after five seconds. Acknowledgement-before-processing
mode is rejected. Custom factories with an unconfigured phase fail startup;
directly registered container beans receive the same policy. No business
consumer was added and no broker transport architecture rule was weakened.

Verification passed after fixing a test import, moving broker wiring into its
owning outbox package, and shortening a Checkstyle diagnostic:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*BrokerDrainConfigurationTest' --tests '*RuntimeManifestVerifierTest' \
  integrationTest --tests '*BrokerDrainIntegrationTest' conformanceTest \
  checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

The real RabbitMQ test verifies that stop waits for active processing, successful
work is acknowledged, and failed or prefetched work remains recoverable. The
five policy tests and conformance suite passed. P4.15's marker was persisted
before starting P4.16. Feature consumers must complete their transaction before
returning or completing their asynchronous listener result.

### P4.16 Completed

Added `SweepLockShutdown`, a lifecycle participant at Boot's graceful-web phase
minus two. The runner now acknowledges completed transaction bodies separately
from completed ticks. Clean shutdown defers handle release behind the final
phase barrier; failed ticks retain prompt cleanup. The dedicated, already
secured/traced pool is disposed only after every tracked tick releases its
handle. Final-phase timeout failures are logged rather than reported as clean
drains. Repeated stop callbacks share one pool disposal operation.

Lifecycle tests exercise actual Spring phase ordering with controlled web and
broker participants. Additional cases cover delayed release, idempotent disposal,
failed-handle cleanup, and the runner's transaction-boundary behavior. The real
PostgreSQL adapter tests verify contention, release/reacquisition and transaction
security. No new pool or weaker security decorator was introduced.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*SweepLockShutdownTest' \
  --tests '*WorkloadConnectionPoolConfigurationTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*SessionSweepLockTest' --tests '*TransactionSweepLockTest' \
  integrationTest --tests '*SessionSweepLockIntegrationTest' conformanceTest \
  spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

The expanded requested range is now P4.16-P4.26. P4.16's marker was persisted
before starting P4.17. Full process/management-context termination budget
measurement remains P7.16, not a claim made by these focused lifecycle tests.

### P4.17 Completed

Configured graceful web shutdown, five-second lifecycle phases, and zero-quiet /
one-second Reactor resource disposal. `runtimeDrain` is an explicit readiness
group member. It checks the pod-local marker off the event loop, latches drain,
and publishes refusing-traffic readiness; SIGTERM/context close does the same
before lifecycle stop. New business admission is rejected with the shared,
sanitized `CBT-PLAT-UNAVAILABLE` 503 contract and a one-second Retry-After.
Already admitted work and health probes continue. An unreadable marker fails
closed. `cbt.runtime.drain-marker` defaults to `/run/cbt/draining`, matching the
P3.9 deployment hook; overrides must be absolute paths.

The old exact catalogue-set test was updated for the new declared error code.
Verification passed:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RuntimeDrainStateTest' --tests '*GracefulWebShutdownTest' \
  --tests '*ProblemDetail*Test' --tests '*WorkerHttpSurfaceValidatorTest' \
  --tests '*ErrorCatalogueGeneratorTest' conformanceTest checkstyleMain checkstyleTest --console=plain
```

Tests cover readiness latching, marker removal, shutdown admission, sanitized
503 rendering, and a real Netty socket response completing while graceful stop
waits. Total pod/parent/management shutdown timing remains P7.16 evidence.
P4.17's marker was persisted before beginning P4.18.

### P4.18 Completed

Added lifecycle-owned API bulkheads: exam (8 threads, 32 queued tasks per
backing thread) and admin/report (2 threads, 16 queued tasks per backing thread),
with 60-second idle TTL. Infrastructure lanes pair each scheduler with the
existing secured/traced `examPathConnectionFactory` or `apiConnectionFactory`.
No pool is recreated, merged or used as a fallback. Deferred execution preserves
Reactor Context. P4.19 owns binding matched HTTP routes to these lanes.

Verification passed after simplifying a test-local counter and context probe:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RuntimeBulkheadConfigurationTest' --tests '*WorkloadConnectionPoolConfigurationTest' \
  --tests '*ApiWorkloadExclusionValidatorTest' conformanceTest checkstyleMain checkstyleTest --console=plain
```

Tests exhaust admin threads and its bounded queue while exam work completes,
verify exact existing pool identity, context propagation and scheduler disposal.
P4.18's marker was persisted before beginning P4.19.

### P4.19 Completed

Added the committed `RuntimeRoutePolicies` table and startup coverage validation.
Matched WebFlux method/template metadata selects policy before handler invocation;
client headers and raw path prefixes cannot select a workload. Handler adapters
dispatch onto the selected API lane. Per-request Reactor Context carries policy
through response rendering, and permits cover streaming until termination.
Route/class caps use P2.10's initial values, including shared admin/report capacity
and pindist's independent cap. Unclassified routes fail boot. Static resource
mappings are disabled for these API runtimes. Capacity rejection uses the shared
503 contract plus bounded Retry-After, with no queued retry.

Verification passed after correcting nested-type and fixture nullability:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RouteAdmissionTest' --tests '*RuntimeAdmissionConfigurationTest' \
  --tests '*RuntimeBulkheadConfigurationTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*WorkerHttpSurfaceValidatorTest' conformanceTest checkstyleMain checkstyleTest --console=plain
```

Tests cover real WebFlux matching/rendering, unknown-route startup rejection,
route/class/shared saturation, exam isolation, streaming permits, cancellation,
idempotent release and selection-after-close. P4.19's marker was persisted
before starting P4.20, which adds transaction and request deadline enforcement.

### P4.20 Completed

Secured transactions install bound, transaction-local `statement_timeout` after
role, scope and controlled search path: exam 500 ms, administrative/pindist 1 s,
report 2 s. Matched policy travels in Reactor Context; neither setting names nor
timeout values come from the caller. Whole-response deadlines are 2/3/4 seconds.
The shared acknowledgement is now named `TransactionCleanup` because request
and sweep transactions both need it. HTTP timeout/cancellation retains permits
until transaction cleanup completes, using asynchronous resource cleanup rather
than detached subscriptions. Health requests do not acquire business policy.

Verification passed:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RouteAdmissionTest' --tests '*RuntimeAdmissionConfigurationTest' \
  --tests '*SweepRunnerTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*SecurityContextInitializerTest' integrationTest \
  --tests '*SessionSweepLockIntegrationTest' conformanceTest \
  checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

The PostgreSQL test checks all three settings, SQLSTATE 57014 for a slow exam
query, restoration to the test container's configured 30-second baseline, and
zero acquired worker connections. The initial baseline assertion incorrectly
expected zero; it was corrected against `PostgreSqlTestContainer` configuration.
Virtual-time request tests prove timeout cancellation waits for cleanup and
returns all permits. P4.20's marker was persisted before starting P4.21.

## P4.14-P4.26 Review on 2026-10-14

P4.13's verified prerequisite and the existing P4.14 draft were preserved.
Task-level review found that the normal completion tests did not exercise
asynchronous transaction rollback after the runner's batch timeout.

Added `SweepRunnerTest.shutdownWaitsForRollbackWhenTheBatchTimesOut`: a batch
never completes, its cancellation rollback takes 500 ms, and shutdown must
retain the lock and scheduler until that boundary finishes. The regression
failed at the four-second batch deadline:

```text
Expecting empty but was: ["release", "stopped"]
```

Reactor cancellation starts asynchronous `usingWhen` cleanup but does not await
it before the outer timeout reports failure. The runner consequently releases
the sweep lock and invokes its lifecycle callback before rollback finishes.
Its scheduler disposal also prevents the test's scheduled rollback completing.
The previous normal commit/rollback evidence does not establish P4.14 acceptance
for timed-out batches.

Verification:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*ApiWorkloadExclusionValidatorTest' --console=plain
```

Production and test compilation passed. Of 21 focused tests, 20 passed and the
new regression failed. No full-suite, PostgreSQL integration, conformance or
style verification was run in this execution. No production implementation was
changed in this execution.

Execution stopped under the invoked skill's validation-failure rule. P4.14
remains unchecked and P4.15-P4.26 were not executed. Next correction: establish
an observable transaction-cleanup boundary for cancellation and await it before
releasing the lock or completing the shutdown callback; retain scheduler
resources until cleanup finishes. Preserve bounded shutdown and the later
P4.16 lock-release phase contract. Resume P4.14-P4.26 with this regression.

## P4.13-P4.26 Execution on 2026-10-14

P4.13 is complete. Added a method-local `UnusedMethod` suppression to the
reflectively inspected listener fixture; production checks and global compiler
enforcement are unchanged. The API gate rejects interval sources, annotated
consumers and listener containers before construction and is absent on worker.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*ApiWorkloadExclusionValidatorTest' \
  --tests '*RuntimeManifestVerifierTest' --tests '*SweepRunnerTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest \
  checkstyleConformanceTest --console=plain
```

The task marker was persisted before starting P4.14. Existing user changes,
completed markers and golden inventories are preserved.

### P4.14 Draft and Validation Stop

Separated interval-source disposal from the sweep lane's work subscription.
The draft runner stops admission, clears pending signals, completes the signal
stream and waits for active work and asynchronous cleanup before invoking its
shutdown callbacks. Its phase is Boot's `GRACEFUL_SHUTDOWN_PHASE + 1`.
Shutdown is idempotent and a stopped runner cannot restart a disposed scheduler.
Final-phase lock retention remains P4.16 work, not a delivered property here.

Added draft tests for delayed commit/rollback completion, discarded pending
ticks, stop during lock acquisition, callback behavior and phase ordering.
Adjusted the existing slow-tick test to allow the current batch to finish.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*ApiWorkloadExclusionValidatorTest' conformanceTest \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

Formatting and production compilation passed. Test compilation failed because
`stopSignal.get()` is nullable in the new shutdown test. Resolve the captured
signal with an explicit non-null assertion before dereferencing it; preserve
NullAway enforcement. No P4.14 tests or subsequent conformance/Checkstyle gates
ran, so transaction-boundary and shutdown timing acceptance remain unverified.
The full application and PostgreSQL integration suites were not run.

Execution stopped under the task skill's validation-failure rule. P4.13 remains
complete; P4.14 is unchecked and P4.15-P4.26 were not executed. Recommended next
chunk: P4.14-P4.26, beginning with this test correction and focused verification.

### Stop-Signal Nullability Correction

Captured `stopSignal.get()` through `Objects.requireNonNull` before asserting
the shutdown signal. The test still fails if no batch supplied the signal;
NullAway remains fully enforced, with no suppression or production-code change.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*DefaultSweepTransactionsTest' \
  --tests '*ApiWorkloadExclusionValidatorTest' conformanceTest spotlessJavaCheck \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

All 20 focused tests and 58 conformance tests passed, together with compilation,
formatting and Checkstyle. The shutdown tests now execute, covering delayed
commit/rollback, acquisition-time shutdown, pending-tick removal and callback
ordering. Full application and PostgreSQL integration suites were not run.
This closes the compile blocker without changing broader task completion markers.

## P4.12-P4.26 Resume on 2026-10-14

P4.12 is complete. The shared route inventory retains the established
pindist-specific failure contract, and the worker YAML test supplies an isolated
temporary directory for the required broker config-tree import without changing
production fail-closed behavior.

Verification passed:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*WorkerHttpSurfaceValidatorTest' \
  --tests '*PindistRouteValidatorTest' conformanceTest \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

All 11 focused worker/pindist tests passed with compilation, all conformance
tests, formatting and Checkstyle. The worker profile sets `server.port=-1`,
management port 9091, health-only exposure and no discovery; boot validation
rejects configuration drift, business routes and opaque mappings. Deployment
values declare the non-ingress management probes. Live child-context probe
responses remain later verification scope. P4.12 was marked complete before
starting P4.13.

### P4.13 Draft and Test-Compilation Failure

Added a package-private `RuntimeIntervalSource` marker implemented by
`SweepRunner`, and an API-only bean-factory post-processor that rejects interval
sources and broker consumers before their constructors run. Broker-consumer
detection is shared with the manifest verifier and covers listener containers
plus type- and method-level `@RabbitListener` declarations. Tests cover clean
API startup, pre-construction failures, and absence of the validator on worker.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*ApiWorkloadExclusionValidatorTest' \
  --tests '*RuntimeManifestVerifierTest' --tests '*SweepRunnerTest' \
  conformanceTest checkstyleMain checkstyleTest \
  checkstyleConformanceTest --console=plain
```

Formatting and production compilation passed. Test compilation failed under
the existing warnings-as-errors policy:

```text
[UnusedMethod] Method 'consume' is never used.
error: warnings found and -Werror specified
```

The method is intentionally discovered reflectively through `@RabbitListener`.
Resume with a narrowly scoped `@SuppressWarnings("UnusedMethod")` on that test
fixture method, retaining Error Prone globally, then rerun all focused and
skipped gates.

Execution stopped under the `execute-tasks` failure rule. P4.13 remains
unchecked; none of its unit tests, conformance or Checkstyle tasks ran.
P4.14-P4.26 were not executed. P4.12's completed marker and existing changes
were preserved; no commit was made. Recommended next chunk: P4.13-P4.26.

## P4.11-P4.26 Resume on 2026-10-14

P4.11 is complete. The exact Spring `EmptyHandlerMapping` classification was
present at entry and preserved; opaque custom mappings remain fail-closed.
The pindist validator enforces zero business routes before FEAT-EXAM-003 ships
and exactly the reserved GET route afterward. Its earliest-order guard returns
404 for forbidden paths, methods and ambiguous encodings without widening the
allowed route's security chain.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*PindistRouteValidatorTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*RuntimeCandidateCatalogueTest' conformanceTest spotlessJavaCheck \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
./gradlew :test --tests '*PindistRouteValidatorTest' --rerun-tasks --console=plain
```

The second command forced all five P4.11 tests to execute successfully instead
of relying on Gradle's prior result. The broader command passed compilation,
manifest regressions, conformance, formatting and Checkstyle. Management-port
isolation remains P4.12 scope; P4.11's marker was persisted before it began.

### P4.12 Draft and Focused-Test Failure

Added a shared fail-closed business-route inventory used by worker and pindist,
a worker-only startup validator, and exact management-surface configuration.
The worker profile disables the business listener with `server.port=-1`, keeps
management on 9091, exposes only health, disables discovery and all endpoints
by default, hides details/components, and declares the three named health
groups. Runtime deployment values carry the same ports and non-ingress
management probe paths. Tests cover empty routes, route/opaque-mapping rejection,
configuration drift and loading the worker profile from `application.yaml`.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*WorkerHttpSurfaceValidatorTest' \
  --tests '*PindistRouteValidatorTest' conformanceTest \
  checkstyleMain checkstyleTest --console=plain
```

Formatting and production/test compilation passed. Eleven focused tests ran:
eight passed and three failed. The two existing P4.11 negatives still fail boot,
but now report `business route inventory contains an opaque mapping` rather
than their asserted pindist-specific allowlist message. The application-YAML
test fails earlier because activating `worker` imports the deliberately required
broker config tree and the test supplies no directory.

Resume by retaining the shared enumeration while translating its opaque-mapping
failure to the established pindist allowlist diagnostic. For the YAML test,
inject a temporary directory and set `CBT_BROKER_SECRETS_PATH` to that existing
config-tree path; do not make the production import optional. Then rerun the
focused tests and all skipped gates.

Execution stopped under the `execute-tasks` failure rule. P4.12 remains
unchecked. The draft does not yet prove a live management child context or
status-only probe responses; later probe verification remains explicitly
unclaimed. P4.13-P4.26 were not executed. P4.11's completed marker and all
existing changes were preserved; no commit was made. Recommended next chunk:
P4.12-P4.26.

## P4.10-P4.26 Completion Review on 2026-10-13

P4.10 is complete. Preserved the method-parameter temporary-directory fix
already present at entry. Reverification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*RuntimeCandidateCatalogueTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*SweepRegistryLoaderTest' conformanceTest spotlessJavaCheck \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

All 23 focused tests executed successfully; compilation, conformance and style
gates were up to date. Coverage includes missing/extra entries before activation,
excluded candidate declarations, static factories, actual functional and annotated
WebFlux mappings, queue metadata, and ordinary JAR discovery. Sorted candidate
inventories are generated under `build/reports/runtime-roles` without rewriting
the committed expectations. The three golden statuses were deliberately promoted
to `VERIFIED_INVENTORY`; entry-point sets are unchanged.

P4.10's marker was persisted before starting P4.11. This verifies the scoped
entry-point inventories, not full application startup, Boot nested-JAR startup,
live broker consumption, or later role/management/lifecycle restrictions. Opaque
route and manual listener-container forms fail closed pending explicit support.
The prior external-observability full-context gap remains open.

### P4.11 Draft and Empty-Mapping Test Failure

Added a pindist-only startup validator over actual business-context handler
mappings and an early request guard. The draft requires no business routes
before FEAT-EXAM-003 ships and exactly the reserved GET route afterward. The
committed feature-delivery YAML is packaged and parsed with duplicate/unknown
field rejection. The guard returns 404 for forbidden methods, aliases, probe
paths and ambiguous encoding, while passing the allowed route to the existing
security chain. Static resource auto-mappings are disabled for pindist only.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*PindistRouteValidatorTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*RuntimeCandidateCatalogueTest' conformanceTest checkstyleMain \
  checkstyleTest --console=plain
```

Formatting and compilation passed. Of 16 focused tests, 15 passed and one
failed: `inspectsActualWebFluxMappingsAndRejectsUnknownMappingTypes` rejects its
initial empty WebFlux context. The stack reaches the unknown-mapping branch in
`PindistRouteValidator`. The installed Spring WebFlux source shows
`WebFluxConfigurationSupport.resourceHandlerMapping` returns its private final
`EmptyHandlerMapping` when there are no resources; its implementation always
returns `Mono.empty()`.

The next correction is a narrowly scoped classification of that exact framework
no-op mapping, not a blanket allowance for unknown or empty-looking custom
mappings. Preserve the negative opaque-handler test. This run stopped under the
requested failure rule before making that correction. Conformance and Checkstyle
were not reached for P4.11. The promoted P4.10 inventories passed their tests
again in this run.

P4.11 remains unchecked. Management-context/listener isolation and full security
ordering still need task-level verification; request-chain unit tests do not
establish those integration properties. P4.12-P4.26 were not executed. Existing
changes and P4.10's completed marker are preserved; no commit was made.
Recommended next chunk: P4.11-P4.26.

## P4.10-P4.26 Packaged Discovery Follow-Up on 2026-10-13

The self-anchored catalogue locator correction was present at entry and
preserved. Reverification passed for the existing 22 focused manifest/loader
tests, compilation, conformance, formatting and Checkstyle. The focused tests
executed; other gates were up to date. Existing R7 and compatibility corrections
were preserved without modification.

Added a draft test that packages the compiled production classes in a temporary
JAR, uses an isolated artifact-first loader, and invokes catalogue validation
from that JAR. Added fail-closed rejection of an empty catalogue and deterministic
map-key ordering for candidate JSON output. Golden manifests remain unchanged.

Verification attempted after these edits:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RuntimeCandidateCatalogueTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*SweepRegistryLoaderTest' conformanceTest checkstyleMain \
  checkstyleTest checkstyleConformanceTest --console=plain
```

Formatting and production compilation passed. Test compilation failed:

```text
RuntimeCandidateCatalogueTest.java: [NullAway] @NonNull field temporary not initialized
    @TempDir Path temporary;
```

The correction is to inject the temporary path through the test method's
`@TempDir Path temporary` parameter, removing the uninitialized field. Preserve
NullAway enforcement. Execution stopped under the requested skill's failure
rule; the new packaging test and subsequent conformance/Checkstyle gates did not
run. Earlier passing results do not verify the later edits.

P4.10 remains unchecked pending this correction, packaged discovery verification
and final manifest completion review. P4.11-P4.26 were not executed. No golden
inventory promotion, full application startup or Boot nested-JAR startup is
claimed. Existing changes and completed markers were preserved; no commit was
made. Recommended next chunk: P4.10-P4.26.

### Temporary Directory Injection Correction

Moved `@TempDir Path temporary` from an uninitialized field to the packaging
test's method parameter, matching existing repository tests. NullAway remains
fully enforced; no suppression or nullable state was introduced.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*RuntimeCandidateCatalogueTest' --tests '*RuntimeManifestVerifierTest' \
  --tests '*SweepRegistryLoaderTest' conformanceTest spotlessJavaCheck \
  checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

All 23 focused tests, including ordinary packaged-JAR catalogue discovery,
and all 58 conformance tests passed, as did compilation, formatting and
Checkstyle. Full application and Boot nested-JAR startup were not tested.
Task completion markers and golden inventories remain unchanged.

## P4.10-P4.26 Resume on 2026-10-13

Preserved the entry-state API compatibility fixes, the narrowly scoped R7
listener-metadata correction and its regression tests. No conformance rule was
changed in this run.

Extended the P4.10 draft with a production-only candidate catalogue intended
to inspect profile-excluded components and factory methods without constructing
them. Added static-factory ownership resolution, shared declaration validation,
registered annotated-route descriptors and single literal consumer-queue
descriptors. Opaque/manual listener containers remain rejected rather than
silently ignored. The verifier exposes only a successfully verified, sorted
inventory for test-generated candidate JSON.

New tests cover excluded candidates, static factories, annotated mappings and
consumer queue drift. The three role fixtures now include WebFlux configuration
and write candidate inventories under `build/reports/runtime-roles` only after
successful context validation. No golden manifest was rewritten or promoted.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RuntimeManifestVerifierTest' --tests '*SweepRegistryLoaderTest' \
  conformanceTest checkstyleMain checkstyleTest checkstyleConformanceTest --console=plain
```

Formatting, production compilation and test compilation passed. Of 22 focused
tests, 16 passed and six context tests failed with:

```text
RUNTIME_MANIFEST_INVALID: candidate catalogue unavailable
```

The new `RuntimeCandidateCatalogue` locates the production artifact using
`org/meldtech/platform/StudentAssessmentApplication.class`, a stale name from
the repository guide. The real source and compiled bootstrap are
`org/meldtech/platform/CbtPlatformApplication`. The locator must be anchored to
an existing production class (preferably the catalogue's own class resource,
avoiding dependence on application naming), then tested for both exploded and
packaged artifacts. Do not turn catalogue unavailability into a bypass.

Execution stopped under the requested skill's failure rule. Conformance and
Checkstyle were not reached for these changes. The passing isolated tests do
not establish the new startup paths: catalogue scanning, static-factory and
annotated/consumer context behavior, generated inventories and full-context
observability remain unverified. P4.10 remains unchecked; P4.11-P4.26 were not
executed. All prior completed markers and existing user changes are preserved.
Recommended next chunk: P4.10-P4.26, starting with the locator correction and
focused reverification. No commit was made.

### Candidate Catalogue Locator Correction

`RuntimeCandidateCatalogue` now derives its artifact anchor from its own class
name and defining class loader instead of the obsolete application bootstrap
name. Production-artifact filtering and fail-closed discovery are unchanged.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests '*RuntimeManifestVerifierTest' --tests '*SweepRegistryLoaderTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest \
  checkstyleConformanceTest --console=plain
```

All 22 focused tests (including the six previously failing contexts), 58
conformance tests, compilation, formatting and Checkstyle passed. This verifies
exploded-classpath discovery; packaged-JAR startup and the full application
suite were not run. P4.10's broader completion markers remain unchanged.

## P4.9-P4.26 Resume on 2026-10-13

P4.9 is complete. The serialization correction was already committed at entry;
the worktree was clean. Collision validation includes disabled rows, preserves
both validated names in the startup diagnostic, and never rewrites stored keys.
Startup tests cover signed key boundaries and prevent listener activation.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test --tests '*SweepRegistryLoaderTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```

Focused tests and conformance executed; compilation, formatting and Checkstyle
were up to date. P4.9 was marked complete before P4.10 began. Full-context
observability remains outside this verification.

### P4.10 Draft and Compilation Failure

Added a classpath-packaged copy of the committed role manifests through
`processResources`, a strict JSON reader, and an entry-point verifier. The draft
compares expected and candidate identities before construction, then compares
registered route/sweep metadata before lifecycle start. It rejects untracked
singleton entry points, duplicate identities, opaque routing and route predicates
that disagree with their declared descriptor. The reference endpoint and its
policy now declare the API role together to avoid orphan policy registration.
No golden manifest was rewritten or promoted to `VERIFIED_INVENTORY`.

Added focused tests for three isolated role contexts with the real reference
endpoint and mocked collaborators, missing and extra entries before activation,
registered route drift, and malformed JSON. These tests have not run.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*RuntimeManifestVerifierTest' --tests '*SweepRegistryLoaderTest' \
  conformanceTest checkstyleMain checkstyleTest --console=plain
```

Formatting completed. Production compilation failed with two removal warnings
promoted to errors by the existing `-Werror` policy:

- `RuntimeManifestVerifier` references the deprecated Spring AMQP
  `MessageListenerContainer` interface.
- `RegisteredRouteInspector.PredicateInspector` implements the deprecated
  `RequestPredicates.Visitor.pathExtension(String)` method.

Execution stopped under the requested skill's failure rule. Resolve the AMQP
type against the installed framework's supported replacement; inspect the
visitor contract and handle its required legacy override locally without
weakening global warnings-as-errors. Then compile and run the focused gates.

P4.10 is not complete beyond the compilation blocker. Its current implementation
checks active bean definitions, not the full profile-excluded production
candidate catalogue. Annotated HTTP handlers and broker consumers are detected
but rejected as unsupported rather than described in the manifest. Complete
those catalogue/descriptor paths, factory metadata coverage and deterministic
candidate output before claiming the P2.2 contract. The current contexts are
focused fixtures, not full application/WebFlux registration tests. Full-context
observability remains an existing open verification gap.

P4.9's completed marker is preserved. P4.10 remains unchecked and its tests,
conformance and Checkstyle were not reached. P4.11-P4.26 were not executed.
Recommended next chunk: P4.10-P4.26. Changes are persisted; no commit was made.

### R7 Listener Inspection Correction

R7 classified the manifest verifier's listener type inspection as publishing
because it rejected every dependency in the Spring AMQP packages. The rule now
allows exactly `MessageListenerContainer` and `RabbitListener`, neither of which
exposes a publishing API. No class or package exemption was added; other broker
dependencies and the outbox/atomic-flow checks remain guarded.

Regression fixtures accept listener metadata inspection and reject both
`AmqpTemplate` and `RabbitTemplate` publishing dependencies in listener inspectors.

Verification:

```sh
./gradlew compileJava compileTestJava :test --tests '*RuntimeManifestVerifierTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest \
  checkstyleConformanceTest --console=plain
```

All seven focused unit tests and 58 conformance tests passed, together with
compilation, formatting and Checkstyle. The full application and PostgreSQL
integration suites were not rerun. This closes the reported R7 false positive;
P4.10's remaining scope and task completion markers are unchanged.

## P4.7-P4.26 Follow-Up on 2026-10-13

P4.7 remains complete. P4.8's typed Mockito matcher correction was already
present at entry and was preserved. P4.8 is now complete: declaration bounds,
every-row validation, missing-row rejection before lifecycle activation,
worker-factory selection, and failed-reload snapshot invalidation are covered.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test --tests '*SweepRegistryLoaderTest' \
  integrationTest --tests '*SessionSweepLockIntegrationTest' conformanceTest \
  spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```

The focused unit and PostgreSQL integration tasks executed successfully;
compilation, conformance, formatting and Checkstyle were up to date. P4.8's
marker was persisted before starting P4.9. Full-context observability remains
outside this verification.

### P4.9 Draft and Compilation Failure

Added an independent lock-key uniqueness check across all loaded rows before
matching enabled definitions. The draft rejects a collision with both validated
sweep names, without deriving or rewriting stored keys. A private validation
exception distinguishes safe validator diagnostics from driver failures so
bootstrap can retain the collision names without exposing arbitrary database
error details.

Added startup tests for collisions involving disabled rows and signed bigint
boundary keys, asserting lifecycle listeners never activate, plus a distinct-key
case. Updated the pre-existing successful two-row fixture to use distinct keys.
These tests have not run.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*SweepRegistryLoaderTest' conformanceTest \
  checkstyleMain checkstyleTest --console=plain
```

Formatting completed, but production compilation failed:

```text
[serial] serializable class RegistryValidationException has no definition of serialVersionUID
error: warnings found and -Werror specified
```

Execution stopped under the requested skill's failure rule. Add an explicit
serialization version to the private exception, retaining warnings-as-errors,
then rerun the focused gates. P4.9 remains unchecked; its test compilation,
tests, conformance and Checkstyle were not reached. P4.10-P4.26 were not
executed. P4.7-P4.8 remain complete, existing changes were preserved, and no
commit was made. Recommended next chunk: P4.9-P4.26. Full-context observability
and later runtime isolation/lifecycle checks remain unverified.

## P4.7-P4.26 Resume on 2026-10-13

### P4.7 Complete

The actor correction was present at entry and was preserved. The installer
validates the closed optional `systemActorName` against the declaration and
rejects tenant actors without referencing `ActorType.SYSTEM`. Added entry-state
regressions cover non-system and tenant actors. No conformance rule changed.

Verification passed: compileJava, compileTestJava, focused installer/initializer/
runner unit tests, all four SessionSweepLock PostgreSQL cases, conformanceTest,
spotlessJavaCheck, checkstyleMain, checkstyleTest and checkstyleIntegrationTest.
P4.7 was marked complete before beginning P4.8. Full-context external
observability configuration and later lifecycle behavior were not claimed.

### P4.8 Draft and Test Compilation Failure

Extended the registry-loader prerequisite to reject declared interval bounds
outside 100 ms through 24 hours, even when the stored interval is valid, and
to clear the previously validated snapshot before attempting another load.
Added tests for declaration bounds, the worker-factory qualifier, startup
failure before lifecycle activation on a missing row, and failed-reload
snapshot invalidation. These new tests have not run.

Verification attempted:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*SweepRegistryLoaderTest' integrationTest \
  --tests '*SessionSweepLockIntegrationTest' conformanceTest \
  checkstyleMain checkstyleTest --console=plain
```

Formatting and production compilation passed. Test compilation failed in
`SweepRegistryLoaderTest` at the new `result.map(any())` Mockito stub: R2DBC
offers both `map(BiFunction<Row, RowMetadata, ...>)` and `map(Function<...>)`,
so the matcher is ambiguous. The correction is an explicitly typed matcher
for the row/metadata `BiFunction` overload. This is a test-compilation error,
not a production-compilation failure.

Execution stopped under the requested skill's failure rule. P4.8 remains
unchecked. Its test, integration, conformance and Checkstyle gates were not
reached; existing result files do not verify these new changes. P4.9-P4.26
were not implemented. P4.7's completed marker and existing changes were
preserved. No full application, deployment or release verification is claimed.

Recommended next chunk: P4.8-P4.26, starting with the typed-matcher correction
and P4.8 reverification. The prior full-context observability gap remains open.

## P4.6-P4.26 Resume on 2026-10-13

P4.6 is complete. The entry state already contained the published
`shared.api.ActorContextPropagation` contract, its existing web adapter, and
the runner's correction to depend only on that contract. Those changes were
preserved. Verification passed: compileJava, compileTestJava, all 12 focused
runner/loader tests, conformanceTest, spotlessJavaCheck, checkstyleMain and
checkstyleTest. No module boundary or conformance rule was weakened.

The runner supplies two serialized lanes, bounded fair coalescing, ownership
checks and asynchronous cleanup. Ordered shutdown and instrumentation remain
their own later tasks. P4.8 retains the prior authorization for its registry
prerequisite to have been brought forward.

### P4.7 Draft and Validation Failure

Added `SweepTransactions`, `SweepConnection` and `SweepDataModule` in
`platform.api`, implemented by worker-only `DefaultSweepTransactions` in
`platform.infra.persistence`. The API exposes statement-only transaction handles
and closed module targets, not arbitrary roles or raw connection lifecycle.
The runner propagates its declared metadata alongside the existing actor.

The draft checks actor/declaration agreement, module ownership, role/actor
mapping and nested transaction denial. The approved retention coordinator may
select one existing narrow business-module role per transaction; it receives no
union role, audit-administration role, new grant or RLS policy. Feature-owned
retention orchestration and cross-tenant selection policies remain outside this
runtime installer. Added the proposed AUTO_SUBMIT_SWEEP and STALE_CLAIM_RECLAIM
operations with their enumerated actor predicates. Tenant work gets a separate
tenant-system context and tenant scope, never a mixed platform/tenant scope.

Transaction opening remains inside `SecurityContextInitializer`. Its managed
boundary now accepts the already secured/traced worker factory so telemetry is
preserved. The installer rejects undecorated factories. Coordination validates
an ambient tick actor against its declaration, while preserving the existing
actor-free internal bootstrap registry read.

Verification command:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*DefaultSweepTransactionsTest' --tests '*SecurityContextInitializerTest' \
  --tests '*SweepRunnerTest' integrationTest \
  --tests '*SessionSweepLockIntegrationTest' conformanceTest \
  checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

Passed: compilation, 29 focused unit tests (six new installer tests, 18 existing
initializer tests, five runner tests), and four PostgreSQL integration cases.
The new PostgreSQL case reads `current_user` and
`current_setting('app.platform_scope', false)` from the first business query
and obtains `app_delivery:auto_submit_sweep` on the worker business pool, with
both business and lock leases released. Unit tests check statement ordering,
tenant scope, absent/mismatched identity, wrong module, nested transactions,
undecorated factories and unscoped query rejection.

Conformance ran 56 cases: 55 passed and one failed:

```text
ActorAttributionConformanceTests.systemActorsCanOnlyBeConstructedThroughTheClosedEnumeration:
SYSTEM-ACTOR: DefaultSweepTransactions references ActorType.SYSTEM directly.
```

The rule prohibits any direct reference to that enum constant outside the
kernel actor types, including the new comparison in `requireSweepActor`.
The correction should use `ActorContext.systemActorName`'s closed optional
identity, whose constructor invariant already excludes non-system actors,
and compare it with the declaration. Do not weaken the conformance rule.

Execution stopped at this validation failure as required. P4.6's completed
marker was persisted before P4.7 began; P4.7 remains unchecked. P4.8-P4.26
were not executed in this run. Formatting was applied, but Checkstyle was not
reached after the conformance failure. The full application suite was not run.
`git diff --check` passed; existing user changes remain intact. No commit was made.

Recommended next chunk: P4.7-P4.26, starting with this correction and focused
reverification. The earlier full-context external observability configuration
gap remains unverified, not newly resolved by these focused tests.

## P4.6-P4.26 Attempt on 2026-10-13

Requested range: P4.6-P4.26, strict sequential execution. P4.1-P4.5 were
complete at entry. The existing registry-prerequisite ordering authorization
and the verified actor-attribution correction were preserved.

Added draft `SweepRunner` and `PendingSweepQueue` in `platform.infra.runtime`,
plus `SweepRunnerTest`. The worker-only runner merges ordinary interval signals
into one serialized lane and gives the outbox relay its own lane. Each lane's
bounded queue coalesces signals by sweep name without changing arrival order.
The permit covers acquisition, bounded batches, ownership checks and asynchronous
release. A tick receives its code-declared system actor and a correlation ID.
The ordered graceful shutdown phases and sweep metrics remain later tasks.

Verification command:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test \
  --tests '*SweepRunnerTest' --tests '*SweepRegistryLoaderTest' \
  conformanceTest checkstyleMain checkstyleTest --console=plain
```

Compilation and all 12 focused tests passed: five runner tests and seven loader
tests. The runner cases cover a 250-second virtual-time overrun, bounded pending
signals, fair coalescing, independent relay progress, asynchronous release after
timeout, contention, lock loss, actor propagation and worker-only activation.

Architecture conformance ran 56 tests: 52 passed and four failed. The new
runner imports and injects `shared.infra.web.RequestContextPropagation`, which
is not a published shared-module interface. The failures are:

- `ContextModuleDependencyTests.descriptorsMatchThePermittedDependencyMatrixExactly`
- `ContextModuleStructureTests.verifiesExactlyTwelveContextModules`
- `R2ModuleBoundaryTests.modulithDescriptorsAreValid`
- `R2ModuleBoundaryTests.crossModuleImportsTargetOnlyPublishedApis`

Representative diagnostic:

```text
R2 module boundary violated: SweepRunner imports non-published type
org.meldtech.platform.shared.infra.web.RequestContextPropagation from shared;
only declared named interfaces are permitted.
```

Execution stopped under the requested skill's validation-failure rule. No task
was marked complete. P4.6 remains a draft; P4.7-P4.26 were not executed.
Formatting was applied; Checkstyle was not reached after the conformance
failure. No integration or full application suite was run in this attempt.
`git diff --check` passed. Existing staged user changes were preserved.

Resume by removing the private shared-infrastructure dependency, using published
contracts and a runtime-owned context adapter as appropriate. Do not expose
shared internals or weaken R2 merely to pass the gate. Reverify P4.6 before
marking it complete or beginning P4.7. Recommended next range remains P4.6-P4.26.

## Earlier Execution

Date: 2026-10-12. Requested range: P4.1-P4.26, strict sequential execution.
Plan: `../student_assessment/workspace/v3/be/plan/plan.md`, section 8.1,
FEAT-PLAT-006. Task list: `tasks/foundation/runtime/tasks.md`.

## Completed Tasks

P4.1: role-declaration types and focused regression tests.
P4.2: single-active-role assertion and corrected regression tests.
P4.3: sweep API and verified duration validation and batch-contract tests.
Existing P0-P3 completion markers are unchanged; P4.4-P4.26 have not been started.

## Initial P4.1 Failure

Added draft production types under
`src/main/java/org/meldtech/platform/platform/api/`:

- `RuntimeRole.java`: closed API/WORKER/PINDIST vocabulary and strict lookup.
- `RoleScoped.java`: the P2.1 type/method annotation with `@Profile({})`
  and a `String[]` alias to `Profile.value`.

Added `src/test/java/org/meldtech/platform/platform/api/RoleScopedTest.java`
to exercise all three roles, an additional environment profile, component and
factory-method selection, and invalid profile names.

Verification command:

```sh
./gradlew compileJava compileTestJava test --tests 'org.meldtech.platform.platform.api.RoleScopedTest' --console=plain
```

Production and test compilation succeeded. The focused suite ran 12 tests:
9 passed and 3 failed. All three role-selection cases fail at component
registration with:

```text
java.lang.IllegalArgumentException: Must specify at least one profile expression
    at org.springframework.core.env.ProfilesParser.parse
    at org.springframework.context.annotation.ProfileCondition.matches
```

The approved P2.1 annotation shape does not select profiles successfully on
the installed Spring runtime. The failure occurs before context refresh and
does not require PostgreSQL or Docker. The generated report is
`build/reports/tests/test/index.html`; the machine-readable result is
`build/test-results/test/TEST-org.meldtech.platform.platform.api.RoleScopedTest.xml`.

Execution initially stopped under the explicitly invoked execute-tasks skill's
failure rule. P4.1 was left unchecked, with its source and failing regression
tests preserved for diagnosis. No later task or framework upgrade was introduced.

## P4.1 Correction

Inspection of the installed Spring Framework 7.0.9 sources confirmed that
`ProfileCondition` calls `AnnotatedTypeMetadata.getAllAnnotationAttributes`,
which explicitly ignores composed-annotation attribute overrides. It therefore
reads the empty meta-annotation array, not `RoleScoped.value`.

Replaced `@Profile({})` and its alias with
`@Conditional(RoleScopedCondition.class)`, preserving the existing string-array
declaration syntax. The package-private condition is annotation support in
`platform.api`, with no infrastructure dependency. It reads `RoleScoped` metadata,
validates every value through `RuntimeRole`, rejects empty or duplicate values,
and delegates profile matching to Spring. All declarations are validated before
matching, so a matching role cannot hide an invalid later value.

This corrects the P2.1 annotation mechanism; it does not implement the P4.2
single-active-role assertion or the later metadata/conformance validators.

Verification:

```sh
./gradlew compileJava compileTestJava test --tests 'org.meldtech.platform.platform.api.RoleScopedTest' spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```

Passed: compilation, all 22 focused cases, Java formatting and main/test
Checkstyle. Coverage includes type and factory-method selection for all three
roles alongside an environment profile, exclusion without a role, and rejection
of empty, duplicate, unknown, environment, expression, negated and placeholder
declarations.

`./gradlew test conformanceTest --console=plain` also passed, including the full
unit suite, migration-verifier unit tests and architecture conformance suite.
`git diff --check` passed.

## P4.2-P4.26 Execution Attempt

Requested range: P4.2-P4.26 on 2026-10-12. The corrected P4.1 implementation
and its completed marker were present at entry and were preserved.

Added `platform/infra/runtime/RuntimeRoleConfiguration.java`: a static bean
factory post-processor resolves exactly one explicitly active runtime role
and registers the immutable enum value before ordinary bean instantiation.
Environment profiles may coexist with the role; default profiles cannot
select a runtime role. No migration-bootstrap change was made.

Added `platform/infra/runtime/RuntimeRoleConfigurationTest.java` to verify
valid roles, rejected combinations, rejection before an external listener is
instantiated, default-profile rejection and Boot profile-group expansion.

Verification command:

```sh
./gradlew compileJava compileTestJava :test --tests 'org.meldtech.platform.platform.infra.runtime.RuntimeRoleConfigurationTest' --tests 'org.meldtech.platform.platform.api.RoleScopedTest' --console=plain
```

Compilation succeeded. The focused run executed 33 cases: 27 passed and
6 failed. All 22 P4.1 cases passed, as did the three valid-role cases,
default-profile rejection and profile-group expansion.

The six invalid-profile cases failed in JUnit parameter resolution before
executing the test body. The `Stream<String[]>` method source is interpreted
as individual invocation arguments, while the test expects one `String[]`.
This is an error in the new test fixture, not evidence of a production
resolver defect. Correct the provider by wrapping each array as a single
JUnit `Arguments` value, then run these cases before claiming completion.

Execution initially stopped under the invoked skill's validation-failure rule.
P4.2 was left unchecked, with its code and failing tests preserved. No later
task was implemented.

## P4.2 Correction

Changed `invalidProfiles()` to return `Stream<Arguments>` and wrap each array
with `Arguments.of((Object) profiles)`. The explicit `Object` cast prevents
varargs expansion, keeping each complete profile combination as the test's
single argument, including the empty array. Production code is unchanged.

All 33 focused P4.1/P4.2 cases passed after this correction, including the six
negative combinations and their assertion that no application bean is created.
Corrected the test file's try-with-resources formatting as required by Spotless.

The full unit suite then exposed missing runtime profiles in the existing
application smoke test and tenant-isolation gate test. Both now explicitly use
`@ActiveProfiles("api")`, satisfying the new startup contract without adding a
default role or weakening the production assertion.

The full unit-suite attempt ran 504 cases: 501 passed and three failed across
those two context classes. With role validation satisfied, context creation now
fails on missing external observability configuration, starting with
`CBT_OBSERVABILITY_COLLECTOR_ENDPOINT`. No production configuration defaults or
security bypasses were introduced to satisfy the tests.

Final verification passed:

```sh
./gradlew compileJava compileTestJava :test --tests 'org.meldtech.platform.platform.infra.runtime.RuntimeRoleConfigurationTest' --tests 'org.meldtech.platform.platform.api.RoleScopedTest' conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```

This covers all 33 focused cases, compilation, architecture conformance,
Java formatting and main/test Checkstyle. `git diff --check` also passed.

## P4.3-P4.26 Execution Attempt

Requested range: P4.3-P4.26 on 2026-10-12. P4.1 and P4.2 were complete at
entry; their implementations, test changes and completion markers were preserved.

Added draft public API files under
`src/main/java/org/meldtech/platform/platform/api/`:

- `SweepDefinition.java`: deferred one-batch port, including commit/rollback,
  cancellation, durable recovery and stop-at-boundary obligations.
- `SweepMetadata.java`: immutable name, ownership, enumerated `SystemActor`,
  interval bounds and batch/tick duration declarations.
- `SweepBatchResult.java`: nonnegative processed count and remaining-work flag.
- `SweepStopSignal.java`: read-only stop request contract.

Added `src/test/java/org/meldtech/platform/platform/api/SweepDefinitionTest.java`
with metadata validation and deferred-work, boundary-stop and cancellation
contract cases. Its transaction fixture is in-memory; it does not implement
the P4.26 PostgreSQL reference sweep or prove database behavior.

Verification command:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test --tests 'org.meldtech.platform.platform.api.SweepDefinitionTest' --console=plain
```

Java formatting completed. Production compilation failed in
`SweepMetadata.requireDuration`:

```text
[ReturnValueIgnored] Return value of 'toMillis' must be used
[JavaDurationGetSecondsGetNano] duration.getNano() only accesses the underlying nanosecond adjustment from the whole second.
```

The first diagnostic is an Error Prone error, and the second is a warning
under a build configured with `-Werror`. Test compilation and tests did not
run. The draft uses `toMillis()` only to detect overflow, which violates the
repository's return-value check. A correction should consume the millisecond
value and compare the reconstructed duration to the original, preserving
rejection of overflow, nonpositive values and fractional milliseconds.

Execution initially stopped under the invoked skill's build-failure rule.
P4.3 was left unchecked with the draft and diagnostics preserved; P4.4-P4.26
were not started.

## P4.3 Correction

`SweepMetadata.requireDuration` now consumes `toMillis()` and requires a positive
result whose `Duration.ofMillis` reconstruction equals the original duration.
This rejects fractional milliseconds without accessing the nanosecond component.
Overflow still becomes an `IllegalArgumentException` with its original cause.
No Error Prone suppression or build-rule change was needed.

Added regression coverage for a fractional millisecond above 1 ms and at the
maximum millisecond value, overflow just above that maximum, and acceptance of
1 ms and `Long.MAX_VALUE` milliseconds. Invalid-duration assertions identify the
field so interval-ordering checks cannot conceal a precision-validation failure.

The original verification command now passes production/test compilation and
all six `SweepDefinitionTest` cases, including deferred work, stop-at-boundary
and cancellation behavior in the in-memory contract fixture.

`./gradlew conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest --console=plain`
and `git diff --check` also passed.

## Remaining Tasks and Risks

P4.4-P4.26 remain incomplete. Full-context verification still requires external
application configuration as recorded under P4.2. The full unit suite and
PostgreSQL integration tests were not rerun for this focused correction. The
in-memory fixture does not prove PostgreSQL transaction behavior; P4.26 owns
the database reference sweep.

## P4.4-P4.26 Dependency Check

Date: 2026-10-12. Requested range: P4.4-P4.26, strict sequential execution.
P4.1-P4.3 were complete at entry. No task in this range was completed or
implemented; P4.4 is blocked before implementation, not a failed test.

The prerequisite inspection found an ordering conflict in the Phase 2 design:

- P2.5 requires P4.4's session adapter to use the dedicated lock pool.
- `WorkloadConnectionPoolConfiguration.sweepLockConnectionFactory` supplies
  that pool through `SecurityContextInitializer` and the tracing decorator.
- `SecurityContextInitializer` rejects transactions without its internal
  security context and rejects statements before initialization.
- P2.14 explicitly requires lock SQL to install `SET LOCAL ROLE app_worker`
  and the closed `SWEEP_REGISTRY_READ` operation. It assigns extension of the
  initializer's policy to P4.7 and explicitly forbids bypassing the decorator.
- The current `AssumableDatabaseRole` has no `app_worker` coordination entry;
  `PlatformOperation` has no `SWEEP_REGISTRY_READ`; the only published
  `DatabaseSecurityScopes` entry point is `withOutboxRelay`.

Consequently, P4.4 cannot exercise the approved coordination path on its
required pool until the coordination portion of P4.7 exists. Using the
outbox actor/scope for every sweep, bypassing the secured connection or adding
a second raw pool would not satisfy the design. An adapter tested only with
an unsecured fixture would leave this dependency unresolved.

The invoked execute-tasks skill requires stopping on missing dependencies
and prohibits executing future tasks or reordering tasks. This run therefore
stopped before adding a partial adapter. Existing code, staged changes and
completion markers were preserved. Only this evidence file and the Phase 4
status paragraph in the task list were updated.

Validation: source and design inspection; `git diff --check`. No build or test
was run because no production or test code changed in this attempt.

Remaining range: P4.4-P4.26. To unblock, explicitly permit implementing P4.7's
coordination-security prerequisite before P4.4, or revise the task ordering to
place that prerequisite first. Then resume P4.4-P4.26. Keep the business-sweep
security work and P4.7's completion marker pending until all P4.7 acceptance
criteria are met. This record does not authorize or perform the reorder.

## P4.4 Resolution

The request to fix the blocked P4.4 authorized the minimum dependency move.
Only the lock-coordination portion of P2.14 was brought forward; P4.7 remains
open for the complete sweep security-context installer.

Implemented the public `SweepLock` port and worker-only `SessionSweepLock`.
The adapter obtains the named `sweepLockConnectionFactory`, looks up the stored
registry key, and executes one parameter-bound `pg_try_advisory_lock` call.
Empty completion represents contention. A winning handle retains the same
physical session for ownership checks and its idempotent, parameter-bound
`pg_advisory_unlock` cleanup. A per-handle reactive queue serializes ownership
checks with release; once close begins, later checks fail without touching the
connection.

Checkout and acquisition use reactive resource cleanup. Normal contention
returns the lease immediately; acquisition errors and cancellation discard the
physical connection because the lock outcome may be uncertain. Unlock false,
unlock error, or transaction cleanup failure logs
`sweep_lock_release_failed` and discards the connection instead of returning a
possibly locked session to the pool.

The existing security initializer now supports successive short transactions
on one retained lease. `DatabaseSecurityScopes` installs the exact internal
coordination context (`SET LOCAL ROLE app_worker`, closed
`SWEEP_REGISTRY_READ`, and platform-only search path) and owns begin/commit/
rollback so R9 remains unchanged. `DiscardableConnection` propagates physical
invalidation through both security and tracing decorators. No database role,
pool, or grant was added.

Verification passed:

```sh
./gradlew spotlessApply test \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest' \
  --tests 'org.meldtech.platform.platform.infra.persistence.AssumableDatabaseRoleTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SessionSweepLockTest' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' \
  conformanceTest spotlessCheck checkstyleMain checkstyleTest
```

The focused run passed 25 unit cases, the PostgreSQL contention/release/
reacquisition integration case, all 56 architecture conformance cases, and
formatting and Checkstyle. The integration case also proves the winning handle
holds one dedicated-pool lease while a competing try returns empty.

A full `test` attempt ran 515 cases: 512 passed and the three already-recorded
full-context cases failed while binding absent external observability settings.
No P4.4 test failed. P4.5-P4.26 remain unstarted.

## P4.5-P4.26 Execution Attempt

Requested range: P4.5-P4.26 on 2026-10-12, strict sequential execution.
P4.1-P4.4 were complete at entry; their completion markers were preserved.
No task in the requested range was marked complete.

### P4.5 Draft

Added `TransactionSweepLock` under `platform.infra.runtime`, implementing the
existing `SweepLock` port on the dedicated lock pool. It retains a coordination
transaction across the handle lifetime, acquires the persisted key with
`pg_try_advisory_xact_lock`, and rolls back on release. The handle serializes
ownership checks and close, and caches release for idempotence. Acquisition
cancellation and uncertain cleanup discard the connection. Normal operations
have two-second timeouts.

Added `SweepLockConfiguration` to validate the closed adapter/pooling settings;
both adapters now have exclusive property conditions. Session remains the
default; transaction pooling with session locks fails startup. Reused the
session adapter's registry/result mapping and ownership query. Documented
the configuration and T-15 limitations in `docs/runtime-connection-pools.md`;
P10.6 remains open for the complete adoption guide.

Added `TransactionSweepLockTest` and `SweepLockConfigurationTest`, and extended
`SessionSweepLockIntegrationTest` to verify cross-adapter contention and
reacquisition against PostgreSQL. `DatabaseSecurityScopes` gained a draft
entry point for beginning a retained coordination transaction.

### Verification and Failure

Passed:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test --tests 'org.meldtech.platform.platform.infra.runtime.*Test' --console=plain
```

The subsequent command was:

```sh
./gradlew integrationTest --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

Both PostgreSQL integration cases passed. The architecture suite ran 56 cases:
55 passed, one failed. R9 reported:

```text
R9_UNDECORATED_CONNECTION_ACCESS: org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes opens a transaction outside SecurityContextInitializer
```

The new facade directly invokes `Connection.beginTransaction()` inside its
deferred publisher. Although the publisher installs the existing security
scope, R9 requires the transaction-opening call itself to reside in the
initializer. The correction is to move that operation into
`SecurityContextInitializer` and make the facade delegate to it, preserving
the existing conformance rule. This correction was not applied in this run.

Execution stopped under the invoked skill's validation-failure rule. P4.5
remains unchecked and P4.6-P4.26 were not implemented. Formatting was applied
in the first command; the separate formatting check and Checkstyle tasks in
the second command were not reached. The full test suite was not rerun.
`git diff --check` passed.

### Resume State at Failure

Resume P4.5-P4.26 with the R9 correction and rerun the focused checks before
marking P4.5 complete. The draft currently fails architecture conformance.
Direct PostgreSQL coverage does not prove PgBouncer deployment behavior or
discharge the broader P7.4 equivalence task. The previously recorded external
observability configuration gap for full-context tests remains unresolved.

### R9 Correction

Moved the retained transaction's deferred `Connection.beginTransaction()` call
and internal scope setup into `SecurityContextInitializer.beginInternalPlatformTransaction`.
`DatabaseSecurityScopes.beginSweepCoordinationTransaction` now delegates to that
boundary. The R9 conformance rule is unchanged.

Added a regression test proving that the facade defers execution, installs
`app_worker`, `sweep_registry_read`, and the platform search path in order,
and leaves the transaction open for its owner to release.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.*Test' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

The focused unit tests, both PostgreSQL integration cases, all 56 conformance
cases, formatting, and Checkstyle passed. This closes the reported R9 failure;
the full application test suite was not rerun for this correction.

## P4.5 Completion and P4.6 Dependency Check

Requested range: P4.5-P4.26 on 2026-10-12. The R9 correction and its regression
test were already present at entry. Inspection confirmed the facade delegates
transaction opening to `SecurityContextInitializer`; the conformance rule
remains unchanged.

The combined verification command recorded under "R9 Correction" passed again.
The focused unit task executed; compilation, PostgreSQL integration tests,
architecture conformance, formatting and Checkstyle were accepted by Gradle
as up to date. No production or test code changed in this attempt. P4.5 was
marked complete immediately after verification. Its delivered files are
`TransactionSweepLock`, `SweepLockConfiguration`, the session adapter's
exclusive selection condition, the initializer/facade coordination entry point,
unit tests, expanded PostgreSQL contention test and lock configuration docs.
P7.4 and P10.6 retain their broader verification/documentation obligations.

P4.6 dependency inspection found that the enabled registry snapshot is absent:

- P4.6/P2.6 require interval sources driven by enabled registry rows and their
  intervals, with metadata agreement before scheduling.
- The only production registry queries are the adapters' name/key lookups;
  neither reads `enabled`, interval bounds, owner or actor metadata.
- There is no registry loader or enabled-sweep snapshot in production code.
  P4.8 explicitly owns registry loading and bounds validation.
- P2.13 requires a missing/unreadable registry to fail worker startup, and
  validation must precede sweep activation. Treating all bean definitions as
  enabled or using an empty fallback would not satisfy that contract.

Under the invoked skill's strict sequencing and missing-dependency rules,
execution stopped before implementing P4.6. No new build/test failure occurred.
The task list and this record were updated; existing code and staged changes
were preserved. `git diff --check` passed.

Remaining requested tasks: P4.6-P4.26. Next prerequisite: permit bringing the
registry loading/validation portion of P4.8 forward for P4.6, or revise the
ordering to put that dependency first. The complete registry identity gate
and sweep security context still need their separately tracked tasks; this
record does not mark those delivered or authorize additional reordering.

Recommended next chunk after resolving ordering: P4.6-P4.26. Previously
recorded full-context configuration gaps remain; no release-level PgBouncer,
shutdown or exactly-once behavior is claimed by P4.5 completion.

## Authorized Registry Prerequisite Attempt

Date: 2026-10-12. The user explicitly authorized bringing P4.8's registry
loading/validation prerequisite forward before P4.6. That ordering exception
remains authorized; no additional approval is needed to resume this work.
The requested continuation remains P4.6-P4.26 after completing the prerequisite.

Added `SweepRegistryLoader` in `platform.infra.runtime`. The worker-only
component reads the complete registry through the existing secured connection
and coordination transaction. Its production qualifier is
`workerConnectionFactory`. A bounded bootstrap wait validates the result
before Spring lifecycle participants start; failed loading leaves the
enabled-sweep snapshot unavailable. No request path blocks.

The draft validates canonical identities, known modules, closed actor values,
interval bounds and fixed cadences, duplicate names/definitions, missing rows,
enabled rows without implementations, declaration/registry metadata agreement,
and batch/tick duration caps. Disabled future rows may lack a sweep bean.
P4.9's independent lock-key collision validator is not implemented here.

Added five `SweepRegistryLoaderTest` cases and a PostgreSQL registry-read case
to `SessionSweepLockIntegrationTest`. The latter reuses that fixture's secured
worker-login lock-pool connection and proves all six seeded rows are read as
disabled with the lease released. It does not claim production worker-pool
qualifier wiring or the complete P4.8 startup suite has been verified.

Passed:

```sh
./gradlew spotlessJavaApply compileJava compileTestJava :test --tests 'org.meldtech.platform.platform.infra.runtime.SweepRegistryLoaderTest' --console=plain
```

The subsequent command was:

```sh
./gradlew integrationTest --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

All three PostgreSQL cases passed. Architecture conformance ran 56 cases:
55 passed and one failed:

```text
SYSTEM-ACTOR: org.meldtech.platform.platform.infra.runtime.SweepRegistryLoader constructs a SystemActor from free text.
```

The loader calls `SystemActor.valueOf` on a database string, which the existing
actor-attribution rule prohibits. The correction should validate the stored
actor name against the closed vocabulary and the sweep's code declaration,
using the declared enum identity for execution. Do not weaken the conformance
rule to permit unchecked actor parsing.

Execution stopped under the invoked skill's validation-failure rule. No task
was marked complete in this attempt. P4.8's prerequisite remains draft;
P4.6, P4.7 and P4.9-P4.26 were not implemented. The separate formatting check
and Checkstyle tasks were not reached after the failure. The full test suite
was not run; its previously recorded external configuration gap remains.
`git diff --check` passed.

Resume with the actor-validation correction under the existing ordering
authorization, verify the prerequisite, then continue P4.6-P4.26. The loader
must not activate a runner while its conformance validation remains failing.

### Actor Attribution Correction

`SweepRegistryLoader.RegistryRow` now retains `system_actor` as a string named
`systemActorName`. Validation checks exact membership in the closed enum's
names for every row, including disabled future rows, then compares it with the
sweep definition's declared actor. Enabled entries retain the code-declared
definition for execution. Database text is never converted into a `SystemActor`,
and the actor-attribution conformance rule is unchanged.

Regression coverage accepts every enumerated actor name for disabled future
rows and rejects unknown, case-altered, and whitespace-padded names on both
enabled and disabled rows. Existing coverage still rejects a known actor that
disagrees with the sweep declaration.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepRegistryLoaderTest' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

All seven loader unit tests, three PostgreSQL integration cases, 56 conformance
cases, formatting, and Checkstyle passed. The reported actor-attribution
failure is resolved. The full application suite was not rerun, and this
focused correction does not change the broader task completion markers.

### Sweep Runner Module Boundary Correction

Date: 2026-10-13. The four module conformance failures shared one cause:
`SweepRunner` imported the non-published
`shared.infra.web.RequestContextPropagation` implementation.

Published the narrow `ActorContextPropagation.write` contract in the existing
`shared.api` named interface. The existing request-context component implements
it, and the runner now receives the published interface through constructor
injection. Module descriptors and conformance rules are unchanged.

Runner regression coverage verifies Spring injection of the implementation
through the interface, propagation of the declared actor and logging fields,
and removal of inherited tenant metadata for platform sweeps. Simplified two
nested expressions in the draft runner and its test to satisfy Checkstyle's
line-length limit while preserving deferred execution.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepRunnerTest' \
  --tests 'org.meldtech.platform.shared.infra.web.*Test' \
  conformanceTest spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```

Runner and shared web/context tests, all 56 conformance cases, formatting, and
Checkstyle passed. The reported module-boundary failures are resolved. The
full application suite and PostgreSQL integration suite were not rerun for
this boundary correction; broader task completion markers remain unchanged.

### Sweep Transaction Actor Correction

Date: 2026-10-13. `DefaultSweepTransactions.requireSweepActor` directly referenced
`ActorType.SYSTEM`, violating the actor-attribution rule. It now requires an
absent tenant and a `systemActorName` equal to the sweep's declared enum value.
`ActorContext` already guarantees that this optional identity exists only for
system actors. Missing identities and mismatches produce the existing
`SecurityException`; the conformance rule is unchanged.

Added regression coverage for both platform and tenant transaction entry points:
workforce and candidate actors with the same textual actor ID, and tenant-scoped
system actors, are rejected before connection checkout. Existing tests cover
valid declared system actors and mismatched enum identities. Also extracted the
integration fixture's overlong SQL literal into a local variable for Checkstyle.

Verification:

```sh
./gradlew compileJava compileTestJava :test \
  --tests 'org.meldtech.platform.platform.infra.persistence.DefaultSweepTransactionsTest' \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepRunnerTest' \
  --tests 'org.meldtech.platform.shared.kernel.context.ActorContextTest' \
  integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.SessionSweepLockIntegrationTest' \
  conformanceTest --console=plain
./gradlew spotlessJavaCheck checkstyleMain checkstyleTest checkstyleIntegrationTest --console=plain
```

All 35 focused unit tests, four PostgreSQL cases, 56 conformance cases, formatting,
and Checkstyle passed. The full application suite was not rerun. The task status
now records the resolved conformance blocker; task completion markers are unchanged.

### Registry Exception Serialization Correction

Added `private static final long serialVersionUID = 1L` to
`SweepRegistryLoader.RegistryValidationException`, matching the repository's
exception convention and resolving the serial warning under `-Werror`.

Verification passed:

```sh
./gradlew compileJava compileTestJava :test \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepRegistryLoaderTest' \
  spotlessJavaCheck checkstyleMain checkstyleTest --console=plain
```
