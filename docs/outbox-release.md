# Outbox Release and Rollback

## Migration-First Deployment

Run the dedicated migration Job from `deploy/kubernetes/migration-job.yaml.template` using the release's immutable application image, migration identity and `--migrate-only` entrypoint. Its successful completion must precede background pod rollout. The migration Job creates the outbox schema objects, relay/maintenance roles, grants, RLS policies, configuration rows and registry reservations. A failed Job blocks rollout; runtime pods do not repair schema or run migrations.

Keep the production relay reservation disabled until P8.5's assembly and manifest prerequisites are delivered. Broker provisioning and identity verification are separate prerequisites recorded in [P8.6](evidence/FEAT-PLAT-004/P8.6-staging-broker-provisioning.md).

`OutboxDatabasePrerequisites` is a worker-only bootstrap check. Before lifecycle participants start, it uses the existing secured worker connection to verify that `app_outbox_relay` exists, the worker login can assume it, and `outbox.outbox_event` is a partitioned table. The read runs in the existing bootstrap coordination transaction, returns/discards its connection on termination, and has a ten-second timeout. It adds no pool or scheduler. Failure aborts startup with `OUTBOX_DATABASE_NOT_READY`, without exposing driver diagnostics. The bounded wait occurs on Spring's bootstrap thread, not a request event loop.

`OutboxDatabasePrerequisitesIntegrationTest` proves successful migrated startup and refusal of a missing relay role, missing table or revoked role membership against PostgreSQL and the secured worker pools. It also proves request and isolated profiles require no startup connection for this component. This safety check remains active while the production relay assembly is blocked; it does not enable publication. The deployment pipeline still owns Job-before-rollout sequencing, and startup validation is its fail-fast backstop.

## Code Rollback

1. Stop new relay work and drain in-flight work through the runtime shutdown contract. A crash may leave a durable claim, which the reclamation path must recover before resumed publication.
2. Roll back the application image/configuration to the compatible prior release. Do not reverse Flyway migrations, drop roles/tables, erase pending rows or rewrite migration checksums. The outbox changes are additive; retained rows and roles remain while the relay stops draining.
3. Expect backlog growth while publication is stopped. Preserve committed events and consumer deduplication records. Reconcile claimed/failed states when resuming; redelivery is possible, and consumers must remain idempotent. Do not bypass the compatibility gate or deploy an older consumer that cannot handle already-published event versions.
4. Re-enable publication only after migrations, broker access and relay startup checks pass. Verify drain progress and absence of duplicate effects.

## Partition Detach Release Gate

**Partition detach stays disabled until P7.18 passes against staging and its audit-survival evidence is retained.** The local P7.18 result is not staging authorization. Do not schedule or invoke `outbox.detach_expired_partition` through a deployment, external job or operator procedure before that gate is met. There is currently no production detach scheduler in this repository; do not introduce one as part of rollback.

Treat **detaching a partition** as the irreversible operation of the automatic release rollback contract: an image rollback cannot restore its parent-table visibility or recover removed data. PostgreSQL can technically reattach a retained table after an independently reviewed recovery procedure; this release does not automate that, and dropping the detached table requires backup recovery. The outbox is a transport log, while the independently retained audit record is the durable evidence.

`app_outbox_maintenance` already has the guarded detach function grant and is assumable by `app_worker`; this release gate is operational, not a claim that the database grant is disabled. Before staging proof, leave maintenance unscheduled and prohibit manual detach. A deployment owner must verify external CronJobs/automation also respect this restriction. Once permitted, the function still refuses unresolved rows and publications younger than seven days.

Rollback acceptance requires a compatible prior image, preserved database contents, no automatic down-migration, no automatic partition reattachment and independently retained audit evidence. No live rollback or staging detach was performed in this task chunk.
