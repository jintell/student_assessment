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

Pool login roles hold **no direct object grants of any kind**. They have no
schema, table, sequence, function, or view privilege. They use `NOINHERIT` and
may access an object only after a transaction explicitly assumes one of their
granted roles.

| Login role | Permitted memberships |
|---|---|
| `app_api` | All twelve module roles and `app_txn_examentry` |
| `app_worker` | All twelve module roles; no composite role |
| `app_pindist` | `app_examaccess` only |

A missing `SET LOCAL ROLE` therefore leaves the connection unable to execute
application SQL. Adding a membership outside this table is a grant-matrix and
architecture change, not runtime configuration.

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
cycles, login-role object grants, and facts contradicting a denial. CI
regenerates the repeatable grant migration and compares it byte-for-byte. The
PostgreSQL integration gate then requires the live roles, memberships, object
grants, default privileges, and denials to equal the normalized matrix with no
extra or missing fact.

The durable outbox table name is `outbox.outbox_event`, resolving
`TASK-PLAT2-DEFECT-002` consistently with the ownership and grant matrices.
