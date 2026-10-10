# Audit verification and disposition testing

Requested range: P7.15-P7.28. Date: 2026-10-09.
Existing uncommitted P7.11-P7.14 changes are preserved.

## Execution summary

- Completed: P7.15-P7.21. Each completion marker was saved after its task passed.
- Latest requested range: P7.24-P7.28. P7.24 is blocked before implementation;
  P7.25-P7.28 were not started under strict sequential execution.
- Earlier work in this report modified `AuditDailyVerifierTest`,
  `AuditDispositionExecutorTest`, `AuditAppendIntegrationTest` and
  `AuditStoreHardeningIntegrationTest`; P7.19 adds the production adapters and
  `AuditDispositionIntegrationTest`.
- Production disposition/evidence adapters, the PostgreSQL integration test and
  the exact audit-metadata allowlist were added. No migration was changed.
- P7.20 adds controlled interleavings and fixes the root-evidence read race.
  Existing P7.19 changes were preserved; no migration was changed.
- P7.21 adds production hold persistence and its PostgreSQL integration test.
- Recommended next range: P7.24-P7.28 after `FEAT-DLV-002` supplies the real
  answer-save route and the required audit load telemetry is operational.

## P7.19: end-to-end disposition

`R2dbcAuditDispositionOperations` now executes the five mandatory stages against
PostgreSQL and persists each completed stage in `audit_disposition_lifecycle`.
It verifies the target chains, unique seal, signature and dense tenant root;
emits and confirms `audit.AUDIT_EPOCH_DISPOSED.v1`; rechecks expiry and the
`AuditDispositionEligibility` policy/hold port; derives and detaches the exact
class/month leaf; and invokes the full verifier after detach.

`R2dbcAuditFullVerificationEvidence` loads tenant-scoped retained chain records,
checkpoints, seals, shard material and the root head, reconstructing the same
canonical envelopes used by the hash verifier. The payload secret policy now
permits only the exact required metadata paths `authorization_reference` and
`signature_reference.key_version`; lookalikes remain rejected.

`AuditDispositionIntegrationTest` creates a fresh migrated PostgreSQL database,
seeds the P3.13 fixture with production-compatible record hashes and seals all 12
epochs in canonical order. It disposes the expired January general-audit epoch
through the production executor and proves:

- the target has two valid shard chains and permanent seal `root_seq = 4` before
  detach;
- exactly one disposition event is committed in the current correction epoch,
  with the disposed class/month, both `1..1` shard ranges, exact root hash,
  policy key/version, request and authorization references;
- the parent exposes zero target rows while the detached leaf retains both rows
  and has no inheritance link;
- lifecycle state is `COMPLETED` at `RETAINED_EVIDENCE_VERIFIED`; and
- an independent post-disposition full verification accepts all 23 retained
  chains and the tenant's dense, duplicate-free 12-seal root chain.

This closes the non-concurrent ARC-VERIFY-032 integration limb. P7.20 below covers
the concurrent-seal interleavings; A7 is not discharged by P7.19 alone.

## P7.20: concurrent disposition and sealing

`AuditDispositionIntegrationTest` now uses a fresh migrated PostgreSQL database
per case. Eleven fixture epochs are sealed initially; the retained March general
epoch has two populated shards and is the twelfth seal. The original P7.19 case
still seals all twelve before executing disposition.

Two parameterized cases pause the real sealer after signing, immediately before
its production CAS append. Disposition starts only after that barrier is reached.
The seal then commits either at the final eligibility check before detach, or
after disposition and its retained-evidence verification complete. Assertions
prove the exact order without sleeps, committed disposal evidence while the
target is still attached, the unchanged retained-row snapshots, completed
disposition progress, and permanent seals with dense unique root sequences 1-12.
The independent final full verifier accepts all 23 retained chains and the root
chain, including the newly appended seal.

