# Phase 6 Security and Hardening Evidence

## P6.1 - Application-Layer Isolation Is Insufficient Alone

`PersistenceFoundationIntegrationTest.missingTenantPredicateLeaksForeignRowsWhenRlsIsDisabled`
runs only against the repository's ephemeral PostgreSQL 17 Testcontainer. Inside a rollback-only
transaction it inserts rows for two tenants, disables RLS on the conformance probe, assumes
`app_migrator`, and demonstrates that an unfiltered query sees both rows. This is the failure mode
prevented at the application layer by the R5 tenant-parameter signature rule.

The transaction is always rolled back in a `finally` block. A post-rollback catalogue assertion proves
that both `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` remain active on the probe table.

## P6.2 - Forced RLS Is the Database Backstop

`PersistenceFoundationIntegrationTest.forcedRlsHidesForeignRowWhenTenantPredicateIsOmitted` installs
tenant A's transaction-local context and queries directly for tenant B's probe identifier without a
`tenant_id` predicate. PostgreSQL returns a count of zero. The test exercises the real forced policy and
therefore supplies the `ARC-VERIFY-005` backstop evidence independently of the R5 signature rule.

## P6.3 - Blocked by Authorization Evaluator

Status: blocked; task remains open.

`FEAT-IAM-003` has not delivered the object-level tenant evaluator or a tenant-owned HTTP resource to this
repository. The current conformance reference `Policy` intentionally returns `DENY`, and its endpoint test
correctly asserts `403`. That surface cannot prove the required cross-tenant `404` status and response body.
Implementing a substitute evaluator in this persistence feature would cross the ownership boundary named by
P6.3, so execution stops here pending `FEAT-IAM-003`.

## P6.4 - No Generic Tenant-Filter Bypass

Status: PASS (2026-09-26).

The production sources, configuration, and deployment templates contain no tenant-bypass flag, no setting
that disables row-level security, and no test hook exposed through a non-test profile. The review exercised
each plausible bypass rather than treating absence of a known flag as sufficient:

| Attempt | Refusal evidence |
|---|---|
| Supply a flag or configuration property that skips tenant filtering | No bypass, disable, skip, ignore, unscoped, or no-tenant setting exists in production configuration or deployment manifests |
| Open a transaction without tenant context | `SecurityContextInitializer` refuses transaction start with `R9_CONTEXT_NOT_FIRST` |
| Use platform scope as an unrestricted substitute for tenant scope | `PlatformOperation` is a closed enum; `SecurityContextInitializer` rejects tenant-bearing actors and actors not explicitly permitted for the selected operation |
| Disable RLS from application code | No production source issues `DISABLE ROW LEVEL SECURITY` or changes the `row_security` setting |
| Give an application role PostgreSQL's `BYPASSRLS` attribute | The canonical grant audit requires `bypassRls=false` for every declared role; the migrator bootstrap also states `NOBYPASSRLS` explicitly |
| Reach a test-only bypass from a serving profile | The sole deliberate RLS-disable probe is under `src/integrationTest` and is absent from the main runtime artifact |

The platform-scope marker is therefore an enumerated, actor-authorized scope with its own transaction-local
setting. It is not a path that installs no filter. This discharges the `ARC-TEN-003` bypass-attempt review.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest'
rg -n -i \
  '(tenant.{0,30}(bypass|disable|skip|ignore|unscop)|bypass.{0,30}tenant|disable row level security|row_security\\s*=|bypassrls|no.?tenant|without.?tenant)' \
  src/main src/main/resources config deploy
