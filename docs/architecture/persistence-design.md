# Persistence Isolation Design

Status: normative Phase 2 design for `FEAT-PLAT-002`.

Sources: ratified architecture v1.4 sections 5.1, 7.3, 8.4, 9.2, 9.4,
9.8, 12.3, 16.2, 16.4, 17.1, 18.1, 19.5, and 19.8; `ADR-003`,
`ADR-010`, and `ADR-023`; plan section 8.1.

This document fixes the contracts that Phase 3 and Phase 4 implement. It does
not claim that migrations, runtime enforcement, or verification gates already
exist.

## P2.1 Schema And Role Naming

Names are lower-case ASCII PostgreSQL identifiers and are never quoted. The
following schema names are closed and exact:

| Category | Schema names |
|---|---|
| Module schemas | `tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`, `examaccess`, `delivery`, `grading`, `result`, `correction`, `notification` |
| Platform schemas | `audit`, `outbox`, `platform` |

Each module schema maps one-to-one to a `NOLOGIN` runtime role named
`app_<module>`. The complete set is `app_tenancy`, `app_iam`, `app_academic`,
`app_people`, `app_questionbank`, `app_authoring`, `app_examaccess`,
`app_delivery`, `app_grading`, `app_result`, `app_correction`, and
`app_notification`.

Role classes use these fixed names and patterns:

| Role class | Name | Naming rule |
|---|---|---|
| Composite transaction role | `app_txn_examentry` | `app_txn_<flow>`; the suffix is the identifier from the closed `ADR-023` flow enumeration |
| Pool login roles | `app_api`, `app_worker`, `app_pindist` | Exact workload identity names; no suffixes or environment names |
| Migration role | `app_migrator` | Exact name |
| Read-only operations role | `app_readonly_ops` | Exact name |

Environment separation belongs to database/cluster boundaries, not identifier
suffixes. Application code and configuration must select from the closed role
enumeration; callers cannot construct a role name from request data. A schema,
module-role, composite-role, or operational-role naming deviation requires an
approved ADR before implementation.

## P2.2 Executable Grant Matrix

The canonical source is a versioned JSON document at
`src/main/resources/db/grants/grant-matrix.json`. JSON is selected so the
implementation can bind it to closed Java records without an ad hoc parser.
The document contains only identifiers and privileges, never passwords or
environment-specific values.

Its top-level shape is:

```json
{
  "formatVersion": 1,
  "schemas": [{"name": "tenancy", "owner": "app_migrator"}],
  "roles": [{"name": "app_tenancy", "login": false}],
  "memberships": [{"member": "app_api", "role": "app_tenancy"}],
  "objectGrants": [
    {
      "grantee": "app_tenancy",
      "objectType": "ALL_TABLES_IN_SCHEMA",
      "schema": "tenancy",
      "privileges": ["SELECT", "INSERT", "UPDATE", "DELETE"]
    }
  ],
  "defaultPrivileges": [],
  "denials": []
}
```

`objectType` is a closed enumeration covering schema usage, all/current tables,
individual tables, sequences, functions, and diagnostic views. Privileges are
also enumerated per object type. `defaultPrivileges` records the creating role,
schema, object type, grantees, and privileges. `denials` makes security-critical
negative obligations executable, including no direct object grants for pool
login roles and no `UPDATE` or `DELETE` on `audit` objects.

One parser and validator produce a normalized model. Validation rejects unknown
schemas, roles, object types, privileges, duplicate facts, role-membership
cycles, non-closed composite roles, login-role object grants, and grants that
contradict a denial. Ordering is insignificant in source and deterministic in
generated output.

The normalized model has exactly two consumers:

1. A deterministic generator emits the Flyway role/grant SQL with the source
   SHA-256 in its header. Generated SQL is never edited by hand, and a build
   check regenerates it and requires a byte-for-byte match.
2. The grant-diff gate converts the same normalized model to expected catalogue
   facts and compares them with normalized facts read from PostgreSQL.

