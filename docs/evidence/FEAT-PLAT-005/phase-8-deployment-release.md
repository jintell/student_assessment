# Phase 8 Deployment and Release Evidence

## P8.1 Migration-before-traffic ordering

Verdict: **PASS**

The retained staging rollback rehearsal enforces the release order rather than
assuming it:

1. The application Deployment starts with zero replicas.
2. `cbt-platform-migration` is created with `backoffLimit: 0` and the dedicated
   `--migrate-only` entrypoint.
3. The rehearsal exits immediately when the Job reports failure or does not
   complete before its timeout.
4. Application replicas are scaled above zero only after the Job reports one
   successful completion and Flyway history confirms the expected migration.

Because the shell runs with `set -euo pipefail`, either Job failure branch ends
the release before the scale operation. No application pod is replaced or
started on that path. The successful staging run is retained in
`P7.17-rollback-rehearsal.md` and its machine-readable companion.

Evidence:

- `ci/rehearse-code-only-rollback`, Job wait and application scale sequence
- `deploy/kubernetes/migration-job.yaml.template`, `backoffLimit: 0` and bounded
  Job deadline
- `docs/evidence/FEAT-PLAT-005/P7.17-rollback-rehearsal.md`

## P8.5 FEAT-OPS-007 interface handover

The freeze decision contract, published manifest fields, and rollback decision
contract are handed over in
`docs/evidence/FEAT-PLAT-005/P8.5-feat-ops-007-handover.md`. The record includes
stable exit codes, fail-closed behavior, required consumer actions, tests, and
the boundary of `FEAT-OPS-007` ownership.

## P8.6 Connection-envelope input

The `ARC-PERF-006` term `migration_job` is **10 connections**, consistently
declared by `CBT_MIGRATION_CONNECTION_ALLOWANCE` in the Job template and the
application default. The completed handover and the exact `A8` test condition
are recorded in `docs/evidence/FEAT-PLAT-005/P3.9-migration-job-envelope.md`.

This makes `ARC-VERIFY-033` executable with the Job active; the envelope gate
and condition `A8` remain owned by `FEAT-OPS-004` and `FEAT-OPS-005`.

## P8.7 Deferral register

| Deferred capability | Owner | Interface or prerequisite delivered here |
|---|---|---|
| Canary rollout, automatic rollback, and production approval gate | `FEAT-OPS-007` | Migration-before-traffic contract, deploy-freeze decision, release manifest, rollback policy, and P8.5 handover |
| Authoritative session-window adapter | `FEAT-EXAM-001` | `SessionWindowQuery` port and fail-closed `UnknownSessionWindowQuery` fallback |
| Business-module migrations and backfills | Each owning feature | Header contract, closed DDL allowlist, module-owned schema rule, stage 12, and `ResumableBackfillHarness` |
| Migration dashboard panels and production alert registration/routing | `FEAT-OPS-004` | Bounded metric contract and the proposed alerts/panel gap record owned by P9.5 |
| Connection-envelope assertion and `ARC-VERIFY-033` execution | `FEAT-OPS-004`, `FEAT-OPS-005` | Ten-connection `migration_job` input and active-Job test condition |

These are ownership deferrals, not local TODOs. `FEAT-PLAT-005` has supplied
the policies, stable interfaces, metrics, evidence, and deployment inputs that
the named features consume.
- `docs/evidence/FEAT-PLAT-005/P7.17-rollback-rehearsal.json`

Ownership boundary: this feature supplies and proves the ordering contract.
Production deployment orchestration that consumes it remains owned by
`FEAT-OPS-007`.

## P8.2 Failed-migration state

Verdict: **PASS**

All migrations currently present in the release manifest are transactional.
Flyway records their result in the module-specific schema-history table, and a
failure returns a non-zero Job result before traffic is enabled. PostgreSQL
therefore leaves a transactional script fully committed or fully rolled back;
the release cannot mistake a failed history row for success.

Future non-transactional migrations are restricted to the concurrently built
index shapes accepted by the closed DDL allowlist. Their known partial state is
an `INVALID` index plus a failed Flyway history row. The recovery contract is:

1. identify the module, failed version, and expected schema-qualified index;
2. take the module/version advisory lock;
3. refuse valid or unattributable indexes;
4. drop only attributable invalid index artifacts with
   `DROP INDEX CONCURRENTLY IF EXISTS`;
5. repair only the failed module history after verifying successful rows; and
6. rerun static analysis before retrying the migration.

`InvalidIndexReconciler` is idempotent when no invalid artifact exists and its
PostgreSQL integration test cancels a concurrent build, observes the invalid
index, reconciles it, and successfully retries. The failed Job remains visible
through Job status and Flyway history throughout; no partial state is treated
as an applied migration.

Evidence:

- `docs/architecture/migration-pipeline-design.md`, P2.6 and P2.7
- `migration-verify/src/main/java/org/meldtech/migrationverify/measure/InvalidIndexReconciler.java`
- `migration-verify/src/test/java/org/meldtech/migrationverify/measure/InvalidIndexReconcilerTest.java`
- `migration-verify/src/test/java/org/meldtech/migrationverify/measure/LockMeasurementHarnessIntegrationTest.java`

## P8.3 Release-manifest artifact

Verdict: **PASS**

The blocking `stage-12-migration` job now publishes
`build/release/release-manifest.json` as the dedicated immutable workflow
artifact `migration-release-manifest-<commit-sha>`. Missing output fails the
upload, and the artifact is retained for 90 days for change-advisory and
rollback consumers.

The manifest identifies the release and its sole classification, the retained
previous image digest, the migration-set checksum, and the path, phase,
transaction mode, and checksum of every included migration. Publication occurs
only after stage 12 succeeds, so consumers cannot receive a manifest for a
release that failed migration verification.

Evidence:

- `.github/workflows/ci.yml`, `Publish migration release manifest`
- `build.gradle.kts`, `generateReleaseManifest`
- `migration-verify/src/main/java/org/meldtech/migrationverify/release/ReleaseManifestGenerator.java`

## P8.4 Feature rollback statement

This feature's gates, analyser, dataset generator, reports, templates, and
operator tooling are build-time or deployment-time code. Rolling back those
changes is a source/image revert and requires no data operation.

That statement does not authorize schema rollback. An applied `EXPAND` release
is followed by a forward fix when defective, and a release classified
`CONTRACT` is never rolled back. `RollbackPolicy` enforces the latter with the
stable `CONTRACT_ROLLBACK_FORBIDDEN` refusal and a forward-fix instruction.

The code-only staging rehearsal confirms the boundary: release N was restored
by changing the application image, the schema fingerprint was unchanged, and
the migration Job was neither recreated nor replaced.

Evidence:

- `src/main/java/org/meldtech/platform/platform/deployment/RollbackPolicy.java`
- `docs/evidence/FEAT-PLAT-005/P7.17-rollback-rehearsal.md`