```

## P6.5 - Composite-Role Exclusions

Status: PASS (2026-09-26).

`PersistenceSecurityGatesIntegrationTest` queries the migrated PostgreSQL catalogue as the cluster owner and
retains a separate assertion for every deliberate exclusion:

| Exclusion | Assertion |
|---|---|
| No `UPDATE` or `DELETE` on `delivery.answer` or `delivery.answer_operation` | `examEntryRoleCannotUpdateOrDeleteAnswerTables` |
| No write on `people`, `authoring`, or `tenancy` | `examEntryRoleCannotWriteReferenceSchemas` |
| No access to `grading`, `result`, `correction`, `notification`, `questionbank`, `iam`, or `platform` | `examEntryRoleCannotReachExcludedSchemas` |
| No `UPDATE` or `DELETE` on `audit.*` | `examEntryRoleCannotUpdateOrDeleteAuditRecords` |

These focused assertions run beside `liveDatabaseGrantsExactlyMatchTheDeclaredMatrix`, which rejects any
extra live grant. Together they prove both the named exclusions and the absence of undeclared privilege.

## P6.6 - Database-Credential Secret Gate

Status: PASS (2026-09-26).

The blocking `secretScan` Gradle task is part of CI stage 3. Its pinned Gitleaks 8.30.1 runner verifies the
downloaded archive checksum, scans every commit reachable through `git --all`, then separately scans the
working tree so uncommitted and untracked material is covered. Reports are redacted and retained below
`build/reports/secret-scan`.

The clean baseline run scanned 91 commits and the current working tree with no leaks. Its retained summary
records `PASS` for both scopes.

All login-role credentials declared by the grant matrix resolve outside source control:

| Login role | Secret-manager resolution |
|---|---|
| `app_api` | API and exam-path pools use the `cbt.database.roles.app-api.password` config-tree property |
| `app_worker` | Worker pool uses the `cbt.database.roles.app-worker.password` config-tree property |
| `app_pindist` | PIN-distribution pool uses the `cbt.database.roles.app-pindist.password` config-tree property |
| `app_migrator` | The migration Job mounts only `cbt.database.roles.app-migrator.password`; `MigrationApplication` requires that property |
| `app_readonly_ops` | The operations credential is supplied to the external diagnostic client and is explicitly never mounted into an application workload |

`application.yaml` imports `/run/secrets/database/` as a Spring config tree and gives no password placeholder
a committed fallback. Role DDL contains no `PASSWORD` clause; provisioning and rotation occur through the
secret manager's non-logging administrative path described in `docs/configuration/database-secrets.md`.

Evidence command:

```bash
./gradlew secretScan
```

## P6.7 - Read-Only Operations Role Contains No PII Surface

Status: PASS (2026-09-26).

`PersistenceSecurityGatesIntegrationTest` verifies the migrated catalogue rather than relying on role names:

- `app_readonly_ops` has zero privileges on base or partitioned tables.
- Its only relation grant is `SELECT` on `platform.database_diagnostics`.
- The view contains exactly `numbackends`, `xact_commit`, `xact_rollback`, `blks_read`, `blks_hit`, and
  `deadlocks`; adding any column fails the exact-size assertion.

Those fields are database-wide operational counters from `pg_catalog.pg_stat_database`. They contain no
tenant, user, candidate, assessment, response, network-address, or free-text value. The role therefore has
no direct table grant and no PII-bearing granted view.

## P6.8 - Migrator Is Isolated to the Migration Entrypoint

Status: PASS (2026-09-26).

The DDL-capable `app_migrator` identity is reachable only through the explicit `--migrate-only` branch:

- `CbtPlatformApplication` starts `MigrationApplication` only when that exact argument is present; normal
  startup launches the serving application.
- `MigrationApplication` refuses every username except `app_migrator` and requires its externally supplied
  password property.
- `application.yaml` disables Flyway globally. Its `api`, `worker`, and `pindist` profiles neither select
  `app_migrator` nor reference the migrator password; `MigrationApplicationTest` enforces this statically.
- The migration Job is the only deployment template mounting the migrator password. Its dedicated service
  account does not mount an API token, and the fail-closed admission policy limits Job creation to the
  release-pipeline principal.
- The live grant-diff gate requires `app_migrator` to be the sole schema owner and DDL-capable application
  role while serving pool roles have no direct object grants.

Evidence command:

```bash
./gradlew test --tests 'org.meldtech.platform.migration.MigrationApplicationTest'
```

## P6.9 - Architecture Section 13.6 Threat Conformance

Status: CONFORMANT WITH NAMED CARRIES (2026-09-26).

The architecture baseline's three cross-cutting tenant-isolation threat rows are mapped below. A carry is
kept explicit where this persistence feature supplies a backstop but does not own the application behavior.

| STRIDE threat | FEAT-PLAT-002 mitigation and evidence | Carried work | Residual assessment |
|---|---|---|---|
| **I/E:** missing tenant predicate exposes another institution's candidates, questions, or results | R5 requires `TenantId`; module grants constrain schema reach; forced RLS uses strict `current_setting`; P6.1 demonstrates why one layer is insufficient; P6.2 proves an omitted predicate returns zero rows; the catalogue and grant-diff gates block drift; `ARC-VERIFY-024` proves context reset under adversarial pool reuse | Object-level `404` evaluation remains `FEAT-IAM-003`/P6.3; full endpoint-matrix release coverage remains `FEAT-SEC-001`/P7.16; privileged-read audit emission remains `FEAT-AUD-001` | Low only after the carried controls are delivered; this feature does not claim to discharge them |
| **E:** platform scope reaches tenant data without a platform role | `PlatformOperation` is a closed source enumeration; `SecurityContextInitializer` rejects tenant-bearing actors and actors not permitted for the operation, then installs an explicit transaction-local platform-scope marker; P6.4 confirms no generic bypass exists | Business-level platform-operation authorization and in-transaction audit emission remain with `FEAT-IAM-003` and `FEAT-AUD-001` | Low after the named authorization and audit owners deliver their limbs |
| **T:** migration or operator script mutates another tenant's data | P6.8 proves the DDL-capable role is restricted to `--migrate-only`; serving profiles keep Flyway disabled and cannot select the role; migration SQL is versioned and CI reviewed; module ownership, grant-diff, RLS, and cross-schema-FK gates constrain each change | Audited operator slices and the production break-glass workflow in architecture section 20.4 are operational/application controls outside this feature | Low under the architecture's reviewed migration and break-glass process; no ad-hoc SQL path is introduced here |

The two Critical persistence risks are covered directly: `ARC-RISK-005` by independent query-signature,
grant, and forced-RLS controls, and `ARC-RISK-026` by reset-on-release plus the adversarial same-connection
suite. Their remaining launch evidence is tracked in P7.13, P7.16, and P7.17 rather than silently treated as
complete by this review.
