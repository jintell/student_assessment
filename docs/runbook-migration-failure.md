# Failed Migration Runbook

Use this runbook when the migration Job, stage 12, or a migration alert reports
failure. Keep the release blocked until the incident has a classified cause and
a reviewed recovery action. Never rerun blindly.

## Preserve evidence first

Record the release, environment, Job UID, immutable image digest, release
manifest checksum, classification, and incident reference. Retain:

- Job status and logs;
- `migration-lock-duration-report.json` and its Markdown summary;
- the published release manifest;
- the affected module's Flyway schema-history rows; and
- `pg_locks` and invalid-index query results.

Do not place JDBC URLs, credentials, SQL literal data, incident narrative, or
approver identities in metric labels or copied public logs.

## Read the report

Start with `overallVerdict` and `failures` in the JSON report. For each failure,
use `migrationPath`, `statementOrdinal`, `relation`, and the matching statement
entry. Confirm the dataset seed/profile, PostgreSQL image digest, migration-set
checksum, threshold-source checksum, sampling completeness, and maximum sample
gap before trusting the measurement.

| Signal | Classification | Next action |
|---|---|---|
| `LOCK_THRESHOLD_EXCEEDED`, `LOCK_THRESHOLD_FAILED`, or SQLSTATE `55P03` | Lock failure | Follow **Lock failure** below. |
| A named analyser rejection such as `NON_CONCURRENT_INDEX` | Forbidden operation | Follow **Forbidden operation** below. |
| Failed non-transactional concurrent index and `indisvalid = false` | Reconciliation required | Follow **Invalid concurrent index** below. |
| Failed transactional migration | Transaction rolled back | Verify history and schema state, then prepare a forward fix. |
| Compatibility failure | N-1 cannot run against N schema | Do not deploy or roll back; fix compatibility and rerun stage 12. |

## Lock failure

1. Confirm whether a scheduled session is open. A `SOURCE_UNKNOWN` freeze result
   is not permission to continue; restore the authoritative source.
2. Match the report's schema-qualified relation and lock mode to current
   `pg_locks`/`pg_stat_activity`. Do not terminate a candidate transaction as a
   migration recovery shortcut.
3. Keep the release blocked. Let the configured `lock_timeout` fail the
   migration rather than increasing or disabling it.
4. Prepare a forward fix that reduces the lock shape or separates the change.
   Rerun stage 12 against the production-shaped profile before rescheduling.

## Forbidden operation

1. Read every analyser violation; one failure must not hide another.
2. Change the migration to a permitted expand/contract shape. Common fixes are
   adding a replacement column instead of renaming, adding a default before
   `NOT NULL`, or using `CREATE INDEX CONCURRENTLY` in a non-transactional
   script.
3. Never edit a migration that has been applied in any shared environment. Add
   a new forward migration.
4. Run `./gradlew :migration-verify:check ciStage12` and attach the new report to
   the incident/change record.

## Invalid concurrent index

Only use this path for a failed non-transactional migration whose header names
the module and expected index.

1. Confirm the failed version in that module's
   `platform_migrations.flyway_schema_history_<module>` table.
2. Query `pg_index`, `pg_class`, and `pg_namespace` for the expected index and
   its `_ccnew`/`_ccold` artifacts. Stop if any collision is valid or belongs to
   another schema/table.
3. Run the repository reconciler as `app_migrator` using a credential supplied
   by the approved secret mechanism:

   ```bash
   ./gradlew :migration-verify:run --args="reconcile-invalid-index <jdbc-url> <module> <failed-version> <module.index_name>"
   ```

   Set `CBT_MIGRATION_USERNAME=app_migrator` and
   `CBT_MIGRATION_PASSWORD` only in the controlled operator environment. The
   reconciler takes a module/version advisory lock and issues only
   schema-qualified `DROP INDEX CONCURRENTLY IF EXISTS` for attributable,
   invalid artifacts. It is safe to repeat.

## Flyway schema-history repair

Repair is allowed only after invalid artifacts are absent and the incident has
reviewed evidence for the exact module and failed version.

1. Snapshot every successful row's version, description, type, script, and
   checksum from the module history table. Compare it with the resolved
   migrations from the immutable release image. Any drift stops the repair.
2. Configure Flyway with only the affected module location, default schema
   `platform_migrations`, and table
   `flyway_schema_history_<module>`. Connect as `app_migrator` through the
   controlled migration environment.
3. Invoke Flyway `repair`. Do not delete or update schema-history rows by hand.
4. Compare the successful-row snapshot again. It must be byte-for-byte
   unchanged, and only the failed row may have been removed.
5. Repeat the invalid-index query and the static analyser. Then retry the new or
   unchanged reviewed migration through the normal release pipeline.

If the approved Flyway repair invocation for the target environment is not
available, stop and escalate to Platform Ops. An ad hoc SQL edit is not an
acceptable substitute.

## Why forward fix is preferred

Schema rollback can invalidate code/data compatibility, reacquire the same
blocking locks, and turn an additive failure into data loss. Keep the current
schema, halt rollout, and ship a reviewed forward migration. A `CONTRACT`
release is never rolled back; an `EXPAND` failure is also repaired forward.

Close the incident only after the Job succeeds, stage 12 is green, the relevant
metrics export, and application health/compatibility checks pass.
