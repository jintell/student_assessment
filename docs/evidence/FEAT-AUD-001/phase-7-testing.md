# Audit testing evidence

Requested range: P7.1 through P7.14. Execution date: 2026-10-09.
Tasks are verified and recorded sequentially. A6 and A7 remain Phase-6-gated.

## Execution summary

- Completed: P7.1-P7.10; each marker persisted immediately after validation.
- Blocked: P7.11, before implementation, on missing P4.18-P4.19 persistence.
- Not started: P7.12-P7.14, preserving strict sequential execution.
- Deliverables: codec determinism, chain tamper and distribution tests; fixed
  root expectations; independent conformance fixtures; PostgreSQL rollback,
  contention and append-protocol tests; this evidence record and task markers.
- Production code and migration files were not changed.
- Resume: supply the missing seal persistence adapter, then P7.11-P7.14.

## P7.1: canonical golden vectors

`CanonicalJsonGoldenVectorTest` checks all four committed vectors in
`config/audit/canonical-json-v1-golden-vectors.json`, asserting exact UTF-8 bytes
and SHA-256 digests for ordering, NFC, UTC microseconds, decimals and null/absence.
`CanonicalJsonCodecTest` additionally rejects sub-microsecond timestamps.

Validation: `./gradlew compileJava compileTestJava test --tests
'org.meldtech.platform.audit.domain.CanonicalJson*Test' --console=plain` passed.

## P7.2: ambient-state and restart determinism

`CanonicalJsonDeterminismTest` launches isolated JVMs with Pacific/Auckland and
Turkish defaults, verifies those defaults inside the child, and compares exact
canonical bytes to a fixed expectation. Two successive fresh JVMs establish
restart reproducibility. Each subprocess has a timeout and is cleaned up.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.domain.CanonicalJsonDeterminismTest' --console=plain`
passed all three tests.

## P7.3: ARC-VERIFY-010 PostgreSQL integration limb

The existing `AuditStoreHardeningIntegrationTest` separately asserts SQLSTATE
42501 for UPDATE/DELETE across every application/composite role and SQLSTATE
23000 for the immutable trigger after temporarily granting mutation privileges.
The trigger tests also verify the 24 fixture records survive unchanged in count.
Both use the approved PostgreSQL Testcontainer and real migrations.

Validation: `./gradlew integrationTest --tests
'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest.everyApplicationRoleLacksMutationPrivileges'
--tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest.immutableTriggerRefusesMutationEvenWithPrivileges'
--console=plain` passed.

## P7.4: ARC-VERIFY-010 coverage rule

`R8AuditCoverageTests` rejects the mutating handler fixture without emission,
accepts the fixture calling the actual kernel `AuditEmitter` port, and checks
production handlers. This suite is part of the blocking conformance task.

Validation: `./gradlew conformanceTest --tests
'org.meldtech.platform.conformance.R8AuditCoverageTests' --console=plain` passed.
## P7.5: transaction rollback symmetry

`AuditAppendIntegrationTest` uses the production R2DBC emitter, append adapter,
secured collaboration transaction and migrated PostgreSQL database. A duplicate
audit-event ID produces SQLSTATE 23505 and rolls back the second business row.
A business failure after successful append rolls back both event and business row.
Both tests also compare the persisted sequence/hash head before and after failure.
A successful commit establishes the positive control.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditAppendIntegrationTest' --console=plain`
passed both tests.

## P7.6: independent prohibited-emission fixtures

`AuditAtomicAppendConformanceTests` now tests REQUIRES_NEW, separate-connection
and async emission independently, each with a fixture containing only its own
violation and an assertion of the matching diagnostic. Production protocol
conformance still passes.

Validation: `./gradlew spotlessJavaApply conformanceTest --tests
'org.meldtech.platform.conformance.AuditAtomicAppendConformanceTests'
--console=plain` passed all four tests.

## P7.7: ADR-011A atomic append

`AuditAppendIntegrationTest` captures business INSERT, predecessor SELECT FOR
UPDATE, and the final CTE in that order, with exactly two audit statements.
Two same-shard writers are forced to overlap: the first holds its lock until
`pg_stat_activity` confirms the second is waiting on a PostgreSQL lock. Committed
rows have sequences 1 and 2, the approved seed and exact predecessor/hash links,
and a matching final head; exact row counts exclude sibling/orphan records.
Independent failures after lock, after CTE and before commit roll back all state.
Later business SQL is refused by the production finalization guard and also rolls
back the append. Test-only decorators forward to production adapters and guards.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditAppendIntegrationTest' --console=plain`
passed all eight cases, including P7.5.