A separate regression reproduced a mixed root view: an observed sequence-11 head
returned alongside seals through sequence 12. `R2dbcAuditFullVerificationEvidence`
now reads the head before and after loading all seals and accepts only equal
heads. A changing head triggers at most three retries. It does not truncate seals,
which would hide orphan evidence. A fifth case simulates continuous movement and
proves termination after eight head reads with a retry-verification error, not an
integrity mismatch; the real database evidence remains valid. The stable-head
strategy relies on immutable seals and monotonically advancing production CAS.

The signer/verifier is a deterministic SHA-256 test fixture over the exact signing
message, replacing the always-true signature stub in this integration class.
These are PostgreSQL correctness tests, not real KMS or full-load A7 certification.
Legal-hold integration remains blocked below.

Validation on 2026-10-09: `./gradlew spotlessJavaApply compileJava compileTestJava
test integrationTest --tests 'org.meldtech.platform.audit.infra.AuditDispositionIntegrationTest'
checkstyleMain checkstyleTest checkstyleIntegrationTest spotlessCheck --console=plain`
passed. Counts: 451 application unit tests, 66 migration unit tests and five
disposition integration cases; zero failures, errors or skips. During development,
the new race regression failed as expected; an unchecked Mockito varargs warning
and a Checkstyle line-length violation were corrected before the successful run.
The full integration suite and clean-checkout pipeline were not run.

## P7.21: legal-hold lifecycle

`R2dbcAuditHoldRepository` now joins the caller's active `TransactionalConnection`.
Its suspension upsert persists the complete, normalized hold set and original
retention start/due times, preserves the earliest detection time, and reports a
change only when the stored hold state changes. Exact retries are therefore
idempotent and do not emit duplicate suppression evidence. Release updates only
the matching suspended request and returns the original timestamps from storage;
`AuditHoldService` rejects any caller attempting to substitute a new clock.

Keeping repository SQL and `R2dbcAuditEmitter` on the same transaction makes the
lifecycle transition and immutable audit append atomic. The adapter assumes the
owning retention workflow supplies that approved tenant transaction; it does not
broaden the signed grant matrix or create a privileged connection path.

`AuditHoldIntegrationTest` migrates fresh PostgreSQL, seeds the P3.13 fixture and
uses its held February publication partition. It proves:

- a forced suppression-emission failure rolls the lifecycle insert back;
- the real suppression promotes the partition to `HOLD_SUSPENDED`, stores the
  hold/legal-basis references and original clock, emits exactly one
  `audit.AUDIT_EPOCH_DISPOSITION_SUPPRESSED.v1`, and is idempotent on retry;
- the covered leaf remains attached with both shard rows throughout suspension;
- a release request with shifted start/due times fails and rolls its database
  update back, leaving the state and absence of release evidence unchanged; and
- the valid release emits exactly one `audit.AUDIT_EPOCH_HOLD_RELEASED.v1`, moves
  the lifecycle to `ELIGIBLE`, clears active holds, and returns/stores the original
  start and due times even though both precede the release time.

