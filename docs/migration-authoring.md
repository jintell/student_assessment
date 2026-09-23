# Migration Authoring Guide

This is the required authoring guide for every module-owned Flyway migration.
It adds the `FEAT-PLAT-005` verification contract to the per-module location,
ownership, and privilege rules established by `FEAT-PLAT-002`.

## Create the Script

Place a new, forward-only migration under:

```text
src/main/resources/db/migration/<module>/V<next>__<description>.sql
```

Never edit a migration that may have run in a shared environment. Add a later
versioned migration as the forward fix.

Every file starts with exactly these four lines, in this order, before any SQL
or other comment:

```sql
-- cbt:phase EXPAND
-- cbt:module delivery
-- cbt:transactional false
-- cbt:justification FEAT-DLV-002 add the answer lookup index
```

- `cbt:phase` is exactly `EXPAND`, `MIGRATE`, or `CONTRACT`.
- `cbt:module` matches both the migration directory and the owned schema.
- `cbt:transactional` is `false` for the admitted `CREATE INDEX CONCURRENTLY`
  and `DROP INDEX CONCURRENTLY` shapes; other currently admitted shapes use
  `true`. `REINDEX CONCURRENTLY` also requires a non-transactional header if a
  future allowlist revision admits it, but the current closed allowlist rejects
  it.
- `cbt:justification` is a non-empty, single-line feature/change explanation.

The file is UTF-8 without a byte-order mark and uses LF line endings. Directive
names and values are case-sensitive. Missing, extra, reordered, duplicated, or
malformed directives fail before SQL analysis; no value is inferred.

## Stay Inside the Module

Every relation and index is explicitly schema-qualified. The qualifier must
equal `cbt:module`; an unqualified or foreign-schema reference fails stage 12.
A module does not create cross-schema foreign keys or change another module's
tables. Cross-module coordination belongs in application contracts and events.

The runtime application remains DDL-free. Only the migration Job connects as
`app_migrator`; application pool roles cannot use migration credentials.

## Use the Closed DDL Policy

Choose a statement from `docs/migration-forbidden-operations.md`. Unknown
shapes fail closed. In particular:

- create every index with `CONCURRENTLY`;
- add constraints as `NOT VALID`, then validate in the appropriate later
  phase;
- keep backfill DML out of Flyway and use the worker-role backfill harness;
- put one `ALTER TABLE` operation in each statement; and
- never use `CASCADE`, dynamic SQL, privilege changes, or transaction-control
  statements.

`CONCURRENTLY` is mandatory because a regular PostgreSQL index build blocks
writes. It must not be wrapped in a Flyway transaction. A failed concurrent
build can leave an `INVALID` index; follow `docs/runbook-migration-failure.md`
to drop it concurrently and repair Flyway history before retrying.

## Update Release Inputs

For every shipped migration:

1. Add the script to `migration/release-manifest-input.json` under the one
   classification declared for the release.
2. Do not mix `EXPAND`, `MIGRATE`, and `CONTRACT` classifications in a release.
3. Add any new exam-critical table to
   `migration/exam-critical-tables.yaml`.
4. Add every new table to `migration/volumetrics.yaml`, including a realistic
   synthetic row-count expression and generation order.
5. Add or update analyser, migration, and N-1 compatibility tests for the
   touched relations.

## What Stage 12 Rejects

Stage 12 blocks the release for any of these conditions:

- invalid headers, a directory/module mismatch, or a cross-schema reference;
- a statement outside the closed phase-specific allowlist;
- a rename or drop still visible to N-1, unsafe `NOT NULL`, blocking critical
  `ALTER TABLE`, non-concurrent index, or Flyway DML/backfill;
- a transactional concurrent-index script or a mixed release classification;
- an unsigned or changed threshold, allowlist, or dataset approval input;
- a missing/unresolvable retained previous image;
- a relation lock beyond its approved threshold; or
- failed N-1 reads or writes against the migrated schema.

Failures are release blockers. Do not bypass the gate, weaken a threshold, or
roll back schema history to make the build pass.

## Run Locally

Run the static/unit checks first:

```bash
./gradlew :migration-verify:check
```

Then run the same stage used by CI:

```bash
CBT_PREVIOUS_IMAGE_DIGEST='sha256:<retained-digest>' \
CBT_IMAGE_REPOSITORY='ghcr.io/meldtech/cbt-platform' \
./ci/stage-12
```

The full stage requires JDK 21, a working Docker-compatible daemon, the pinned
PostgreSQL 17 image, and access to the retained immutable N-1 image. It emits
the generated dataset manifest, lock-duration report, compatibility report,
and release manifest under `build/`; generated output is not committed.

Before review, also run the repository compile check:

```bash
./gradlew compileJava compileTestJava
```
