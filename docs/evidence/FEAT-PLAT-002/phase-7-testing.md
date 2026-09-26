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
