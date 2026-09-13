# Tenant RLS Migration Template

Status: normative implementation note for `FEAT-PLAT-002` task `P4.14`.

Copy `src/main/resources/db/templates/tenant_rls.sql` into the versioned migration
that creates a tenant-scoped table. Replace `${schema}` and `${table}` with
lower-case, unquoted identifiers owned by that migration location. The template
directory is not a Flyway location and is never executed directly.

Apply the fragment after the table and its `tenant_id uuid NOT NULL` column are
created. Do not change `false` to `true`, omit `FORCE ROW LEVEL SECURITY`, rename
the policy, add a second policy, or replace either predicate. `USING` protects
reads, updates, and deletes; `WITH CHECK` protects inserts and updated rows.

The CI stage 8 catalogue gate discovers tenant-scoped tables mechanically from
the `tenant_id` column. A table is rejected unless it has enabled and forced RLS
and exactly one permissive `tenant_isolation` policy with the strict predicate
on both policy expressions. There is no table allowlist or platform bypass.
