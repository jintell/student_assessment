# Metric Catalogue

Status: normative catalogue for metrics registered by the observability
baseline. This is the panel-input contract for `FEAT-OPS-004`.

The machine-enforced source is
`config/observability/metric-cardinality.json`. A metric is not live merely
because architecture names it: its owning feature must implement emission,
add an exact budget to that file, and pass `metricCardinalityGate` before an
operator may build a panel or alert from it.

## Naming and Labels

Metric names use lowercase `snake_case`. Monotonic counters end in `_total`;
base units use suffixes such as `_seconds`, `_bytes`, or `_ratio`. Names
describe measured facts rather than a library or implementation. Label keys
use the registered contract spelling and values come only from closed enums or
reviewed catalogues.

`tenantId` and `correlationId` are forbidden metric labels. Correlation may be
attached to a filtered exemplar, where supported, without becoming a series
dimension. Raw URI, exception text, SQL, personal data, identifiers, and
credentials are never labels. `maxSeries` is a hard registration budget, not
an operational target.

Every Micrometer meter also carries the common `runtime_role` dimension from
runtime bootstrap. Its closed values are `api`, `worker`, and `pindist`; it is
not repeated in each row's feature-owned label list. The same identity is the
OTLP resource attribute `service.runtime.role`, so logs, metrics, and spans can
be joined without maintaining three exporter configurations.

## Registered Metrics

### Audit integrity — P9.1

`AuditTelemetryConfiguration` registers the Micrometer adapter on `api` and
`worker`, not `pindist`. These are available meters, not proof of connected
business producers, a running verifier or deployed alerts. The current audit
assembly remains blocked under P8.8. The adapter implements the inward
`AuditIntegrityTelemetry` port, including the existing CAS telemetry seam.

The adapter lives only in `audit.infra.telemetry`. Conformance permits
Micrometer core instrumentation there while continuing to reject it in audit
domain/application/persistence code. Operational logging, tracing and imports
of platform observability internals remain forbidden across that boundary.

| Prometheus name | Kind / semantics | Feature labels | Series budget |
| --- | --- | --- | ---: |
| `audit_chain_verification_result` | Gauge: last complete verification, 1 success / 0 failure / NaN unknown | None | 1 |
| `audit_emit_failure_total` | Counter: emission failures propagated to the caller | None | 1 |
| `audit_checkpoint_overdue_count` | Gauge: overdue checkpoints in a complete sampled inventory | None | 1 |
| `audit_epoch_seal_total` | Counter: completed seal attempts | result: SEALED, DEFERRED | 2 |
| `audit_epoch_disposition_total` | Counter: disposition transitions | outcome: DISPOSED, HOLD_SUSPENDED, VERIFY_FAILED | 3 |
| `audit_epoch_seal_cas_retry_total` | Counter: lost CAS attempts causing recomputation | None | 1 |
| `audit_root_chain_fork_detected_total` | Counter: observed forks; initial zero does not prove verifier health | None | 1 |
| `audit_root_chain_seq` | Gauge: min/max over the complete sampled tenant-root inventory, never a sum | statistic: min, max | 2 |

Budgets follow the existing per-process feature-label convention; the common
runtime-role dimension and scrape targets must also be accounted for by the
exporter. No tenant, shard, entity or correlation ID is a metric label.
Root-range aggregates are an overview, not proof of any tenant's root density;
protected tenant evidence is still required for incident diagnosis.

Sampled gauges start as NaN. Invalid negative/reversed samples are rejected
without replacing a good sample. The collector must call `invalidateSamples`
when coverage/freshness is lost and publish only complete successful samples;
there is no automatic sampling or expiry scheduler in this adapter. Do not
publish a successful empty/incomplete verification as a healthy inventory.
Disposition completion must be recorded only after its required state is
persisted; failure classifications must not hide a possible post-detach failure.

P9.1's Prometheus tests prove all eight names, bounded outcome increments,
unknown/invalidation semantics and 12 series matching the registry budget.
Lock-wait/shard-skew measurement and exemplar resolution remain P9.2; live
fork paging and daily verification publication remain P9.3/P9.4.

### Outbox Addendum — TASK-PLAT4-OBS-001

These FEAT-PLAT-004 definitions fill the omission in architecture §16.2; they are not claimed as existing baseline text. `OutboxTelemetryConfiguration` registers the adapter on `worker`. The production relay assembly and backlog sampling hook remain blocked under outbox P8.5. Registered gauges start as `NaN` (unknown), not a healthy zero; only an explicit empty snapshot sets zero. The adapter rejects invalid negative snapshots without overwriting the last good sample. A live collector must refresh these cluster-wide values and provide freshness evidence before operational acceptance.

