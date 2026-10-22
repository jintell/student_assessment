# Outbox backlog collection

The worker now declares `outbox-backlog` in the common sweep lane. Its
`OUTBOX_RELAY` actor uses `SweepTransactions` with `SweepDataModule.OUTBOX`,
the existing secured worker pool, `app_outbox_relay`, and platform scope
`outbox_relay`. No role grants, RLS policies, pool sizes or lock permits change.
The allocation remains 4 grading + 2 sweep-lock + 4 background = 10.
The relay's separate lane cannot suppress sampling when publication fails.

## Collection contract and enablement

`V12__reserve_outbox_backlog_sweep.sql` reserves a **disabled** identity. The
60-second cadence, 120-second expiry and 2-second timeout are candidate local
defaults, not an agreed production contract. FEAT-PLAT-006 / FEAT-OBS-001 must
approve cadence, expiry, timeout and detection/delivery budget; FEAT-PLAT-002
must review the existing relay actor/scope reuse. Enable through a reviewed
additive registry migration only after these and the operating prerequisites
are satisfied. No staging target or owner identity has been assumed.

| External configuration | Candidate default | Constraint |
|---|---|---|
| `CBT_OUTBOX_BACKLOG_INTERVAL` | `60s` | 1 second–24 hours; must equal registry interval at startup |
| `CBT_OUTBOX_BACKLOG_MAXIMUM_SAMPLE_AGE` | `120s` | Greater than interval plus timeout; include scheduler contention and scrape/evaluation delay in review |
| `CBT_OUTBOX_BACKLOG_QUERY_TIMEOUT` | `2s` | At least 1 ms, less than the 4-second runtime batch ceiling |
| `MANAGEMENT_ENDPOINT_PROMETHEUS_ENABLED` | disabled | Set `true` only with approved internal authenticated scrape access |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health` | When enabled, exactly `health,prometheus` |

The worker always declares the sampler bean so its inventory matches
`contracts/runtime-roles/worker.json`. A disabled registry row does no work and
exports freshness zero and unknown backlog. Registry validation rejects missing
definitions and interval mismatches. Changing registry enablement requires worker
restart. The sampler is independent of `outbox-relay` enablement; a staged relay
hold may leave collection enabled via separately reviewed registry configuration.

## Snapshot, clocks and failures

One aggregate statement counts **all** rows in all four states, including
PENDING retries scheduled in the future. The minimum PENDING `created_at` and
PostgreSQL `statement_timestamp()` use the same statement snapshot and database
clock. Age ignores `occurred_at` and retry/claim eligibility, clamps future
creation times to zero, and is refreshed even when no new rows arrive. A complete
empty snapshot yields four zero counts and zero age.

Only a successfully committed/released transaction replaces the immutable
snapshot. Failure, timeout and cancellation increment a label-free failure
counter and never publish zero. Both a transaction-local PostgreSQL
`statement_timeout` and a reactive timeout bound collection; the database
timeout also prevents cancelled queries from indefinitely retaining a connection.
An AtomicReference publishes counts, age and observation time together. Separate
meter reads can straddle an update; the underlying database observation itself
is coherent and publication never mutates individual state counts.

Freshness uses monotonic elapsed time from collection start, including pool
acquisition/query/commit time. Application/database wall-clock skew cannot keep
a cached snapshot fresh. The last-success timestamp is database UTC epoch time.
Scraping reads only cached memory. At expiry depth and age become NaN, freshness
becomes zero, and last-success remains available for diagnosis. Restarts start
unknown. After ownership transfer, old snapshots expire independently; `max`
across fresh copies avoids double-counting, with bounded conservative overlap
until the former owner's expiry. The next scheduled lock winner collects again.

Counting all retained states requires scans. The local 10,006-row secured plan
uses partition sequential scans; no new index is justified by that sample.
Production retention volumes and concurrent sweep contention need owner-budget
measurement before enabling. Sampling never runs on the 200 ms relay cadence.

## Ops adoption

Reuse `config/alerting/outbox-backlog.rules.yml`: strict PENDING depth > 5000 OR
age > 300 seconds, severity p2, no `for` delay. Install that artifact in the
maintained Prometheus rule configuration; do not create another baseline rule.
`cluster`, `namespace`, `job` must uniquely identify one monitored database.
Review an additional bounded deployment label if that tuple is insufficient.

`config/observability/outbox-backlog-scrape.yml.template` is an unrendered
authenticated TLS scrape fragment. Ops supplies target inventory, labels,
cadence, CA and a renewable bearer-token secret accepted by the worker's OAuth2
resource-server issuer configuration. Enable management TLS (or the approved
internal TLS proxy) before using it. No public ingress is needed. Apply the
worker management NetworkPolicy template with the real monitoring namespace;
review existing policies because Kubernetes allow rules are additive. Management
remains on 9091 and business HTTP remains disabled (`server.port=-1`). Security
authentication is not bypassed by business-admission exclusions.

Import `config/observability/outbox-backlog-dashboard.json` into the maintained
dashboard, select its Prometheus datasource, and set exact cluster/namespace/job
and the deployed runbook URL. It provides rows, seconds, PENDING/age threshold
overlays, freshness and last-success time; null/NaN must remain unknown. This is
an import artifact, not evidence of Grafana adoption or visual verification.
Broader throughput/DLQ work remains with its P9.4 owners.

Ops must configure the approved P2 receiver, grouping/repeat intervals and
resolved delivery; replace the repository-relative alert runbook annotation in
the rendered deployment with the approved accessible URL. Retain the rendered
rule/routing configuration without credentials. Separately agree detection for
`up == 0`, absent targets and no fresh collector. A threshold alert becoming
inactive while collection is lost is **not recovery**. Deployment collection
readiness requires a current successful scrape and at least one fresh snapshot;
health endpoint success alone does not satisfy this check. No fifth baseline
alert or application-readiness dependency is silently introduced.

## Verification and live handoff

Run `./gradlew verifyOutboxAlerts`. It invokes `bash ci/verify-outbox-alerts`
with the multi-platform Prometheus 3.10.0 image pinned by registry digest. Both
rule syntax and semantics block `ciStage8` (the hosted integration job already
invokes that stage). `ciStage9` remains the independent event contract gate.

`OutboxBacklogIntegrationTest` exercises the production configuration and secured
PostgreSQL transactions, reads actual Prometheus exposition and records the plan.
`OutboxBacklogSchedulingIntegrationTest` runs the real runner, registry and lock
adapter alongside a failed relay. Micrometer tests cover expiry/restart/ownership
handoff without sleeping. HTTP tests prove management authentication and cached
exposition with the business listener disabled.

Live P9.2/P7.22 acceptance still requires FEAT-OPS-004's staging target, deployment
owner, rule owner, approved test receiver, dashboard adoption, collection-loss
decision and timing contract, plus P8.5/P8.6 production relay/broker prerequisites.
Follow FIX-OUTBOX-722-06 in `docs/resolution/outbos-7.22-fix.md`: independently
trigger depth and age using committed synthetic events, hold the relay through
approved runtime configuration while sampling continues, resume normal processing,
and retain real notification and recovery evidence. Do not rewrite event states
to clear alerts. Retain revision, UTC times, database/scrape samples, routed
notification references, dashboard exports and detection/delivery measurements
under the agreed staging evidence destination. These external details remain
unprovided, so live enablement and acceptance are BLOCKED.
