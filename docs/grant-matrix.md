# Database Grant Matrix

Status: durable grant contract for `FEAT-PLAT-002`.

The executable source of truth is
`src/main/resources/db/grants/grant-matrix.json`. Generated migration SQL and
the live PostgreSQL grant-diff gate both consume that file. This document is
the reviewable contract; a grant change is made in the JSON source and must
keep this document consistent.

## Module Roles

The twelve `NOLOGIN` module roles are:

`app_tenancy`, `app_iam`, `app_academic`, `app_people`,
`app_questionbank`, `app_authoring`, `app_examaccess`, `app_delivery`,
`app_grading`, `app_result`, `app_correction`, and `app_notification`.

Each role receives only:

- `USAGE` on its same-named schema;
- `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on tables in that schema;
- `USAGE` on `audit` and `outbox`; and
- `INSERT` on `audit.audit_event` and `outbox.outbox_event` when those tables
  are created by `app_migrator`.

No module role receives object privilege in another module schema. No module
role may update or delete an audit object.

## Pool Login Roles

Pool login roles use `NOINHERIT` and ordinarily access objects only after a
transaction explicitly assumes one of their granted roles. One closed
exception supports the scheduler registry: `app_worker` has `USAGE` on the
`platform` schema and `SELECT` on `platform.sweep_registry`. It has no other
direct object privilege. `app_api` and `app_pindist` retain the absolute
no-direct-object-grant denial.

| Login role | Permitted memberships |
|---|---|
| `app_api` | All twelve module roles and `app_txn_examentry` |
| `app_worker` | All twelve module roles plus `app_outbox_relay` and `app_outbox_maintenance`; no composite role |
| `app_pindist` | `app_examaccess` only |

A missing `SET LOCAL ROLE` therefore leaves the connection unable to execute
business-module SQL; the worker can only read the scheduler registry directly.
Adding a membership or direct grant outside this table is a grant-matrix and
architecture change, not runtime configuration.

## Scheduler Registry Addendum (FEAT-PLAT-006 P10.4)

Architecture §9.2's advisory-lock registry is `platform.sweep_registry`, owned
by `app_migrator` and created by platform `V9__create_sweep_registry.sql`.
It is platform metadata, not tenant data: no tenant identifier or tenant RLS
policy applies. Its primary key is `sweep_name`; `lock_key` is a unique stored
`bigint`. Rows carry module/feature ownership, default interval in milliseconds,
enabled state, declared system actor and creation/update timestamps.
`V10__seed_sweep_registry.sql` reserves six disabled names; reserving a name
does not implement or enable its business sweep.

| Principal | Exact registry access |
|---|---|
| `app_worker` | `USAGE ON SCHEMA platform`; `SELECT ON TABLE platform.sweep_registry` |
| `app_api`, `app_pindist` | No registry access or registry-reader membership |
| Module roles and `app_readonly_ops` | No added registry privileges |
| `app_migrator` | Ownership and reviewed migration changes |

`V11__grant_sweep_registry_read.sql` and the executable grant matrix encode the
closed worker exception to ARC-DATA-026. It grants no `INSERT`, `UPDATE`,
`DELETE`, `TRUNCATE` or DDL capability and no access to other platform tables.
No new role was needed: the existing worker login receives these two exact
read-only facts, and advisory-lock acquisition itself requires no new object
grant. The proposed `app_sweep_registry_reader` role was rejected; see the
[resolution record](evidence/FEAT-PLAT-006/proposals/registry-reader-resolution.md).

The runtime still installs the enumerated sweep-coordination platform context
before registry SQL. This exception does not let a worker execute business SQL
without its module/tenant context. Registry keys are read, never recomputed per
tick. Changes follow the [registry runbook](runbooks/sweep-registry.md): the
audited operator mutation path remains blocked at runtime P6.9, so there is no
operator write grant or sanctioned ad hoc SQL update.

Verification: `GrantMatrixLoaderTest`, `PersistenceSecurityGatesIntegrationTest`
and `PersistenceFoundationIntegrationTest` cover the declared/live grants and registry
constraints. The persistence task list links this addendum as part of its
durable §9.2 matrix artifact.

## Outbox Role Addendum (FEAT-PLAT-004 P10.7)

Architecture §9.2 omitted the relay's read/update identity and partition
maintenance authority. TASK-PLAT4-DEFECT-001/002 record the additive resolution;
the executable matrix already contains these grants. Both roles are
`NOLOGIN`, `NOINHERIT`, `NOCREATEROLE`, non-owning and not RLS-bypass identities.
Only `app_worker` receives membership, with explicit assumption rather than
inherited privileges. `app_api`, `app_pindist` and module roles receive no
membership in either role.

| Role | Exact object authority | Denied authority |
|---|---|---|
| `app_outbox_relay` | `USAGE` on `outbox`; `SELECT, UPDATE` on `outbox.outbox_event` | No INSERT, DELETE, ownership, arbitrary DDL, business-schema grant or maintenance function grant |
| `app_outbox_maintenance` | `USAGE` on `outbox`; `EXECUTE` on `outbox.attach_week_partition(date)` and `outbox.detach_expired_partition(name)` | No direct row/payload access, table ownership, arbitrary DDL or other relation alteration |

The parent retains ENABLE/FORCE RLS. Module/composite writers have only INSERT
and the strict `app.tenant_id` predicate. Relay policy is role-scoped and also
requires `app.platform_scope = 'outbox_relay'`; install the approved platform
security context before assuming the relay role. It permits cross-tenant drain
without disabling RLS or changing the tenant predicate. Direct partition
access is locked down by outbox V9; access through the parent remains the
approved boundary.

Maintenance functions are owner-controlled SECURITY DEFINER routines with
fixed `search_path = pg_catalog, pg_temp`, validated partition identity,
serialized maintenance and age/state guards. PUBLIC execution is revoked;
`app_migrator` retains ownership. This narrowly delegates operations without
making the worker a DDL owner. The [staging detach release gate](outbox-release.md#partition-detach-release-gate)
still prohibits scheduling/manual detach until evidence is accepted; an
existing function grant is not release authorization.

Implementation: outbox migrations V5–V9 and
`src/main/resources/db/grants/grant-matrix.json`. Verification:
`OutboxRelayLeastPrivilegeIntegrationTest`, `OutboxRetentionIntegrationTest`,
`PersistenceSecurityGatesIntegrationTest` and the generated/live grant-diff
gates. These roles do not grant runtime configuration reads, operator redrive
access or a general-purpose operations console; those require their own
approved boundaries. No new grant or migration is introduced by this addendum.

## Exam-Entry Composite Role

`app_txn_examentry` is the single `ADR-023` MVP composite role. It is not a
member of a module role and its grants are written explicitly:

| Schema | Tables and privileges |
|---|---|
| `examaccess` | `SELECT`, `INSERT`, `UPDATE` on `exam_access_pin`, `pin_validation_guard`, `candidate_principal`, and `candidate_identity_verification` |
| `delivery` | `SELECT`, `INSERT` on `attempt`; `INSERT` on `attempt_question_evidence` and `attempt_presentation` |
| `people` | `SELECT` on `candidate` and `candidate_assignment` |
| `authoring` | `SELECT` on `exam_session`, `session_candidate`, `assessment`, `assessment_config`, `section`, and `section_item` |
| `tenancy` | `SELECT` on `tenant` |
| `audit` | `INSERT` on `audit_event` |
| `outbox` | `INSERT` on `outbox_event` |

It has no access to `grading`, `result`, `correction`, `notification`,
`questionbank`, `iam`, or `platform`; no write privilege in `people`,
`authoring`, or `tenancy`; no update or delete privilege on delivery answers;
and no update or delete privilege on audit or outbox objects. Its grant set is
strictly narrower than the union of the module roles it replaces.

## Operational Roles

| Role | Contract |
|---|---|
| `app_migrator` | The only DDL-capable application role. It owns the schemas and runs only through the migration entrypoint. |
| `app_readonly_ops` | `USAGE` on `platform` and `SELECT` on `platform.database_diagnostics`; no base-table or PII-bearing grant. |

## Default Privileges

For objects created by `app_migrator` in `audit` and `outbox`, default
privileges grant `INSERT` to every module role and `app_txn_examentry`.
Default privileges do not grant `UPDATE`, `DELETE`, sequence access, function
execution, or ownership.

## Verification

The grant-matrix loader rejects unknown identifiers, invalid privileges,
cycles, pool-login object grants outside the exact worker registry exception,
and facts contradicting a denial. CI
regenerates the repeatable grant migration and compares it byte-for-byte. The
PostgreSQL integration gate then requires the live roles, memberships, object
grants, default privileges, and denials to equal the normalized matrix with no
extra or missing fact.

The durable outbox table name is `outbox.outbox_event`, resolving
`TASK-PLAT2-DEFECT-002` consistently with the ownership and grant matrices.
