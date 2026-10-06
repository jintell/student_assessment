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

## Registered Metrics

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

The six business counters are a closed `NFR-OBS-001` set. Their event code is
selected by the owning capability and recorded through `BusinessEventRecorder`;
feature code does not register alternative spellings. `pin_validation_total`
is the sole member with a dimension, using only `PinValidationOutcome`.

The self-observability `signal` catalogue is `trace`, `metric`, and `log`.
Drop reasons are `overflow`, `expiry`, `rejection`, and `export-failure`.
Redaction surfaces are `log`, `span`, `metric`, and `event`; reasons are
`prohibited-field` and `serialization-failure`.

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