Consequently, a grant is added, changed, or removed only in
`grant-matrix.json`. Generated DDL and verification expectations cannot be
maintained independently or silently diverge.

## P2.3 Audit And Outbox Default Privileges

`app_migrator` is the creating role for objects in `audit` and `outbox`.
PostgreSQL keys default privileges by object creator, so running a migration as
a different effective role would not satisfy this contract. The migration
entrypoint must either connect as `app_migrator` or establish that effective
role before creating an object.

Before any audit or outbox table is created, generated DDL applies this shape
once per schema:

```sql
ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA audit
    GRANT INSERT ON TABLES TO app_tenancy, app_iam, app_academic,
        app_people, app_questionbank, app_authoring, app_examaccess,
        app_delivery, app_grading, app_result, app_correction,
        app_notification, app_txn_examentry;

ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA outbox
    GRANT INSERT ON TABLES TO app_tenancy, app_iam, app_academic,
        app_people, app_questionbank, app_authoring, app_examaccess,
        app_delivery, app_grading, app_result, app_correction,
        app_notification, app_txn_examentry;
```

The grantee list is emitted from the canonical grant matrix, not duplicated in
handwritten migration code. Schema `USAGE` is declared separately in that
matrix. No sequence, function, `UPDATE`, or `DELETE` default privilege is
implied by the table `INSERT` grant.

Default privileges affect only objects created later by the named creator.
Therefore this bootstrap precedes the table migrations owned by
`FEAT-AUD-001` and `FEAT-PLAT-004`; an already existing object requires an
explicit matrix grant. The grant-diff gate checks `pg_default_acl` as well as
current object grants, and its integration test creates a table as
`app_migrator` to prove inheritance.

No application, composite, pool-login, or operations role is ever granted
`UPDATE` or `DELETE` on `audit.*`, directly, through default privileges, or
through role membership. Changing that invariant requires an architecture
amendment, not a matrix edit alone.

## P2.4 SecurityContextInitializer Decorator

`SecurityContextInitializer` decorates the pooled R2DBC `ConnectionFactory`.
Pool configuration authenticates physical connections only as `app_api`,
`app_worker`, or `app_pindist`; the grant matrix proves those login roles have
no direct object privileges. The decorator never accepts a database role or
tenant from SQL text, HTTP input, or mutable global state.

Each returned connection is wrapped with an isolated state machine:

```text
CHECKED_OUT -> TRANSACTION_OPEN -> INSTALLING_ROLE -> INSTALLING_TENANT -> READY
     |                 |                                      |
     +-----------------+---------------+----------------------+-> RELEASING
```

The wrapper permits `beginTransaction`, transaction completion, context
installation, reset, and close while not `READY`. It refuses all caller-created
statements, batches, savepoints, and commits before context installation
completes. It also rejects concurrent initialization, a second role assumption,
and every statement after release begins. State is attached to the connection
wrapper; it is neither a `ThreadLocal` nor shared between connections.

The role/tenant selection is read with `deferContextual` from the immutable
request carrier and the compile-time handler-to-role policy. Initialization is
started by `beginTransaction`, not by connection checkout. A caller receives
completion from `beginTransaction` only after the two installation statements
have succeeded, making `READY` the only state visible to application SQL. An
installation error rolls back and enters release cleanup.

Transaction orchestration uses `Mono.usingWhen` with all three asynchronous
cleanup callbacks:

| Reactor termination | Cleanup before delegate `close()` |
|---|---|
| `onComplete` | Complete the transaction, then execute `RESET ROLE`, then `RESET ALL` |
| `onError` | Roll back an active transaction, execute both resets, preserve the original error, then close |
| `cancel` | Roll back an active transaction in the asynchronous-cancel callback, execute both resets, then close |

The pool `preRelease` hook repeats the reset assertion as a final backstop, so
a caller cannot bypass cleanup by using the pooled connection incorrectly.
Reset statements are issued sequentially through a private internal execution
path that application code cannot invoke. Cleanup is idempotent and runs at
most once per wrapper. If rollback or either reset fails, the physical
connection is invalidated and evicted rather than returned to the pool; reset
failure is never converted into a reusable connection.

