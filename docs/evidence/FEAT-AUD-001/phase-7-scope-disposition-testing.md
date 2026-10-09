# Audit verification and disposition testing

Requested range: P7.15-P7.28. Date: 2026-10-09.
Existing uncommitted P7.11-P7.14 changes are preserved.

## Execution summary

- Completed: P7.15-P7.18. Each completion marker was saved after its task passed.
- Blocked before implementation: P7.19; missing production disposition and
  retained-evidence adapters. P7.20-P7.28 were not started, per strict sequencing.
- Modified: `AuditDailyVerifierTest`, `AuditDispositionExecutorTest`,
  `AuditAppendIntegrationTest`, `AuditStoreHardeningIntegrationTest`, the four
  completion entries and P7.19 blocker entry in `tasks.md`, and this report.
- Production code, migrations and earlier task markers were not changed by this
  execution. Existing uncommitted work remains present.
- Recommended next range: P7.19-P7.28 after supplying the prerequisite adapters.

## P7.19: dependency stop

The migrated schema, real append and sealing adapters, P3.13 fixture, pure chain
algebra, disposition executor and disposal event builder exist. However,
`AuditDispositionOperations` has no production implementation: all five runtime
operations and progress persistence stop at the port. The only implementation is
the controlled `RecordingOperations` fixture in `AuditDispositionExecutorTest`.
`AuditFullVerificationEvidence` likewise has no production adapter for loading
retained chains and tenant-root evidence. Searches covered production Java and
the audit migrations, including DETACH and lifecycle state access.

`V13__create_audit_disposition_lifecycle.sql` creates the state table and grants;
it does not implement the protocol. `AuditDispositionEvidenceEmitter` creates the
correct event shape but is not wired into production disposition operations.
The direct DETACH in P7.17 tests structural partition behavior only.

Required prerequisite: a production operation adapter that freezes/verifies the
expired epoch, verifies its seal/root chain, commits and verifies disposal
evidence, rechecks policy/holds before disposition, persists progress, and verifies
retained evidence afterward, with real evidence-loading adapters and approved
transaction/role boundaries. P4.24/P4.26/P4.27 need this integration before P7.19
can prove their database behavior. Their markers are outside this range.

No test-only disposal implementation was substituted for the missing production
path. Risks still open: actual disposal ordering, hold/policy enforcement, evidence
continuity after removal, and concurrent sealing during disposition. A7 is not
discharged. The execute-tasks rule requires: "If dependencies are missing: Stop
execution and report blockers." Resume at P7.19 after the prerequisite repair.

## P7.15: daily verification scope

`AuditDailyVerifierTest.retainedRecordGrowthDoesNotIncreaseDailyWalkCost` compares
sealed anchors covering 10 and 1,000,000 retained records with the same one-record
open chain and one sealed epoch. Both runs require one open-chain record query,
one record consumed and two signature checks (checkpoint plus seal). The test
checks the exact signing messages and rejects a record read for any other chain.
JUnit test properties retain the operation counts for both sizes.

This establishes application-level traversal cost: retained event rows are not
re-walked. Seal/root work still scales with the number of anchors and shards.
Evidence and cryptographic ports are test doubles; this is not a database query
plan, real KMS verification or a wall-clock benchmark.

Validation: `./gradlew spotlessJavaApply compileJava compileTestJava test --tests
'org.meldtech.platform.audit.application.AuditDailyVerifierTest' --console=plain`;
compilation passed, and both daily-verifier tests passed after correcting the new
test's identity comparison of immutable signature/message objects.

## P7.16: retention placement

Three `AuditAppendIntegrationTest` cases use the production resolver, emitter,
append adapter and secured transaction against migrated PostgreSQL:

- `longestHorizonDeterminesPhysicalPlacementAtEmission`: a five-year publication
  horizon beats the shorter general horizon; both stored retention and the
  actual leaf partition (`tableoid`) match.
- `storedPlacementMatchesThePolicyVersionEffectiveAtOccurrence`: writes one
  microsecond before and exactly at the policy transition, proving versions 1
  and 2 respectively and matching class, key, version and horizon on re-evaluation.
- `reclassificationAppendsNewEvidenceWithoutUpdatingTheOriginal`: appends the
  documented `privacy.RETENTION_RECLASSIFIED.v1` event with original-event,
  prior-class and policy references; two events occupy their appropriate class
  partitions and the original complete row snapshot is identical before/after.

The policy view is a deterministic two-version fixture. These tests establish
the audit placement contract; the FEAT-PRIV-001 policy store and reclassification
business workflow remain owned by that feature.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditAppendIntegrationTest' --console=plain`
passed all 11 cases (three new placement cases and eight append regressions).

## P7.17: physical partition homogeneity and detachability

Two new `AuditStoreHardeningIntegrationTest` cases reuse `AuditPostgreSqlFixture`
and the migrated PostgreSQL database. Grouping by physical `tableoid` proves the
12 populated leaves each contain exactly one retention class, one period, two
shards and two records (four classes across three months).

The detach test enumerates every installed audit leaf through `pg_partition_tree`,
including empty provisioned leaves. For each leaf it performs real DETACH DDL,
checks that the parent loses exactly that leaf's records, the detached table
retains every row, and no inheritance link remains. Each transaction rolls back,
and all 24 fixture rows remain visible afterward. This tests structural
detachability, including the held partition; it does not authorize retention
disposal or bypass a hold in a production workflow.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest.fixturePartitionsAreHomogeneousAcrossClassesMonthsAndShards'
--tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest.everyAuditLeafPartitionCanBeDetachedAsAWhole'
--console=plain` passed both cases.

## P7.18: mandatory disposition ordering

`AuditDispositionExecutorTest` now covers every stage separately:

- Five incomplete-stage tests withhold completion through a reactive gate,
  assert that no later operation or progress record runs, then reject the
  missing completion and require a terminal error without a success result.
- Five stage-failure tests assert the exact executed prefix and prohibit
  later work after each possible failure.
- Five progress-failure tests ensure the next operation cannot run without
  successfully recording the previous stage's completion.
- The positive control asserts all five operations and progress records in order.

This verifies the real executor's orchestration contract with controlled operation
ports. It does not validate the side effects of a production operation adapter;
an adapter returning success without performing its work would violate that
contract and requires separate integration evidence.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.application.AuditDispositionExecutorTest'
--console=plain` passed all 16 cases.

## Final verification

- `./gradlew compileJava compileTestJava test spotlessCheck checkstyleTest
  checkstyleIntegrationTest --console=plain`: passed. The full test task ran
  449 root-project and 66 migration-verification cases (515 total), with no
  failures, errors or skips.
- `./gradlew integrationTest --tests
  'org.meldtech.platform.audit.infra.AuditAppendIntegrationTest' --tests
  'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest' --console=plain`:
  passed all 531 cases (11 append/placement and 520 hardening/partition cases),
  with no failures, errors or skips.
- JUnit XML confirms both P7.15 measurements: retained counts 10 and 1,000,000,
  each with one query, one open record consumed and two signature checks.
- `git diff --check`: passed.

The full integration suite, clean build and CI pipeline were not run. P7.27 was
not reached; no pipeline certification or A6/A7 discharge is claimed.