| Prometheus name | Kind / unit | Feature labels and values | Series budget |
|---|---|---|---:|
| `outbox_backlog_depth` | Gauge, rows | `state`: PENDING, CLAIMED, PUBLISHED, FAILED | 4 |
| `outbox_oldest_pending_age_seconds` | Gauge, seconds since oldest pending row was created; sampled empty backlog = 0 | None | 1 |
| `outbox_relay_published_total` | Counter, confirmed publications recorded as published | None | 1 |
| `outbox_relay_publish_failure_total` | Counter, failed attempts | `reason`: BROKER_NACK, CONFIRM_TIMEOUT, BROKER_UNAVAILABLE, UNCLASSIFIED | 4 |
| `outbox_relay_tick_total` | Counter, terminated tick attempts including lock losers, errors and cancellation | None | 1 |
| `outbox_relay_tick_duration_seconds` | Timer, tick attempt including lock acquisition/release; exports count, sum and max | None | 3 |
| `outbox_stale_claim_reclaimed_total` | Counter, expired claims returned to pending | None | 1 |
| `outbox_event_failed_total` | Counter, transitions into FAILED after exhausted attempts | None | 1 |
| `outbox_writer_appended_total` | Counter, successful appends before transaction commit; not committed events | None | 1 |
| `outbox_consumer_duplicate_total` | Counter, guard-suppressed deliveries | None | 1 |

No feature meter accepts tenant, correlation, event, aggregate, payload or exception labels in any spelling, including `tenant_id`. Runtime identity and infrastructure scrape labels remain the platform's bounded dimensions. Aggregate replica-local counters using `sum(rate(...))`. Cluster-wide backlog/age snapshots are duplicated across workers: use `max`, scoped to one deployment, not `sum`. Tick activity alone does not establish successful lock ownership or publication; correlate it with sweep success and publication progress. Failed sampling must not be presented as an empty backlog; freshness/missing-data alerting remains an Ops prerequisite.

### Baseline Catalogue

| Metric | Kind | Owning feature | Permitted labels | `maxSeries` |
|---|---|---|---|---:|
| `exam_started_total` | Counter | Exam lifecycle owner; contract owned by `FEAT-OBS-001` | None | 1 |
| `exam_finished_total` | Counter | Exam lifecycle owner; contract owned by `FEAT-OBS-001` | None | 1 |
| `pin_validation_total` | Counter | Exam-access owner; contract owned by `FEAT-OBS-001` | `outcome` | 8 |
| `result_published_total` | Counter | Result-publication owner; contract owned by `FEAT-OBS-001` | None | 1 |
| `correction_applied_total` | Counter | Correction owner; contract owned by `FEAT-OBS-001` | None | 1 |
| `provisional_feedback_released_total` | Counter | Feedback-release owner; contract owned by `FEAT-OBS-001` | None | 1 |
| `http_server_requests` | Timer | `FEAT-OBS-001` | `routeClass`, `audience`, `method`, `statusClass`, `outcome` | 2500 |
| `db_pool_acquire_duration` | Timer | `FEAT-OBS-001` | `workload` | 3 |
| `db_pool_pending` | Gauge | `FEAT-OBS-001` | `workload` | 3 |
| `db_query_duration` | Timer | `FEAT-OBS-001` | `slice`, `operation` | 320 |
| `redis_command_duration` | Timer | `FEAT-OBS-001` | `command`, `outcome` | 128 |
| `redis_errors_total` | Counter | `FEAT-OBS-001` | `command`, `reason` | 128 |
| `problem_detail_emitted_total` | Counter | `FEAT-PLAT-003`; catalogue governed by `FEAT-OBS-001` | `code` | 128 |
| `problem_detail_unmapped_total` | Counter | `FEAT-PLAT-003`; catalogue governed by `FEAT-OBS-001` | None | 1 |
| `idempotency_replay_total` | Counter | `FEAT-PLAT-003`; catalogue governed by `FEAT-OBS-001` | `outcome` | 3 |
| `idempotency_store_unavailable_total` | Counter | `FEAT-PLAT-003`; catalogue governed by `FEAT-OBS-001` | None | 1 |
| `db_context_missing_total` | Counter | `FEAT-PLAT-002`; catalogue governed by `FEAT-OBS-001` | None | 1 |
| `db_context_install_failure_total` | Counter | `FEAT-PLAT-002`; catalogue governed by `FEAT-OBS-001` | `role` | 14 |
| `db_role_assumption_total` | Counter | `FEAT-PLAT-002`; catalogue governed by `FEAT-OBS-001` | `role` | 14 |
| `db_connection_reset_failure_total` | Counter | `FEAT-PLAT-002`; catalogue governed by `FEAT-OBS-001` | None | 1 |
| `migration_lock_held_seconds` | Timer | `FEAT-PLAT-005`; catalogue governed by `FEAT-OBS-001` | `module`, `relation`, `lock_mode` | 720 |
| `migration_outcome_total` | Counter | `FEAT-PLAT-005`; catalogue governed by `FEAT-OBS-001` | `classification`, `outcome` | 18 |
| `telemetry_export_attempt_total` | Counter | `FEAT-OBS-001` | `signal` | 3 |
| `telemetry_export_success_total` | Counter | `FEAT-OBS-001` | `signal` | 3 |
| `telemetry_export_timeout_total` | Counter | `FEAT-OBS-001` | `signal` | 3 |
| `telemetry_span_dropped_total` | Counter | `FEAT-OBS-001` | `reason` | 4 |
| `telemetry_log_event_dropped_total` | Counter | `FEAT-OBS-001` | `reason` | 4 |
| `telemetry_metric_point_dropped_total` | Counter | `FEAT-OBS-001` | `reason` | 4 |
| `telemetry_redaction_rejection_total` | Counter | `FEAT-OBS-001` | `surface`, `reason` | 8 |
| `telemetry_export_queue_depth` | Gauge | `FEAT-OBS-001` | `signal` | 3 |
| `telemetry_export_queue_capacity` | Gauge | `FEAT-OBS-001` | `signal` | 3 |
| `rabbitmq_queue_depth` | Gauge | Broker-owning capability; common contract owned by `FEAT-OBS-001` | `queue` | 32 |
| `queue_consumer_utilisation` | Gauge | Broker-owning capability; common contract owned by `FEAT-OBS-001` | `queue` | 32 |
| `sweep_tick_total` | Counter | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `sweep`, `outcome` | 768 |
| `sweep_duration_seconds` | Timer | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `sweep`, `outcome` | 768 |
| `sweep_last_success_timestamp_seconds` | Gauge | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `sweep` | 128 |
| `sweep_lock_acquire_total` | Counter | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `sweep`, `result` | 256 |
| `sweep_batch_rows_total` | Counter | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `sweep` | 128 |
| `shutdown_phase_duration_seconds` | Timer | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `phase` | 1 |
| `shutdown_abandoned_units_total` | Counter | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `phase` | 1 |
| `runtime_heap_threshold_restart_total` | Counter | `FEAT-PLAT-006`; catalogue governed by `FEAT-OBS-001` | `reason` | 3 |