The decorator contains only connection lifecycle and context enforcement.
Grant selection remains in the closed role policy, transaction ownership in the
handler/`TransactionalCollaboration`, and SQL in slice-local `Queries` adapters.

## P2.5 Transaction-Local Context Installation

After the R2DBC `beginTransaction` protocol completes, the first two SQL
statements on the physical connection are exactly, and only, these statements
in this order:

```sql
SET LOCAL ROLE app_<allowed_role>;
SET LOCAL app.tenant_id = '<canonical-tenant-uuid>';
```

The role is selected from the closed handler-to-role enumeration. It is never a
free-form string. The tenant value is parsed into the platform tenant-id type
before checkout and rendered only in canonical UUID form. An absent, malformed,
or platform-scope tenant is rejected before SQL generation. These constraints
make the unavoidable PostgreSQL identifier/value rendering finite and
injection-safe.

No `SELECT`, timeout setting, validation query, savepoint, module callback, or
business statement may occur between `BEGIN` and these statements. Role
installation must complete before tenant installation begins; tenant
installation must complete before the wrapper enters `READY`. A failure in
either statement rolls back the transaction and follows the error cleanup path.

Installation never occurs at connection checkout because checkout is outside a
transaction and session-level state could survive pool reuse. `SET LOCAL`
automatically reverts both the assumed role and tenant setting at `COMMIT` or
`ROLLBACK`; no explicit transaction restore path exists to be skipped. The
release-time `RESET ROLE` and `RESET ALL` from P2.4 remain a defence-in-depth
pool hygiene assertion, not the normal transaction restore mechanism.

## P2.6 Forced RLS Convention

Every table carrying a `tenant_id` column receives the following generated DDL
in its owning migration. Schema and table identifiers come from migration
metadata, not runtime input.

```sql
ALTER TABLE <schema>.<table> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.<table> FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON <schema>.<table>
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (
        tenant_id = current_setting('app.tenant_id', false)::uuid
    )
    WITH CHECK (
        tenant_id = current_setting('app.tenant_id', false)::uuid
    );
```

`USING` protects reads, updates, and deletes; `WITH CHECK` protects inserts and
the new row produced by an update. `TO PUBLIC` makes the policy apply to every
current and future grantee; object privileges still determine whether a role
may attempt an operation. This is the table's only policy. The catalogue gate
rejects additional policies so PostgreSQL's permissive-policy `OR` composition
cannot widen access. A lone restrictive policy is deliberately not used,
because PostgreSQL requires at least one applicable permissive policy before a
restrictive policy can admit any row.

The second argument to `current_setting` is always the literal `false`.
Omitting it or changing it to `true` is a conformance failure: without an
installed setting, PostgreSQL must raise instead of producing a null scope. A
query that omits its tenant predicate can see only rows matching the installed
tenant and returns zero rows for another tenant. `FORCE ROW LEVEL SECURITY` is
mandatory because `ENABLE` alone permits the table owner to bypass policies.

The convention applies mechanically to business tables, platform tables, and
conformance tables alike whenever `tenant_id` exists. A table cannot opt out by
name or package; legitimate platform-scope operations use the separate closed
mechanism in P2.14.

## P2.7 Blocking RLS Catalogue Gate

After all Flyway migrations, CI stage 8 and the staging verification entrypoint
run a catalogue query equivalent to the following. The application schema array
is generated from P2.2's canonical schema model; there is no table list or
exemption list.

