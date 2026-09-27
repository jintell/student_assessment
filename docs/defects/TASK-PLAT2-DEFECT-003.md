# TASK-PLAT2-DEFECT-003 Schema Count Mismatch

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Baseline Defect

Architecture section 7.3 states twelve contexts and twelve schemas, while
section 9.2 enumerates fifteen schemas. The latter contains twelve bounded-
context module schemas plus the `audit`, `outbox`, and `platform` platform
schemas.

## Resolution Adopted by This Feature

The platform provisions fifteen schemas: twelve one-to-one module schemas and
three platform schemas. `docs/schema-ownership.md`, `MigrationSchema`, Flyway
locations, grant generation, and catalogue gates use that closed set.

## Next-Baseline Action

Change the section 7.3 shorthand to "twelve bounded-context module schemas plus
three platform schemas" and cross-reference the complete section 9.2 table.
Keep the distinction between module count and total schema count explicit.
