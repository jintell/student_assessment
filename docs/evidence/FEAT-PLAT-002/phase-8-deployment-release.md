# Phase 8 Deployment and Release Evidence

## P8.1 Migration Before Traffic

Verdict: **PASS**

The retained staging rollback rehearsal proves the required order:

1. The application Deployment is applied with zero replicas.
2. Kubernetes runs `cbt-platform-migration` as a bounded `Job` using the
   dedicated `--migrate-only` entrypoint and `app_migrator` identity.
3. The rehearsal aborts on Job failure or timeout and verifies the successful
   Flyway history row.
4. Only after those checks does it scale the application Deployment above
   zero and wait for readiness.

`ci/rehearse-code-only-rollback` keeps the Job success and schema-history
checks before the first application scale operation. Its retained successful
run is recorded in
`docs/evidence/FEAT-PLAT-005/P7.17-rollback-rehearsal.md` and the accompanying
JSON report.

Application replicas hold no DDL path:

- `application.yaml` disables Flyway globally and binds the `api`, `worker`,
  and `pindist` profiles only to `app_api`, `app_worker`, and `app_pindist`.
- The migration Job is the only deployment template mounting the migrator
  secret and passes the literal `app_migrator` username.
- `MigrationApplication` rejects any other identity for `--migrate-only`, and
  its tests prove serving profiles cannot assume `app_migrator`.
- The canonical grant matrix gives the three serving login roles no direct
  object grants and no DDL capability.

Evidence rerun on 2026-09-27:

- `MigrationApplicationTest`: PASS
- `ci/rehearse-code-only-rollback` shell syntax and migration-before-scale
  sequence check: PASS

This feature supplies and proves the ordering and privilege contract.
Production rollout orchestration that consumes it remains owned by
`FEAT-OPS-007`.

## P8.2 Workload to Role Verification

Verdict: **PASS**

| Workload | Profile | Pool allocation per replica | Login role | Permitted membership |
|---|---|---|---|---|
| `cbt-api` | `api` | 6 general plus 8 exam-path connections, total 14 | `app_api` | Twelve module roles plus the enumerated `app_txn_examentry` composite role |
| `cbt-worker` | `worker` | 10 connections | `app_worker` | Twelve module roles; no composite role |
| `cbt-pindist` | `pindist` | 5 connections | `app_pindist` | `app_examaccess` only |

`application.yaml` binds each profile and every pool it enables to exactly the
login role in this table. The API reservation is implemented as separate six-
and eight-connection pools, not as a soft limit inside one pool.

`grant-matrix.json` is the closed authorization source. Its membership rows
contain only the sets above, and its `NO_DIRECT_OBJECT_GRANTS` denials cover all
three login roles. PostgreSQL roles use `NOINHERIT`, so every access requires an
explicit transaction-local assumption; an attempt to assume a role outside
the login identity's membership is rejected by PostgreSQL.

Evidence rerun on 2026-09-27:

- `WorkloadConnectionPoolConfigurationTest`: PASS
- `PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix`
  against PostgreSQL 17: PASS

This mapping is the deployment input consumed by `FEAT-PLAT-006`; that feature
owns the three profile-selected workload manifests and their role-isolation
verification.

## P8.3 Rollback Statement

Verdict: **PASS**

Persistence-foundation changes are additive. Application rollback changes only
the deployed image or ReplicaSet and leaves schemas, roles, memberships,
grants, default privileges, RLS policies, and migrated data in place. No
rollback path drops a schema or table, reverses a migration, or removes a role
that may own or access persisted data.

If a role or grant is later obsolete, its revocation is a separate forward
migration. That migration requires the normal ownership, compatibility,
security, and deployment review; it is never smuggled into an application
rollback command.

The retained `P7.17` rehearsal reverted only the Deployment image, created no
rollback migration Job, and recorded identical schema fingerprints before and
after rollback. Release N then passed its read, write, read-back, and persisted
invariant probes against release N+1's expanded schema.

This contract is the persistence contribution to `ARC-OPS-006`. Expand,
migrate, and later contract sequencing remains governed by `FEAT-PLAT-005`.

## P8.4 Connection Envelope Input

Verdict: **PASS FOR FEATURE CONTRIBUTION**

The retained input report at
`docs/evidence/FEAT-PLAT-002/P8.4-connection-envelope-input.md` publishes the
pool figures contributed by this feature and reproduces all four
`ARC-PERF-006` figures at baseline and simultaneous workload ceilings.

CI stage 4a limb (b) is the required inequality enforcement point owned by
`FEAT-OPS-004` and `FEAT-OPS-005`. This feature hands off verified inputs; it
does not claim their downstream deployed-manifest gate or `ARC-VERIFY-033`
execution.