The six business counters are a closed `NFR-OBS-001` set. Their event code is
selected by the owning capability and recorded through `BusinessEventRecorder`;
feature code does not register alternative spellings. `pin_validation_total`
is the sole member with a dimension, using only `PinValidationOutcome`.

The self-observability `signal` catalogue is `trace`, `metric`, and `log`.
Drop reasons are `overflow`, `expiry`, `rejection`, and `export-failure`.
Redaction surfaces are `log`, `span`, `metric`, and `event`; reasons are
`prohibited-field` and `serialization-failure`.

Sweep names come from the validated registry catalogue and are capped at 128.
Sweep outcomes are `success`, `skipped_lock_not_acquired`,
`skipped_lock_lost`, `timeout`, `failed`, and `interrupted`; lock-acquisition
results are `acquired` and `not_acquired`. No sweep metric carries a tenant
identifier. `auto_submit_sweep_backlog` remains owned by `FEAT-DLV-004` and is
not registered by the runtime-role feature.

The runtime feature currently owns the `sweep` shutdown phase. Its abandoned
unit count is the number of queued ticks discarded when interval sources stop;
an active batch is not abandoned because shutdown waits for its transaction
boundary. Heap restart reasons are the closed `heap`, `container`, and
`measurement_failed` catalogue.

## Capability-Owned Expansion

Architecture section 16.2 also assigns exam-path, grading, authorization,
identity, notification, feedback-delivery, privacy, audit-integrity, and
durability metrics to their capability features. Those names remain reserved,
but are not represented as registered baseline metrics until their owning
feature supplies its emitter and exact budget. Every addition must declare:

1. instrument kind and base unit;
2. permitted label keys and their closed value catalogues;
3. the computed Cartesian-product bound as `maxSeries`;
4. emission ownership and the dashboard or SLO consumer; and
5. tests proving unknown labels and values are rejected.

Dynamic bounded catalogues use configured ceilings for active sessions,
replicas, queues, database roles, policies, shards, routes, and slices. Series
for closed sessions or stale replicas must expire. Metrics without an approved
dimension have a budget of one.

## Verification and Compatibility

Run `./gradlew metricCardinalityGate` to verify syntax, uniqueness, forbidden
labels, positive budgets, and completeness of the six business-event metrics.
Renaming or removing a metric, label, or label value is a breaking change for
`FEAT-OPS-004`; additions require catalogue review before panel adoption.