```sql
WITH tenant_tables AS (
    SELECT c.oid, n.nspname, c.relname,
           c.relrowsecurity, c.relforcerowsecurity
    FROM pg_catalog.pg_class AS c
    JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
    WHERE n.nspname = ANY (CAST(:application_schemas AS text[]))
      AND c.relkind IN ('r', 'p')
      AND a.attname = 'tenant_id'
      AND a.attnum > 0
      AND NOT a.attisdropped
)
SELECT t.nspname, t.relname,
       t.relrowsecurity, t.relforcerowsecurity
FROM tenant_tables AS t
WHERE NOT t.relrowsecurity
   OR NOT t.relforcerowsecurity
   OR NOT EXISTS (
       SELECT 1
       FROM pg_catalog.pg_policy AS p
       WHERE p.polrelid = t.oid
         AND p.polcmd = '*'
         AND p.polpermissive
         AND position(
             'app.tenant_id' IN
             coalesce(pg_catalog.pg_get_expr(p.polqual, p.polrelid), '')
         ) > 0
         AND position(
             'app.tenant_id' IN
             coalesce(pg_catalog.pg_get_expr(p.polwithcheck, p.polrelid), '')
         ) > 0
   )
ORDER BY t.nspname, t.relname;
```

Any returned row fails the gate and reports the qualified table plus which of
enabled RLS, forced RLS, `USING`, or `WITH CHECK` is missing. A companion
template check requires exactly one policy named `tenant_isolation`, normalizes
its `pg_get_expr` output, and requires both expressions to equal the P2.6
predicate, including the literal `false`; merely mentioning `app.tenant_id`
cannot satisfy the gate.

The gate runs against PostgreSQL 17 after real migrations and before integration
tests. It also asserts that at least the proving table from P2.9 was discovered,
preventing a vacuous pass in the Phase 0 schema. Because discovery uses
`pg_attribute`, any future application table with a live `tenant_id` column is
automatically in scope and blocks the build until its forced policy exists.

## P2.8 Live Grant-Diff Gate

The `ARC-VERIFY-002` database limb loads expected facts through the P2.2 parser
and reads actual facts from PostgreSQL 17 in the same normalized vocabulary.
Actual-state readers cover:

| Fact | PostgreSQL source |
|---|---|
| Role existence and attributes | `pg_catalog.pg_roles` |
| Direct memberships and membership options | `pg_catalog.pg_auth_members` joined to `pg_roles` |
| Schema owner and ACL | `pg_namespace`, using `aclexplode` for explicit privileges |
| Table and view grants | `information_schema.role_table_grants` |
| Column grants | `information_schema.role_column_grants` |
| Sequence and routine grants | `information_schema.role_usage_grants` and `role_routine_grants` |
| Default privileges | `pg_catalog.pg_default_acl`, using `aclexplode` |

The comparison scope is every role whose name starts with `app_` and every
object in the 15 application schemas. Normalization represents each role
attribute, ownership edge, membership edge, and `(grantor, grantee, object
type, qualified object, privilege, grantable)` tuple as one sorted fact.
Implicit owner capabilities are represented as ownership, not fabricated ACL
rows. The reader also expands the membership graph to detect an effective
foreign-schema privilege obtained indirectly.

The gate computes both set differences:

```text
missing = expected - actual
extra   = actual - expected
```

Either non-empty set exits unsuccessfully and prints stable, redacted facts.
An undeclared `app_` role, role membership, `LOGIN`/`BYPASSRLS`/superuser flag,
grant option, object grant, or default ACL is therefore an extra; a declared
fact absent from the live database is missing. Pool login roles receive an
additional invariant check that their direct object-grant set is empty and all
effective access arrives only through declared role memberships.

Information-schema views are intentionally not the sole source because they do
not completely describe role attributes, ownership, membership, or default
ACLs. The gate runs after migrations against the real PostgreSQL database in CI
stage 8 and produces an `ARC-VERIFY-002` failure; there is no advisory mode or
allow-extra option.

## P2.9 Tenant-Scope Proving Table

`platform.tenant_scope_probe` is a permanent conformance artifact owned by
`app_migrator`. It has no application API, domain representation, repository,
or cross-module identifier and stores no business or personal data.

```sql
CREATE TABLE platform.tenant_scope_probe (
    tenant_id uuid NOT NULL,
    probe_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, probe_id)
);

COMMENT ON TABLE platform.tenant_scope_probe IS
    'FEAT-PLAT-002 conformance artifact; contains no business or personal data';
```

