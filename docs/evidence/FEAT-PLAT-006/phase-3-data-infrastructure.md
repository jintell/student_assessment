# FEAT-PLAT-006 Phase 3 Data and Infrastructure

## P3.5-P3.10 Execution on 2026-10-12

Current range: P3.5-P3.10. P3.1-P3.4 were already complete on entry.
Completed this run: P3.5-P3.10. No remaining or blocked task in this range.

### P3.5 - Dedicated Lock Pool

The worker is partitioned into three distinct named, secured, traced R2DBC
pools: `workerConnectionFactory` / `worker` (4),
`gradingConnectionFactory` / `grading` (4), and
`sweepLockConnectionFactory` / `sweep-lock` (2). Configuration rejects pool
sizes outside this approved allocation, a different login role, and nested
pooled URLs. Existing secret-tree credentials and external database URL are
reused. No physical connections are added to the ten-connection allowance.
The background factory remains primary, preserving Boot's default worker
`DatabaseClient`; a regression test proves it never selects a reserved pool.

`docs/runtime-connection-pools.md` registers this allocation for P8.5 and
documents qualifiers and Phase 4 adapter/shutdown obligations. P8.5's envelope
proof remains its own task.

Verification passed: compileJava, compileTestJava, the pool configuration unit
suite, and `WorkerPoolIsolationIntegrationTest`. The PostgreSQL test holds two
session advisory locks, confirms a third lock-pool borrower must wait, and
still acquires all four grading plus four background connections. Ten unique
backend PIDs prove physical isolation. The test inspects physical allocation;
it does not claim that Phase 4 lock/context adapters are implemented.

### P3.6 - Per-Role Deployment Values

`deploy/runtime/values.yaml` provides profiles, observability role, exact
baseline resources/replicas/HPA bounds, separate pool budgets and session
affinity None. API scales on CPU and exam-path p95, worker on queue depth,
and pindist has no HPA. Grading prefetch/concurrency are recorded independently
of existing outbox consumers. P2.11 heap recommendations are explicitly labelled
in `docs/runtime-deployment-values.md`; HPA thresholds remain required external
inputs because no numeric targets are specified by the architecture.

`RuntimeDeploymentValuesTest` passed, checking role/profile identity, one shared
image input, fixed/elastic scaling boundaries and equality of the deployment
pool budgets with the actual profile configuration. The renderer and staging
verification remain Platform Ops/P7.17 responsibilities.

### P3.7 - Dedicated Workload Identity

The `cbt-pindist` ServiceAccount template and role values use a distinct
namespace-qualified federation subject. Automatic API token mounting is off.
Platform Ops supplies the provider binding/projected token; FEAT-EXAM-002
binds the retrieval KEK grant to this identity. No KMS grant or Kubernetes
RoleBinding is made here. `RuntimeDeploymentValuesTest` passed, including
three distinct accounts and agreement between values and the template.

### P3.8 - Ingress-Only PIN Distribution

The NetworkPolicy template selects pindist and allows only ingress-controller
pods in a separate controlled namespace on TCP 8080. Both selectors share one
peer; no API/worker peer, namespace-wide allow or management-port allow exists.
The values tests passed, asserting the complete policy structure. Deployment
requires an enforcing CNI, protected ingress labels and no broader additive
policy. These requirements and the distinction between source filtering and
HTTP authorization are documented; live network proofs remain P6.3/P7.13.

### P3.9 - Shutdown Values

Common values supply the drain marker hook, 30-second preStop, 60-second grace,
graceful server shutdown and five-second lifecycle timeout. A writable pod-local
emptyDir and non-root group support the hook. The values tests passed and check
the complete arithmetic: 30 + (4 x 5) + 5 cleanup + 5 margin = 60 seconds.
The marker reader, actual ordered lifecycle and measured timing remain Phase 4
and P7.16; supplying values does not claim a completed shutdown implementation.

### P3.10 - One Runtime Artifact

`runtimeImage` builds the shared bootJar through one role-neutral Dockerfile.
`bootBuildImage` delegates to this same build and skips its original action.
There are no role-specific targets, build arguments, JARs or image values.
The role-neutral Java 21 base and SBOM scanner are immutable-digest pinned.
A dedicated BuildKit builder resolved the local Docker driver's lack of
attestation support without changing the user's default builder.

The actual linux/amd64 OCI build and verifier passed. It contains one runnable
manifest and one SPDX attestation, not three role artifacts:

