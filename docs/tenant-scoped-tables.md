# Tenant-Scoped Table Authoring Guide

Status: normative guide for every feature that creates a tenant-scoped table.

A table is tenant-scoped when it contains a live column named exactly
`tenant_id`. The CI catalogue gate discovers this mechanically; there is no
table allowlist or naming-based exemption.

## Column Convention

Declare the tenant key in the table-creation migration:

```sql
tenant_id uuid NOT NULL
```

Use the lower-case, unquoted name `tenant_id`, PostgreSQL type `uuid`, and a
`NOT NULL` constraint. The value is the platform `TenantId`; do not use text,
an integer surrogate, a nullable value, a sentinel, or a separately named
tenant column.

Slice-local query methods must accept `TenantId` and include the tenant
predicate even though RLS is present. RLS is the independent backstop, not a
replacement for rule R5.

## Required Migration Fragment

After creating the table and its `tenant_id` column, copy
`src/main/resources/db/templates/tenant_rls.sql` into the same versioned
migration and substitute lower-case identifiers owned by the module:

```sql
ALTER TABLE <schema>.<table> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.<table> FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON <schema>.<table>
    AS PERMISSIVE
    FOR ALL
    TO PUBLIC
    USING (tenant_id = current_setting('app.tenant_id', false)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid);
```

`USING` protects reads, updates, and deletes. `WITH CHECK` protects inserts and
the new row produced by an update. `FORCE` applies the policy to the table
owner. The literal `false` makes a missing setting raise rather than become a
nullable or permissive scope.

The template directory is not a Flyway location and is never executed
directly. Do not edit the template into a generic runtime DDL helper.

## Catalogue Gate

CI stage 8 selects every ordinary or partitioned relation in an application
schema with a non-dropped `tenant_id` column. It rejects the table with
`RLS_CATALOG_GATE: <schema>.<table>` when any of these conditions is true:

- RLS is not enabled;
- RLS is not forced;
- there is zero policy or more than one policy;
- the policy name is not exactly `tenant_isolation`;
- the policy does not apply to all commands;
- the policy is restrictive rather than permissive;
- the policy roles are not `PUBLIC`;
- either `USING` or `WITH CHECK` is missing;
- either expression does not compare `tenant_id` with the strict
  `current_setting('app.tenant_id', false)::uuid` value; or
- an additional policy could widen access through PostgreSQL's permissive
  policy composition.

Do not change `false` to `true`, omit `FORCE`, scope the policy to a module
role, add a convenience policy, or create a test-only bypass reachable from a
production profile.

## Author Checklist

Before submitting the migration:

1. Confirm the table is in the owning module's schema and migration location.
2. Declare `tenant_id uuid NOT NULL` in the initial table definition.
3. Apply the complete RLS fragment after `CREATE TABLE`.
4. Add indexes appropriate to the slice's access pattern, including tenant
   discrimination where the query shape needs it.
5. Keep cross-module references as identifiers; never add a cross-schema
   foreign key.
6. Add repository and slice tests for tenant A versus tenant B reads, writes,
   and enumeration.
7. Run the real-PostgreSQL catalogue and isolation gates.

Platform-scope work does not exempt a tenant table from RLS. It uses the closed
platform-operation protocol and an explicitly authorised transaction context.
