# FEAT-PLAT-002 Phase 7 Testing Evidence

## P7.1 ARC-VERIFY-002 Static Schema-Ownership Limb

Status: PASS (2026-09-26)

`R3SchemaOwnershipTests.tenantQueriesReferenceOnlyTheirOwningSchema` scans compiled production query types,
extracts SQL constants with ASM, parses relation names with JSQLParser, and rejects every schema qualifier
that differs from the query type's owning module. Both classes named `Queries` and implementations of
`TenantScopedQuery` are covered.

The rule has no exemption for `app_txn_examentry` or any other composite role. Cross-module collaboration
therefore does not authorize a slice query to reference a foreign schema; it must proceed through the
published module APIs and the single transaction owned by `TransactionalCollaboration`.

`rejectsAQueryNamingAForeignSchema` imports a deliberate fixture and proves the same Stage 4 assertion fails
with `R3 schema ownership violated:` rather than merely passing the current production tree.

Verification:

```text
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.conformance.R3SchemaOwnershipTests'

BUILD SUCCESSFUL
```

## P7.2 ARC-VERIFY-002 Live Grant-Matrix Limb

Status: PASS (2026-09-26)

`PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix` migrates a real
PostgreSQL 17 database and compares the complete live authorization state with
`db/grants/grant-matrix.json`. `GrantDiffGate` includes role attributes, memberships, schema privileges,
relation privileges, column privileges, routine privileges, and default privileges. Any extra or missing
fact fails with `ARC-VERIFY-002 grant drift`.

Each module role is limited to its owning module schema. The only cross-schema facts allowed by the matrix
are `USAGE` plus `INSERT` for the shared `audit.audit_event` and `outbox.outbox_event` infrastructure
contracts; no role receives access to another module's schema. Because the comparison is exact, an
undeclared foreign-schema grant cannot pass as harmless surplus privilege.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix'

BUILD SUCCESSFUL
```

## P7.3 ARC-VERIFY-005 Omitted-Predicate Backstop

Status: PASS (2026-09-26)

`PersistenceFoundationIntegrationTest.forcedRlsHidesForeignRowWhenTenantPredicateIsOmitted` inserts probe
rows for tenants A and B, installs tenant A's transaction-local context, and deliberately queries tenant B's
probe identifier without any `tenant_id` predicate. PostgreSQL forced RLS returns a count of zero.

This is the database-layer assertion for `ARC-VERIFY-005`; it does not depend on the R5 query-signature rule
or on application query construction.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.migration.PersistenceFoundationIntegrationTest.forcedRlsHidesForeignRowWhenTenantPredicateIsOmitted'

BUILD SUCCESSFUL
```

## P7.4 Pre-Context Statement Has No Database Privilege

Status: PASS (2026-09-26)

`AdversarialConnectionReuseIntegrationTest.uninitialisedConnectionHasNoPrivilegeOrTenantSetting` checks out
the raw `app_api` connection pool and deliberately bypasses `SecurityContextInitializer`. Before any
transaction or role/tenant context is installed, it issues a query against
`platform.tenant_scope_probe`.

PostgreSQL rejects the statement with SQLSTATE `42501` (`insufficient_privilege`). The assertion is on the
database error class, proving the no-direct-grants rule remains the backstop even if the decorator's own
pre-context refusal is absent.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest.uninitialisedConnectionHasNoPrivilegeOrTenantSetting'

BUILD SUCCESSFUL
```

## P7.5 Missing Tenant Setting Raises by Error Class

Status: PASS (2026-09-26)

The fresh-session limb of
`AdversarialConnectionReuseIntegrationTest.uninitialisedConnectionHasNoPrivilegeOrTenantSetting` connects as
`app_api` outside the decorator and evaluates `current_setting('app.tenant_id', false)` before any context
installation.

The assertion requires an `R2dbcException` with PostgreSQL SQLSTATE `42704` (`undefined_object`). It does not
inspect provider message text, so localization or wording changes cannot turn a real failure into a brittle
test failure.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest.uninitialisedConnectionHasNoPrivilegeOrTenantSetting'

BUILD SUCCESSFUL
```

## P7.6 Forced RLS Restricts the Table Owner

Status: PASS (2026-09-26)

`AdversarialConnectionReuseIntegrationTest.forcedRlsRestrictsTheTableOwner` connects as `app_migrator`, the
owner of `platform.tenant_scope_probe`. The fixture contains rows for tenants A and B; after tenant A's
transaction-local setting is installed, an unfiltered owner-role query sees exactly one row.

The probe migration applies both `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY`, so this result
proves the table owner does not retain PostgreSQL's normal owner bypass.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest.forcedRlsRestrictsTheTableOwner'

BUILD SUCCESSFUL
```

## P7.7 Cross-Schema Foreign-Key Gate and Negative Test

Status: PASS (2026-09-26)

`PersistenceSecurityGatesIntegrationTest.noForeignKeySpansApplicationSchemas` runs the P4.13 catalogue gate
against all fifteen application schemas and finds no cross-schema foreign key.

`crossSchemaForeignKeyGateRejectsAViolationAndRollsItBack` then creates a deliberate
`platform.p7_fk_child -> tenancy.p7_fk_parent` foreign key inside a rollback-only transaction. The gate must
fail with `CROSS_SCHEMA_FOREIGN_KEY_GATE` and name the relationship. The transaction is rolled back in a
`finally` block, after which the same gate is run again and must pass, proving the violation was reverted.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.noForeignKeySpansApplicationSchemas' \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.crossSchemaForeignKeyGateRejectsAViolationAndRollsItBack'

BUILD SUCCESSFUL
```

