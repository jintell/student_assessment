# FEAT-PLAT-006 Phase 4 Execution

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