```text
image/index: sha256:15aa8e5692cfeeab80ba976cab57a874a4e83ff633968616899f64f6a2ea7202
runtime:     sha256:410d7313b0e282dfb50bcda9e77dd0af986778a0eb6caed644e05d290db33b52
SPDX file:   sha256:56f76af487a2803ad1479e847f24d181466afdf86bb25440232df1aa6d75621f
```

Generated evidence is in `build/runtime-image/{oci,metadata.json,image.json,sbom.spdx.json}`.
The verifier checks metadata hashes, a single runnable image/SBOM, matching
attestation subject, non-root entry point and no baked profile/JVM override.
The contract test passed its positive fixture and six negative cases. The
deployment contract tests, compileJava, compileTestJava and full root test suite
also passed. `docs/runtime-image.md` documents reproduction and promotion.

No image was published or signed; those are P8.3. No running three-role system,
route isolation or completed lifecycle is claimed ahead of Phase 4/P7.10.
Next implementation chunk starts at P4.1 with the existing Phase 4 prerequisites.

### Final Verification

- `compileJava compileTestJava`: passed.
- Full root `:test`: 471 tests, no failures/errors/skips.
- Full `conformanceTest`: 56 tests, no failures/errors/skips.
- Targeted `integrationTest`: 31 tests across WorkerPoolIsolation,
  PersistenceFoundation and PersistenceSecurityGates, no failures/errors/skips.
- `runtimeImageContractTest`: one valid and six rejected fixtures passed.
- `bootBuildImage`: executed `runtimeImage` and skipped the original buildpack
  action; final code produced the image and SBOM hashes above.
- `spotlessCheck`, shell syntax checks, documentation conformance and
  `git diff --check`: passed.

The complete integration suite and `clean build` were not run. No live
Kubernetes, cloud identity/KEK or registry-promotion validation was performed.
Existing user changes were preserved; no commit or publication was made.

## Prior P3.1-P3.4 Execution

Date: 2026-10-11. Requested range: `P3.1-P3.10` in
`tasks/foundation/runtime/tasks.md`. Source plan:
`../student_assessment/workspace/v3/be/plan/plan.md`, section 8.1.
Execution order is strict; completion markers are persisted after verification.

## P3.1 - Registry Migration

Status: COMPLETE.

The signed `P0.6` registry/budget approval was verified before authoring the
migration. `platform/V9__create_sweep_registry.sql` creates the nine approved
columns, the primary and unique lock keys, name/feature syntax constraints,
positive interval and ordered timestamps. The migration runs as
`app_migrator`, which owns the new table. It adds no tenant column, role,
runtime grant, seed row or sweep implementation.

The release manifest includes V9. The synthetic volumetric profile records
the new registry as absent and empty before EXPAND. Later P3.2 supplies the
six reserved rows; this control table needs no production-derived dataset.

`PersistenceFoundationIntegrationTest.sweepRegistryHasApprovedOwnershipAndConstraints`
verifies the real PostgreSQL owner, nine columns, absence of tenant scope,
disabled-by-default behavior, timestamps, duplicate-key rejection and invalid
name, feature, interval and timestamp rejection. Transaction rollback removes
all test rows.

## P3.2 - Seed Migration

Status: COMPLETE.

`platform/V10__seed_sweep_registry.sql` reserves the six P2.4 lock keys with
the P1.3 owning features and system actors. It uses the approved 60000 ms
placeholder for the three unspecified cadences and leaves every deployed row
disabled. The enabled reference sweep is deliberately test-only under P4.26;
it is not a seventh production identity.

The migration uses `ON CONFLICT (sweep_name) DO NOTHING`. A rerun therefore
adds a missing reservation without resetting an existing feature-owned row.
`sweepRegistrySeedReservesExactDisabledIdentitiesAndIsRerunnable` verifies the
six exact records against PostgreSQL, replays V10, and proves the result is
unchanged.

The former policy conflict is resolved by
`ADR-019-static-reference-seed-amendment.md` and the dual-signed
`P3.2-static-reference-seed-allowlist-approval.json`. The analyser recognizes
only the exact table, columns, six literal rows and do-nothing conflict action.
Five mutation tests prove that a changed key, enabled flag, owner, row set or
conflict action remains forbidden DML. Stage 12 verifies the document and
migration hashes plus distinct Platform Ops and Engineering Lead signatures.

