# Database Secret Resolution

Database passwords are supplied by the deployment platform's external secret
manager and mounted as a Spring config tree. The default mount is
`/run/secrets/database/`; `CBT_DATABASE_SECRETS_PATH` may select a different
mount without carrying secret material itself.

The config tree uses one file per property:

| Property | Consumer |
|---|---|
| `cbt.database.roles.app-api.password` | API general and exam-path pools |
| `cbt.database.roles.app-worker.password` | Worker pool |
| `cbt.database.roles.app-pindist.password` | PIN-distribution pool |
| `cbt.database.roles.app-migrator.password` | `--migrate-only` JDBC connection |
| `cbt.database.roles.app-readonly-ops.password` | Read-replica diagnostic client |

The R2DBC URL is supplied separately as `CBT_DATABASE_URL`. The migration-only
process also requires `cbt.migration.jdbc-url` and
`cbt.migration.username=app_migrator`; neither value may contain a password.

Role migrations intentionally contain no `PASSWORD` clause. Cluster
provisioning creates or rotates each role credential from the same external
secret manager through a non-logging administrative channel. A serving pod
receives only its workload role's secret, while the migration job receives only
the migrator secret. The read-only operations credential is never mounted into
an application workload.