The tenant-leading primary key follows the normal tenant-table convention. The
table receives the complete P2.6 RLS template: enabled RLS, forced RLS, and the
sole `tenant_isolation` policy using
`current_setting('app.tenant_id', false)::uuid` for both `USING` and
`WITH CHECK`.

No module or pool-login role receives an object grant on this table, because
that would create a foreign-schema exception to `ARC-VERIFY-002` merely for a
test. PostgreSQL integration verification connects through its migration test
fixture, installs one tenant context per transaction, and seeds opaque UUID
rows for tenants A and B. It then exercises owner-role FORCE behavior, an
omitted query predicate, absent context, cross-tenant read/write rejection, and
policy catalogue discovery. Any temporary test-only privilege is created only
inside the ephemeral test database and must be revoked before the grant-diff
assertion.

The artifact is not a placeholder for a future business table. Business
features still create and own their tables and receive catalogue-gate coverage
independently.

## P2.10 TransactionalCollaboration

`TransactionalCollaboration` is shared infrastructure for the one closed
`ADR-023` flow. Its public API exposes a dedicated exam-entry operation, not a
method accepting arbitrary flow and role strings:

```java
<T> Mono<T> inExamEntryTransaction(
        TenantId tenantId,
        Function<TransactionalConnection, Mono<T>> work);
```

Internally, a source-controlled enum pairs
`EXAM_ENTRY("examaccess.verifyPinAndStartAttempt", "app_txn_examentry")`.
The role is selected from that enum by the dedicated method and cannot be
supplied by configuration, request data, or a generic role parameter. Adding a
flow requires an enum and API change reviewed as an `ADR-023` amendment.

The initiating `verifyPinAndStartAttempt` handler calls this component once.
The component checks out exactly one physical connection, begins exactly one
transaction, invokes P2.5 context installation with `app_txn_examentry`, and
places an opaque `TransactionalConnection` handle in Reactor `Context`. The
handle cannot open or close a connection and cannot change role or tenant.
Nested collaboration scopes and a pre-existing incompatible transaction are
rejected.

Module `api` methods invoked by the callback are annotated
`@Transactional(propagation = Propagation.MANDATORY)`. Their R2DBC adapters use
`deferContextual` to obtain the bound handle; they never call the pool and may
neither begin, commit, roll back, suspend, nor replace the transaction. The
initiating component alone commits on callback completion or rolls back on
error/cancellation, then delegates all release paths to P2.4.

The existing `AtomicCrossModuleFlow` Phase 1 placeholder currently contains
`cbt_exam_entry`; P4.8 must replace that literal with the ratified
`app_txn_examentry` value while implementing this design. No Phase 2 document
treats the placeholder value as an approved alias.

## P2.11 R9 And R10 Conformance Rules

### R9: context first and reset on release

Subject: every R2DBC transaction opened for a slice handler or synchronous
collaboration. The decorator records statement kinds, not SQL values, in a
connection-local trace used by conformance tests.

The rule passes only when the first statement after transaction begin is one
allowed `SET LOCAL ROLE`, the second is `SET LOCAL app.tenant_id` or the closed
platform marker from P2.14, no caller statement occurs before readiness, and
release executes `RESET ROLE` followed by `RESET ALL` for completion, error,
and cancellation. Static conformance also forbids handlers and query adapters
from depending directly on an undecorated `ConnectionFactory`.

Stable failures are:

```text
R9_CONTEXT_NOT_FIRST: transaction executed <statement-kind> before the required SET LOCAL ROLE and scope statements
R9_CONTEXT_ORDER_INVALID: expected <expected-kind> as context statement <1|2>, observed <actual-kind>
R9_CONNECTION_NOT_RESET: connection release after <complete|error|cancel> did not complete RESET ROLE then RESET ALL
R9_UNDECORATED_CONNECTION_ACCESS: <class> can obtain a connection outside SecurityContextInitializer
```

### R10: one allowed role and no switch

