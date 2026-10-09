# Audit testing evidence

Requested range: P7.1 through P7.14. Execution date: 2026-10-09.
Tasks are verified and recorded sequentially. A6 and A7 remain Phase-6-gated.

## Execution summary

- Completed: P7.1-P7.14; each marker persisted after validation.
- Resolved: P7.11's missing P4.18-P4.19 production persistence dependency.
- Requested range P7.1-P7.14 is complete.
- Deliverables: codec determinism, chain tamper and distribution tests; fixed
  root expectations; independent conformance fixtures; PostgreSQL rollback,
  contention and append-protocol tests; this evidence record and task markers.
- Production seal persistence and its PostgreSQL concurrency test were added;
  migration files were not changed.
- Next open task: P7.15.

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

## P7.11: concurrent root CAS append

`R2dbcAuditEpochSealRepository` closes the missing production dependency. It
loads the persisted tenant root and complete shard topology, obtains PostgreSQL
time, and uses one atomic data-modifying CTE to conditionally advance the exact
observed `(root_seq, root_head_hash)` and insert the seal only for that winner.
Zero changed rows is the explicit CAS-loss result consumed by the sealer retry
path; partial head/seal state cannot commit.

`AuditEpochSealConcurrencyIntegrationTest` provisions two epochs for one tenant
in migrated PostgreSQL and holds both first signing attempts at a barrier so they
derive from the same predecessor. The observed outcomes are two successful
commits and exactly one failed CAS, three signatures, and one telemetry retry.
Persisted seals have dense sequences 1 and 2; sequence 1 starts at the zero root,
sequence 2 names sequence 1's root exactly, and the single tenant head equals the
sequence-2 root. These checks exclude a sibling or orphan root.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditEpochSealConcurrencyIntegrationTest'
--console=plain` passed. Provider KMS authorization and full-load A6/A7 evidence
remain separate outstanding obligations.

## P7.12: post-signature kill and restart

`AuditEpochSealConcurrencyIntegrationTest` injects termination when the signed
seal reaches the repository boundary, before the production CAS statement can
run. The failed worker signed exactly once but persisted no seal: the tenant root
remained at sequence zero with its zero hash and unset `sealed_at`.

The test then constructs a fresh production repository and sealer for the same
epoch. Restart re-reads sequence zero, re-derives, and requests a second signature
instead of reusing process-local evidence. PostgreSQL contains exactly one seal at
sequence 1, its predecessor is the zero root, its request ID is the second signing
request, and the tenant head equals that epoch root. Thus there is no gap,
duplicate, orphan or cached-signature replay.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditEpochSealConcurrencyIntegrationTest'
--rerun-tasks --console=plain` passed both root-sealing integration cases. This is
the non-load kill-point limb of `ARC-VERIFY-031`; it does not discharge A6's
Phase-6 load obligation.

## P7.13: canonical close-order determinism

`AuditEpochSealConcurrencyIntegrationTest` provisions the same six epochs for
two fresh tenants, spanning three UTC periods and all four retention classes.
Each run submits a different non-canonical permutation to the production
`AuditEpochCloser`, which serializes the real PostgreSQL sealer in the approved
period-first, retention-class-second order.

Both persisted runs produce the identical epoch-to-`root_seq` mapping. The test
asserts the complete canonical epoch order, dense sequences 1 through 6 and zero
CAS retries, establishing restart/input-order determinism at the database level
rather than only exercising the comparator.

Validation: `./gradlew spotlessJavaApply integrationTest --tests
'org.meldtech.platform.audit.infra.AuditEpochSealConcurrencyIntegrationTest'
--rerun-tasks --console=plain` passed all three root-sealing integration cases.

## P7.14: fail-closed verifier behaviour

`AuditFullVerifier` now routes every retained-chain or tenant-root
`AuditVerificationMismatch` through an `AuditVerificationFindingCapture` and the
existing `AuditIntegrityFailureHandler`. The capture port supplies immutable
snapshot, database-LSN and affected-identity metadata; handling then preserves
the finding, records the high-severity metric, raises P1, halts sealing and
disposition for the tenant, and re-propagates the original mismatch.

`AuditFullVerifierTest` adds five distinct verifier cases: broken predecessor,
missing root sequence, invalid signature, non-reproducing epoch root and sibling
roots at one sequence. Every case asserts the complete ordered failure response,
an emitted finding with snapshot evidence, tenant halt and terminal error. Each
also compares the full input evidence before and after and verifies the
preservation port exposes no repair or reconciliation operation.

Validation: `./gradlew spotlessJavaApply test --tests
'org.meldtech.platform.audit.application.AuditFullVerifierTest' --rerun-tasks
--console=plain` passed six cases: the intact quarterly/post-restore control and
all five required failures.

## Final verification

- `compileJava compileTestJava`: passed.
- Full `test`: 434 root-project tests and 66 migration-verification tests passed,
  zero failures, errors or skips (495 total).
- Targeted `AuditFullVerifierTest`: six passed, including all five fail-closed
  verifier cases; zero failures or skips.
- Targeted `integrationTest` for `AuditAppendIntegrationTest`,
  `AuditStoreHardeningIntegrationTest`, root-CAS concurrency and post-signature
  restart plus canonical multi-epoch reruns passed with zero failures or skips.
- Full `conformanceTest`: 56 passed, zero failures, errors or skips. The
  `R2dbcComplianceAuditQueries` adapter now lives under the compliance slice's
  `infra` package, so its dependency direction satisfies R1. Its row-mapping and
  binding helpers are isolated in a non-query support type, leaving the
  `TenantScopedQuery` implementation with only `find(TenantId, ...)`, satisfying
  R5 without weakening either conformance rule.
- Four long SQL lines found by Checkstyle were corrected before the final
  PostgreSQL rerun; no test semantics were relaxed.
- Final `spotlessCheck`, `checkstyleTest`, `checkstyleIntegrationTest` and
  `checkstyleConformanceTest`: passed.
- `git diff --check`: passed. No generated content was added to version control.

The full build and pipeline are not claimed green; previously documented
compliance/KMS dependencies remain. No task outside P7.1-P7.14 was marked
complete by this run.

## Conformance failure closure

Closed 2026-10-09. The failure was an adapter-boundary defect introduced during
the P6.7 prerequisite repair, not a defect in R1 or R5. Moving the adapter beneath
`audit.slice.getComplianceAuditEvents` makes its imports internal to that slice.
Extracting only stateless SQL binding and row mapping into
`ComplianceAuditQuerySupport` keeps the query implementation's complete method
surface tenant-explicit. SQL remains on the adapter's `find` method, preserving
R3 schema-ownership inspection.

The relocated seven adapter unit cases and all 518 focused PostgreSQL hardening
cases pass. Compilation, `spotlessCheck`, main/test/integration/conformance
Checkstyle, the 56-test conformance suite and `git diff --check` pass. No
conformance assertion or production query behavior was relaxed.
