# ADR-019 DDL Safety Approval

Status: APPROVED
Architecture authority: ADR-019
Ratified architecture commit: `8ea8250ef7ef6877eaf2d89444d473f7a4933fbc`

## Forbidden Operations

- Renaming or dropping a column still read by the previous version.
- Adding a `NOT NULL` column without a default.
- A blocking `ALTER TABLE` on `answer`, `attempt`, or `audit_event`.
- `CREATE INDEX` without `CONCURRENTLY`.

## Enforcement Policy

Permitted DDL shapes form a closed allowlist.
Unrecognized DDL shapes fail validation.
Adding a permitted shape requires reviewed approval and may not be performed
as a configuration-only change.

The signed approval record is `ci/dor/P0.4-closed-ddl-allowlist.json`.