Verification passed:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.migration.PersistenceFoundationIntegrationTest.sweepRegistrySeedReservesExactDisabledIdentitiesAndIsRerunnable' \
  :migration-verify:check spotlessCheck --console=plain

CBT_IMAGE_REPOSITORY=ghcr.io/jintell/cbt-platform \
CBT_PREVIOUS_IMAGE_DIGEST=sha256:371688cb13086e3f86a67b5705c7f4b8c4e2eaa1ed22c02abc980e8bccc0c384 \
  ./ci/stage-12
```

Stage 12 passed all signed approvals, analyser tests, PostgreSQL migration and
dataset checks, lock thresholds, and retained-image N-1 compatibility for the
thirteen-statement release set.

## Execution Summary

- Requested: P3.1-P3.10.
- Completed: P3.1-P3.2, including the registry, six immutable reservations,
  signed policy amendment, PostgreSQL rerun proof and passing stage 12.
- Remaining: P3.3-P3.10. Strict execution resumes at P3.3.
- Risk: runtime grants, scheduling, lock pools and deployment controls are not
  delivered by this chunk. A4, A8 and staging proof remain outside this claim.
- The pre-existing untracked provisioning signature was preserved.

The active V10 seed is included in the release manifest and passes the closed
migration analyser under its hash-bound, exact-shape approval.

## P3.3 - Non-Tenant Table Classification

Status: COMPLETE. P3.4-P3.10 are not started.

`db/security/table-scope-catalogue.json` now lists every ordinary non-tenant
table in the `platform` schema with its owning feature and reviewed reason.
The `platform.sweep_registry` entry records it as platform-wide scheduler
identity and lock-key data owned by `FEAT-PLAT-006`.

The PostgreSQL catalogue gate still discovers tenant scope mechanically from
the presence of `tenant_id`. Its new complementary check requires every
platform base table without that column to have a reviewed catalogue entry,
rejects a catalogued table that gains `tenant_id`, and rejects entries for
tables that do not exist. The classification therefore creates no RLS bypass.
`docs/tenant-scoped-tables.md` explains the contract, and the completed
`FEAT-PLAT-002 P1.8` task carries the requested runtime addendum.

Verification passed:

```text
./gradlew spotlessApply integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.everyTenantTableHasTheStrictForcedRlsPolicy' \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.platformTableScopeGateRejectsAnUncataloguedNonTenantTable' \
  --console=plain
```

The positive test classifies the migrated registry on PostgreSQL. The negative
test creates an uncatalogued non-tenant platform table inside a transaction,
observes `TABLE_SCOPE_CATALOGUE`, rolls it back, and proves the gate is clean
again.

## P3.4 - Registry Grant

Status: COMPLETE. P3.5-P3.10 are not started.

`platform/V11__grant_sweep_registry_read.sql` grants `USAGE` on `platform` and
`SELECT` on `platform.sweep_registry` directly to `app_worker`. This is the
closed exception to the pool-login direct-grant rule; the grant-matrix loader
accepts only those exact privilege facts. Its generated repeatable migration
and live grant-diff gate prevent any wider grant from drifting in.

No role is created. `app_api` and `app_pindist` retain their absolute
no-direct-object-grant denials. The worker has no registry write privilege,
and `pg_try_advisory_lock` remains available through PostgreSQL's built-in
function privileges without an application object grant.

The dedicated `app_sweep_registry_reader` proposal was reviewed and rejected
because it contradicts P3.4's no-new-role acceptance criterion. The review is
recorded in `proposals/registry-reader-resolution.md`; the former direct-grant
SQL proposal is superseded by the active V11 migration.

Verification passed:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.sweepRegistryIsReadableOnlyByTheBackgroundWorkload' \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix' \
  --console=plain

./gradlew compileJava compileTestJava test spotlessCheck --console=plain

CBT_IMAGE_REPOSITORY=ghcr.io/jintell/cbt-platform \
CBT_PREVIOUS_IMAGE_DIGEST=sha256:371688cb13086e3f86a67b5705c7f4b8c4e2eaa1ed22c02abc980e8bccc0c384 \
  ./ci/stage-12
```

The PostgreSQL test proves the exact worker SELECT grant, six-row read,
read-only boundary, advisory-lock call, absence of a new role, and denied API
and PIN-distribution reads. P3.4 is closed; strict execution resumes at P3.5.