## P7.8: chain tampering

`AuditChainWalkTest` first verifies an intact two-record chain against its
committed head, then independently detects payload substitution, record reordering,
and predecessor substitution with the corresponding verification mismatch.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.domain.AuditChainWalkTest' --console=plain` passed
the positive control and all three tamper tests.

## P7.9: shard affinity and distribution

`AuditShardAssignmentTest` checks related creation/submission events share their
entity's shard. A deterministic 64,000-entity sample over 64 shards measured
minimum 941, maximum 1,071, mean 1,000 and maximum/mean skew **1.071**.
The test requires every shard above 850 records and maximum/mean below 1.15;
JUnit retains the measured figures as test properties. This is an algebra sample,
not a load-profile claim or discharge of A6.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.domain.AuditShardAssignmentTest' --console=plain`
passed all three tests.

## P7.10: populated and sparse epoch roots

`EpochRootDerivationTest` asserts fixed hashes for fully populated and sparse
three-shard epochs, shuffled input reproducibility, exact counts and sequence
bounds/nulls. The fixed expectations were independently calculated using Python
stdlib SHA-256, sorted compact JSON and big-endian uint64, from the P2.7 formula
in `phase-2-architecture-design.md`, including the domain-separated empty-shard
sentinel. The sparse expected hash is
`901314c825a7f7db5213da39367e138ea740fccaf6d7a8701bf12711fe602862`;
the populated expected hash is
`d8ebf9975cb673a06bf971522fb7f4534bc62d3e1fd129e4d571b6394721f4a8`.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.domain.EpochRootDerivationTest' --console=plain`
passed both tests.

## P7.11 dependency review

`src/main/java/org/meldtech/platform/audit/application/AuditEpochSealRepository.java`
declares `insertSealAndCompareAndSwap`, but has no production implementation.
`AuditEpochSealer.commit` delegates the atomic persistence operation to this port.
The only implementations are `RecordingSealRepository` and `RetryingSealRepository`
inside `AuditEpochSealerTest`; they return synthetic outcomes. No production SQL
inserts a seal and advances the tenant root head atomically. This leaves the
P4.18-P4.19 dependency incomplete despite its existing completion markers, which
are outside this execution range and were left untouched.

The requested concurrency proof needs the actual atomic commit/rollback path.
A new in-memory fake would test invented persistence behavior and would not
establish that a losing insert rolls back or that persisted roots cannot fork.
The missing implementation therefore blocks P7.11; P7.12-P7.14 were not executed.
This follows execute-tasks: "If dependencies are missing: Stop execution and
report blockers." It is a dependency stop, not an approval request.

Prerequisite: implement the production port's read/material/time operations and
atomic seal INSERT plus root-head CAS, including loser rollback and retry inputs,
under P4.18-P4.19. Then resume the requested range at P7.11. Actual provider KMS
authorization and full-load A6/A7 evidence remain separate outstanding obligations.

## Final verification

- `compileJava compileTestJava`: passed.
- Full `test`: 429 root-project tests and 66 migration-verification tests passed,
  zero failures, errors or skips (495 total).
- Targeted `integrationTest` for `AuditAppendIntegrationTest` and
  `AuditStoreHardeningIntegrationTest`: 526 cases passed, zero failures or skips.
- Full `conformanceTest`: 54 passed, two failed. The requested R8 audit coverage
  and audit append protocol suites pass. The failures are pre-existing in
  `R2dbcComplianceAuditQueries`: R1 rejects imports from the compliance slice,
  and R5 flags its `required`, `map` and `bind` helpers without TenantId parameters.
  `git diff --exit-code HEAD` confirmed this adapter and both failing rule tests
  are unchanged. Their owning P4.29 task was already reopened before this run.
- Four long SQL lines found by Checkstyle were corrected before the final
  PostgreSQL rerun; no test semantics were relaxed.
- Final `spotlessCheck`, `checkstyleTest`, `checkstyleIntegrationTest` and
  `checkstyleConformanceTest`: passed.
- `git diff --check`: passed. No generated content was added to version control.

The full build and pipeline are not claimed green: the two conformance failures,
missing seal persistence and previously documented compliance/KMS dependencies
remain. No task outside P7.1-P7.14 was marked or repaired.
