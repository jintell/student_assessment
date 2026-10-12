# P3.4 Registry Reader Proposal Review

Date: 2026-10-12. Status: REJECTED.

The proposed `app_sweep_registry_reader` role is not adopted because P3.4
explicitly requires that no new database role be created. The task-owner
resolution is the narrower direct-grant exception: `app_worker` receives only
`USAGE` on `platform` and `SELECT` on `platform.sweep_registry`. `app_api` and
`app_pindist` receive neither a grant nor a membership.

## Rejected Alternative

The proposal would have preserved ARC-DATA-026's zero-direct-object-grant rule
for every pool login by revising runtime P3.4 to allow one dedicated capability
role: `app_sweep_registry_reader`.

## Proposed Task Acceptance

Grant SELECT on platform.sweep_registry to a dedicated NOLOGIN role, with
USAGE on platform and no other object privileges. Grant non-inherited,
explicitly assumable membership only to app_worker. The app_api and
app_pindist workloads receive neither membership nor direct registry grants.
All pool login roles continue to hold no direct object grants.

## Proposed Privileges

```sql
CREATE ROLE app_sweep_registry_reader
    NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA platform TO app_sweep_registry_reader;
GRANT SELECT ON TABLE platform.sweep_registry TO app_sweep_registry_reader;
GRANT app_sweep_registry_reader TO app_worker WITH INHERIT FALSE, SET TRUE;
```

This is review material, not an active migration. The implementation must use
the executable grant matrix as its source of truth and retain all existing
pool-login denials. The migration and live grant tests must prove that the
worker cannot read before explicit role assumption, can read afterward, and
cannot modify the registry. API and PIN-distribution roles must be unable to
assume the reader role or read the registry.

P2.3 and P2.14's design references to direct app_worker access would be updated
to this role. P4.7 remains the owner of the runtime security-context protocol;
its registry transaction must install the dedicated role and closed platform
marker before executing SQL. No general business privileges or tenant-policy
bypass are introduced by this proposal.

This alternative would change P3.4's explicit "no new database role"
acceptance criterion and is therefore closed without implementation. The
approved direct grants are recorded in the executable matrix, constrained by
its loader to these exact two privilege facts, and verified against the live
PostgreSQL catalogue.
