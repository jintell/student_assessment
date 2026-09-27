# Module Migration Ownership

Status: normative persistence note for module owners.

This document defines where a module migration lives and what it may own.
`docs/migration-authoring.md` remains the detailed source for expand/contract
headers, the closed DDL policy, release manifests, lock thresholds, and CI
stage 12.

## Location and History

Place a module-owned Flyway script under:

```text
src/main/resources/db/migration/<module>/V<next>__<description>.sql
```

`<module>` must be one of the fifteen names in `docs/schema-ownership.md` and
must equal the schema qualified in every statement. Each location has an
independent history table named `flyway_schema_history_<module>` in the shared
`platform_migrations` history schema. Versions are ordered within a location,
not as one repository-wide sequence.

Never edit a migration that may have run in a shared environment. Correct it
with a later forward migration in the same module location.

## Ownership Boundary

A migration may create, alter, index, constrain, or drop only objects in its
own schema. Every relation and index is explicitly schema-qualified.

It must not:

- reference or alter another module's table;
- create a foreign key whose source and target schemas differ;
- transfer ownership or grant privileges outside the canonical grant-matrix
  mechanism;
- create a shared-schema shortcut for cross-module reads;
- use dynamic SQL to evade schema analysis; or
- write another module's data as a backfill.

Cross-module references are stored as opaque identifiers. Integrity across
module boundaries belongs to exposed APIs, events, reconciliation, or an
approved `ADR-023` atomic flow, never a cross-schema foreign key.

## Tenant Tables

A table containing `tenant_id` must follow
`docs/tenant-scoped-tables.md` in the same migration: `tenant_id uuid NOT NULL`,
enabled and forced RLS, and exactly one strict `tenant_isolation` policy. The
catalogue gate discovers the column automatically.

## Execution Identity

Flyway runs only from the separate `--migrate-only` Kubernetes Job through its
JDBC datasource as `app_migrator`. The normal application profiles use R2DBC,
keep Flyway disabled, and authenticate as `app_api`, `app_worker`, or
`app_pindist`. Those pool login roles have no DDL privilege and cannot assume
`app_migrator`.

Application startup, request handlers, consumers, schedulers, and health checks
must never execute DDL. A schema change that is needed at runtime is still a
versioned migration deployed before traffic.

## Review Checklist

1. The directory, `cbt:module` header, and every qualified object name match.
2. The next version is unused in that module history and no applied file was
   edited.
3. Every statement stays inside the owning schema and no cross-schema foreign
   key exists.
4. Tenant tables apply the full RLS convention.
5. The change follows the expand/migrate/contract phase and closed DDL policy
   in `docs/migration-authoring.md`.
6. The release inputs, volumetric profile, and compatibility tests are updated
   where required.
7. Static migration checks, the PostgreSQL catalogue gates, and CI stage 12
   pass before deployment.