Subject: every class named `Handler` in a slice package and every decorated
transaction trace. A normal handler maps from its owning top-level module to
exactly `app_<module>`. A handler annotated with `@SynchronousAtomicFlow` may
map only to the composite role paired with that flow in the closed P2.10 enum.
The source rule rejects free-form role parameters/configuration and undeclared
flow annotations. The runtime rule requires exactly one `SET LOCAL ROLE` and
rejects `SET ROLE`, `SET SESSION AUTHORIZATION`, or a later `SET LOCAL ROLE`
before transaction completion.

Stable failures are:

```text
R10_ROLE_NOT_ALLOWED: handler <class> may assume <expected-role> but requested <actual-role>
R10_COMPOSITE_ROLE_UNDECLARED: handler <class> references flow/role pair absent from ADR-023
R10_ROLE_SWITCH: transaction attempted role statement <ordinal> after initial role <role>
R10_DYNAMIC_ROLE_INPUT: <class-or-method> accepts a role outside the compile-time enumeration
```

Failure output names classes, flow identifiers, role identifiers, and signal
kinds only; it never prints tenant identifiers, credentials, or SQL parameter
values. P4.10 owns R9 implementation and P4.11 owns R10 implementation.

## P2.12 Module Search Path

Search path is transaction-local and derived from the same closed role mapping
as context installation. Immediately after P2.5's mandatory first two
statements, the decorator issues a third internal statement:

```sql
SET LOCAL search_path = pg_catalog, <owning_module_schema>;
```

For example, `app_delivery` receives `pg_catalog, delivery`. `pg_catalog` is
first so an application-schema object cannot shadow a built-in function or
operator. `public`, other module schemas, `audit`, `outbox`, and `platform` are
never in a module role's search path. Schema names come from the P2.1 enum and
cannot be supplied by configuration or request data.

`app_txn_examentry` receives `SET LOCAL search_path = pg_catalog`. Its explicit
cross-module operations must use schema-qualified names, making every access
reviewable against its minimum grant list. Operational and migration
connections use separate entrypoints and do not inherit an application search
path.

Search path is a usability and accidental-reference control, not the security
boundary. A qualified foreign-schema reference bypasses name resolution, so
the module role also lacks `USAGE` and object privileges on every foreign
schema. It then fails at PostgreSQL even if static R3 scanning misses it. The
static SQL scan, local search path, and exact grant matrix remain independent
controls.

The setting reverts with the transaction and is covered by release-time
`RESET ALL`. It is installed after role and tenant, preserving the exact R9
first-statement order while avoiding session state at pool checkout.

## P2.13 Cross-Schema Foreign-Key Gate

After migrations, the integration gate runs this catalogue query with the 15
application schemas generated from P2.2:

```sql
SELECT con.conname,
       source_ns.nspname AS source_schema,
       source_table.relname AS source_table,
       target_ns.nspname AS target_schema,
       target_table.relname AS target_table
FROM pg_catalog.pg_constraint AS con
JOIN pg_catalog.pg_class AS source_table
  ON source_table.oid = con.conrelid
JOIN pg_catalog.pg_namespace AS source_ns
  ON source_ns.oid = source_table.relnamespace
JOIN pg_catalog.pg_class AS target_table
  ON target_table.oid = con.confrelid
JOIN pg_catalog.pg_namespace AS target_ns
  ON target_ns.oid = target_table.relnamespace
WHERE con.contype = 'f'
  AND source_ns.nspname <> target_ns.nspname
  AND (
      source_ns.nspname = ANY (CAST(:application_schemas AS text[]))
      OR target_ns.nspname = ANY (CAST(:application_schemas AS text[]))
  )
ORDER BY source_schema, source_table, con.conname;
```

The only passing result is an empty set. Each returned row is a blocking
`ARC-VERIFY-002` violation and reports the constraint plus both qualified
tables. There is no allowlist for audit, outbox, platform, or the composite
flow. Cross-module references remain scalar identifiers; the owning module and
event flow maintain their integrity.