## P7.8 Exact Module-Role Grant Contract

Status: PASS (2026-09-26)

`PersistenceSecurityGatesIntegrationTest.moduleRoleGrantsMatchOwnedSchemaAndSharedInsertContracts` derives
all twelve module schemas and roles from the canonical grant matrix. For each role it requires exactly:

- `USAGE` and `SELECT`/`INSERT`/`UPDATE`/`DELETE` on its owning module schema;
- `USAGE` on `audit` and `outbox`; and
- `INSERT` as the sole default table privilege in both shared schemas.

The test also queries PostgreSQL's live default-privilege catalogue and requires zero `UPDATE` or `DELETE`
defaults on `audit` for every module role, then reruns the complete live grant diff. The shared tables are
owned by `FEAT-AUD-001` and `FEAT-PLAT-004`; P7.15 separately owns the future-table creation proof, while
this task proves the exact role contract and its live bootstrap state without inventing those tables here.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.moduleRoleGrantsMatchOwnedSchemaAndSharedInsertContracts'

BUILD SUCCESSFUL
```

## P7.9 Forced-RLS Catalogue Gate Negative Test

Status: PASS (2026-09-26)

`PersistenceSecurityGatesIntegrationTest.forcedRlsGateRejectsAnUnprotectedTenantTableAndRollsItBack`
creates `platform.p7_unforced_rls` with a `tenant_id` column and deliberately applies no RLS configuration.
The table exists only inside a rollback-only transaction.

The P4.12 gate discovers it mechanically from the `tenant_id` column and fails with
`RLS_CATALOG_GATE: platform.p7_unforced_rls`. Cleanup runs in a `finally` block; after rollback, the same
catalogue gate passes again. The violation is therefore reverted while its failing assertion remains in CI.

Verification:

```text
./gradlew integrationTest \
  --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.forcedRlsGateRejectsAnUnprotectedTenantTableAndRollsItBack'

BUILD SUCCESSFUL
```

## P7.10 R9 Negative Tests

Status: PASS (2026-09-26)

Two deliberate R9 violations prove that the transaction wrapper rejects work before context installation and
does not silently report a connection release as successful when reset fails:

| Violation | Test | Stable refusal |
|---|---|---|
| A caller creates a statement before the wrapper has installed role, tenant, and search-path context | `SecurityContextInitializerTest.refusesCallerStatementsBeforeContextInstallation` | `R9_CONTEXT_NOT_FIRST` |
| `RESET ROLE` fails while a connection is being released | `SecurityContextInitializerTest.refusesSuccessfulReleaseWhenConnectionResetFails` | The transaction terminates with an error and increments `db_connection_reset_failure_total`; `RESET ALL` and close are still attempted |

Verification:

```text
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest.refusesCallerStatementsBeforeContextInstallation' \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest.refusesSuccessfulReleaseWhenConnectionResetFails'

BUILD SUCCESSFUL
```

## P7.11 R10 Negative Tests

Status: PASS (2026-09-26)

Two deliberate R10 violations prove that both enforcement limbs reject the
prohibited behavior:

| Violation | Test | Stable refusal |
|---|---|---|
| A handler accepts a free-form database role outside the compile-time enumeration | `R10DatabaseRoleAssumptionTests.rejectsAHandlerAcceptingADynamicRole` | `R10_DYNAMIC_ROLE_INPUT` |
| A transaction attempts `SET LOCAL ROLE app_people` after installing `app_delivery` | `SecurityContextInitializerTest.refusesARoleSwitchAfterInitialContextInstallation` | `R10_ROLE_SWITCH`; rollback, `RESET ROLE`, `RESET ALL`, and close all complete |

Verification:

```text
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.conformance.R10DatabaseRoleAssumptionTests.rejectsAHandlerAcceptingADynamicRole' \
  test \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest.refusesARoleSwitchAfterInitialContextInstallation'

BUILD SUCCESSFUL
```

## P7.12 ARC-VERIFY-024 Adversarial Pool Reuse

Status: PASS (2026-09-26)

`AdversarialConnectionReuseIntegrationTest` runs against migrated PostgreSQL 17
with a pool constrained to one physical connection. Nine transactions alternate
tenants A and B and distinct enumerated module roles across success, cancellation,
read-timeout, statement-timeout, and application-exception paths. Every snapshot
reports the expected tenant and role, and every snapshot has the same
`pg_backend_pid()`.

The suite also proves that:

- a raw pooled statement is refused with SQLSTATE `42501`;
- a recycled connection has no usable tenant value;
- strict `current_setting('app.tenant_id', false)` on a fresh uninitialised session
  raises SQLSTATE `42704`;
- `FORCE ROW LEVEL SECURITY` restricts the table owner to the selected tenant.

The suite exposed an eager callback invocation in `SecurityContextInitializer`.
Application work is now deferred until transaction start and context installation
complete, with a focused unit regression test preserving that ordering.

Verification:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest' \
  --rerun-tasks

BUILD SUCCESSFUL

./gradlew ciStage8 --rerun-tasks

BUILD SUCCESSFUL
```