Validation on 2026-10-10: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditHoldIntegrationTest' --rerun-tasks
--console=plain` passed. The wider `test`, `conformanceTest`, `spotlessCheck` and
all main/test/integration Checkstyle gates passed. A fresh combined run of the
hold and disposition PostgreSQL classes also passed. P7.21 is complete; later
tasks were not executed.

## P7.22: dependency stop

The slice contract, endpoint, policy, handler, signed cursor, three indexed SQL
query modes and privileged-read event builder exist. Unit and database adapter
tests cover several pieces, but they cannot satisfy P7.22's slice-level security
claim because Phase 4 task 29 remains explicitly reopened.

The dependency recheck on 2026-10-10 confirmed all four open runtime gaps in
`TASK-AUD1-BLOCKER-002`:

- `AuditComplianceCapabilityView` has no authoritative IAM/tenancy adapter, so
  production authorization cannot prove the actor holds `AUDIT_COMPLIANCE_READ`
  for the request tenant;
- no approved `app_audit_compliance_reader` role or SELECT/grant amendment exists;
- no audited caller transaction installs that role and tenant on one connection
  shared by `R2dbcComplianceAuditQueries` and the read-audit append; and
- the endpoint, policy, handler, catalogue, cursor codec and emitter dependencies
  have no production bean graph.

An always-allow capability fixture, owner connection or isolated handler test
would bypass the exact authorization, tenant-scope and atomic read-audit behavior
this task must prove. The plan forbids token claims as authorization authority,
and the signed grant policy requires approval before widening database access.
Those prerequisites are outside P7.22-P7.28 and were not implemented here.

Per execute-tasks, "If dependencies are missing: Stop execution and report
blockers." P7.22 remains open and P7.23-P7.28 were not executed. No build or tests
were run for this documentation-only dependency stop; validation is
`git diff --check`. Resume P7.22-P7.28 after Phase 4 task 29 is complete.

## P7.23: dependency stop

`ADR-011A` and P2.18 define the answer-save maximum as six database statements:
four business statements followed by the audit predecessor lock and the atomic
event-insert/head-advance CTE. Existing audit integration tests prove those two
audit statement shapes and their internal order, but P7.23 requires observing
them as statements five and six on the production answer-save request.

The dependency check on 2026-10-10 found no production implementation of the
owning `FEAT-DLV-002` feature. `src/main/java/org/meldtech/platform/delivery`
contains package scaffolding only; there is no answer endpoint, handler,
transaction, operation insert or answer upsert. The only `delivery.acceptAnswer`
references are unrelated idempotency fixtures. The executable query-budget
contract registers only `platform.getConformanceReference`, not the answer route.

Creating a synthetic six-call test in the audit module would not establish that
the eventual delivery handler performs exactly four business statements, uses
the production audited transaction, or executes no hidden function, trigger,
advisory lock or later SQL. Implementing `FEAT-DLV-002` is materially outside the
requested P7.23-P7.28 range, so it was not substituted here.

Per execute-tasks, "If dependencies are missing: Stop execution and report
blockers." P7.23 remains open and P7.24-P7.28 were not executed. No build or tests
were run for this documentation-only dependency stop; validation is
`git diff --check`. Resume P7.23-P7.28 after the production answer-save route and
its `maxQueries = 6` contract are available.

## P7.24: dependency stop

P2.19 requires one reusable harness whose reduced profile executes real answer
saves while concurrent epoch closing and disposition faults run. Its recorder
must reject missing measurements, including answer latency and query count,
chain-head lock wait p50/p95/p99, per-shard distribution and skew, audit share
of transaction time, CAS retries, root sequence density and verification output.

The dependency check on 2026-10-10 found no harness or load-driver implementation.
More importantly, the required subjects and signals are unavailable:

- `FEAT-DLV-002` is not implemented, so there is no production answer-save
  endpoint/transaction on which to measure latency, exact six-query execution or
  audit share;
- `audit_chain_head_lock_wait_seconds`, `audit_chain_shard_skew`, seal CAS retry
  and root-sequence metrics appear in design documents but have no production
  metric registration/emission; `AuditSealTelemetry` exposes only a no-op/default
  callback rather than the complete required recorder; and
- the existing concurrency/disposition integration tests prove functional limbs
  with deterministic fixtures, but they are not a reactive load driver and do
  not emit the mandatory smoke-run metric bundle.

Building a driver around mocked answer work or fabricating metric values would
allow the Phase 6 run to pass while measuring nothing, exactly what P7.24 forbids.
Implementing the delivery feature and production telemetry is outside the
requested P7.24-P7.28 range.

Per execute-tasks, "If dependencies are missing: Stop execution and report
blockers." P7.24 remains open and P7.25-P7.28 were not executed. No build or tests
were run for this documentation-only dependency stop; validation is
`git diff --check`. Resume P7.24-P7.28 after the answer path and required metric
producers exist.

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

- `./gradlew integrationTest --tests
  'org.meldtech.platform.audit.infra.AuditDispositionIntegrationTest'
  --rerun-tasks --console=plain`: passed the P7.19 PostgreSQL case.
- `./gradlew test conformanceTest spotlessCheck checkstyleMain checkstyleTest
  checkstyleIntegrationTest --console=plain`: unit, migration-verification and
  conformance tests passed; Spotless and all requested Checkstyle sets passed
  after the single reported line-length violation was corrected.
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