The gate inspects the migrated PostgreSQL catalogue, so dynamically generated
or manually introduced constraints cannot escape it. A negative integration
test creates two ephemeral tables in different application schemas, adds a
foreign key, requires this query to fail, and rolls the fixture back. This
runtime evidence complements the R3 source SQL scan rather than replacing it.

## P2.14 Platform Scope

`@PlatformScope` accepts a value from a closed `PlatformOperation` enum. The
initial enumeration is:

| Operation | Permitted actor |
|---|---|
| `PLATFORM_ADMINISTRATION` | Workforce actor whose current authoritative policy resolves platform-administrator authority |
| `RETENTION_SWEEP` | `SYSTEM` actor `RETENTION_ENGINE` |
| `RECONCILIATION` | `SYSTEM` actor `IDP_RECONCILER` |

Startup discovery requires every annotated handler to name one value and to
have a matching policy. A caller cannot request platform scope; the annotation,
handler identity, resolved actor, and operation enumeration must all agree.

Tenant and platform context are mutually exclusive. For an approved
platform-scope transaction, R9's second statement is:

```sql
SET LOCAL app.platform_scope = '<enumerated-operation>';
```

The decorator does not set `app.tenant_id`, a wildcard tenant, a sentinel UUID,
`row_security = off`, `BYPASSRLS`, or an owner role. Therefore an attempt to use
a tenant-scoped table from this transaction still raises through the P2.6
policy. Cross-tenant jobs enumerate authorized tenant identifiers from their
owned platform workflow and perform tenant data work in separate tenant-scoped
transactions, one explicit `TenantId` at a time.

`@PlatformScope` confers no database privilege. The handler still assumes only
the role R10 permits, and any platform object access must be an explicit grant
in the canonical matrix. There is no generic query flag, configuration switch,
test hook, privileged base class, or SQL helper that bypasses tenant filtering.

Every platform-scope state change or privileged read emits an audit row with
`tenant_id = NULL`, the non-null `PlatformOperation` value as its explicit
platform-context marker, and the normal actor and correlation fields. Audit
validation rejects a row having both tenant and platform context, or neither.
`SET LOCAL` and P2.4 cleanup prevent the platform marker from surviving the
transaction or pool release.

## P2.15 Tenant-Isolation Matrix Generator

The generator starts the route-only application context and treats the complete
set of `PolicyProtectedRoute` beans as the route table. A route descriptor adds
its stable route id, HTTP method, path template, owning module, and exactly one
scope classification: tenant-scoped or a P2.14 `PlatformOperation`. Duplicate
ids, missing descriptors, and unclassified routes fail generation.

For each tenant-scoped route, the generator emits the Cartesian product:

```text
route x {READ, WRITE, ENUMERATE}
```

An `IsolationScenarioProvider` keyed by route id supplies opaque tenant A and B
fixtures and an executable attempt for all three operation kinds. Unsupported
HTTP capabilities still require an assertion that the route rejects the
operation without revealing or changing tenant B state; `NOT_APPLICABLE` is not
a valid matrix result.

Each generated assertion establishes an actor in tenant A and a resource owned
by tenant B, invokes only the public endpoint, and verifies:

- read cannot return the tenant B resource and responds `404`, never `403`;
- write responds `404` and leaves tenant B state byte-for-byte unchanged;
- enumerate succeeds only according to the endpoint contract and contains no
  tenant B identifier or data; a direct foreign-resource lookup remains `404`;
- the response body is non-disclosing and contains no foreign identifier or
  existence hint.

The generator writes a deterministic machine-readable matrix under
`build/generated/isolation/` and JUnit dynamic tests consume it in CI stage 10.
The build compares these sets exactly:

```text
tenant route ids == scenario-provider route ids == matrix route ids
```

It also requires exactly three operation rows per tenant route. Any extra or
missing id/operation fails before tests run, so adding an endpoint cannot pass
silently. Platform-scoped routes are excluded only through the closed
`@PlatformScope` enumeration and are listed with their operation marker in the
report; package name, missing `tenant_id`, or absent fixture never implies
platform scope. This feature owns the generator and baseline gate;
`FEAT-SEC-001` extends the scenario catalogue as endpoints are added.

## P2.16 Migration Layout And Entrypoint

Each owned schema has one Flyway location and an independent version stream:

| Category | Locations |
|---|---|
| Module | `classpath:db/migration/tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`, `examaccess`, `delivery`, `grading`, `result`, `correction`, `notification` |
| Platform | `classpath:db/migration/audit`, `outbox`, `platform` |

Each location starts at `V1` and uses a distinct history table named
`flyway_schema_history_<schema>`. The migration runner creates history tables
in its administrative default schema so a location's first migration may create
its owned application schema. A migration may create or alter only its named
schema, except the generated role/grant bootstrap whose ownership is declared
centrally by P2.2. Cross-location ordering dependencies are rejected; data
coordination occurs through application contracts, not migration SQL.

The application artifact has a dedicated `--migrate-only` entrypoint. In that
mode it starts no Netty server, route, scheduler, consumer, outbox relay, or
R2DBC pool. It resolves the JDBC URL and `app_migrator` credentials from
external secrets, runs the 15 Flyway instances in a deterministic order, emits
only migration metadata, and exits non-zero on the first failure. Deployment
runs it to completion before any serving pod is updated.

Cluster provisioning must create the database and the `app_migrator` login
identity before this entrypoint can connect. Migrations own the application
roles, schemas, grants, and object DDL after that bootstrap boundary; no
committed password or cluster-owner credential is used. This precondition must
be reconciled explicitly when P3.12 authors operational-role DDL, because a
role cannot connect in order to create itself.

The `api`, `worker`, and `pindist` profiles disable Flyway and JDBC
autoconfiguration. Their R2DBC pools authenticate with their respective login
roles, which have no DDL or direct object privilege; no request path can invoke
the migration runner or obtain its credentials. Runtime startup may verify the
expected schema versions read-only but never migrates.

This feature defines locations, ownership, and entrypoint isolation only.
Expand/contract rules, forbidden-operation scanning, compatibility windows,
and CI stage 12 remain owned by `FEAT-PLAT-005`.

## P2.17 Isolation Telemetry

The decorator owns four monotonic counters. It emits each counter once at the
state transition where the event becomes certain:

| Metric | Tags | Emission point |
|---|---|---|
| `db_context_install_failure_total` | `role` from the closed role enum | Increment when either mandatory context statement fails and before rollback/cleanup starts |
| `db_context_missing_total` | None | Increment when a caller statement is refused because the wrapped connection is not in `READY` with an installed tenant/platform context |
| `db_role_assumption_total` | `role` from the closed role enum | Increment only after `SET LOCAL ROLE` succeeds; retries that never assume the role do not count |
| `db_connection_reset_failure_total` | None | Increment once when rollback, `RESET ROLE`, `RESET ALL`, or the pre-release assertion fails and the physical connection is marked for eviction |

Role is a bounded, non-user-controlled label. Tenant, actor, correlation,
connection, exception-message, and SQL labels are forbidden. Meter emission is
non-blocking and must not replace, suppress, or delay the security failure and
connection eviction path.

The two alerts owned by this design are:

| Alert | Condition | Severity | First action |
|---|---|---|---|
| Database security context missing | Any increment of `db_context_missing_total` | P1 | Stop or roll back the defective path; identify the R9 caller and run `ARC-VERIFY-024` before restoring it |
| Database role assumption failure | Any increment of `db_context_install_failure_total` | P2 | Compare the requested closed role with pool membership and the `ADR-023` enumeration; inspect migration/grant drift |

The alert implementation uses a short-window counter increase so a process
restart cannot erase an event. `db_role_assumption_total` provides the expected
role-usage baseline and `db_connection_reset_failure_total` feeds pool-security
diagnostics; this feature does not invent additional paging thresholds absent
from architecture section 16.4. P9.5 records the separate observability gap that
section 16.5 names no dedicated isolation dashboard panel.
