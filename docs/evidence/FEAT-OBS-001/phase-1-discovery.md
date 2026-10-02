# FEAT-OBS-001 Phase 1 Discovery Record

Date: 2026-10-02
Architecture baseline: `arch-v1.4`
Primary sources: architecture sections 15.2, 16.1-16.3, 17.5, 18.1,
and 19.8; plan sections 8.0, 8.1, 10, 11.2, and 14.4; requirements
`REQ-SEC-004`, `REQ-RSLT-021`, `REQ-PRIV-004`, and `REQ-AUD-002`

This is the working artifact for observability tasks `P1.1`-`P1.10`. It
preserves approved source wording, assigns each contract to a producer or
consumer, and records source defects without inventing replacement
architecture identifiers.

## P1.1 Structured-Log Field Provenance

| Architecture section 16.1 field | Presence | Value producer | Emission owner and rule |
|---|---|---|---|
| `timestamp` | Always | `FEAT-OBS-001` | The structured encoder emits RFC 3339 UTC. |
| `level` | Always | `FEAT-OBS-001` | Derived from the logging event. |
| `logger` | Always | `FEAT-OBS-001` | Derived from the SLF4J logger name. |
| `message` | Always | `FEAT-OBS-001` | Structured message text after redaction. |
| `correlationId` | Always | Kernel contract from `FEAT-PLAT-003` | `FEAT-OBS-001` reads the propagated value; the same value is returned in `X-Correlation-Id` and every `ProblemDetail`. |
| `traceId` | Always | OpenTelemetry SDK | `FEAT-OBS-001` enriches the log event from the active or synthetic root span. |
| `spanId` | Always | OpenTelemetry SDK | `FEAT-OBS-001` enriches the log event from the active or synthetic root span. |
| `actorType` | Authenticated requests | Kernel `ActorContext` from `FEAT-PLAT-003` | `FEAT-OBS-001` consumes propagated context. |
| `actorId` | Authenticated requests | Kernel `ActorContext` from `FEAT-PLAT-003` | Safe opaque identifier only; never an email or name. |
| `tenantId` | Tenant-scoped operations | Kernel `ActorContext` from `FEAT-PLAT-003` | Kept in protected logs and spans, never used as a metric label. |
| `module` | Always | `FEAT-PLAT-001` log-field contract | `FEAT-OBS-001` serializes the fixed module value. |
| `slice` | Always | `FEAT-PLAT-001` log-field contract | `FEAT-OBS-001` serializes the `<module>.<verbNoun>` slice value. |
| `eventCode` | Business events | `FEAT-OBS-001` contract, value selected by the owning capability | Stable code aligned with the audit event type. |
| `errorCode` | Failures | Kernel error catalogue from `FEAT-PLAT-003` | Same code as the client-facing `ProblemDetail`. |
| `durationMs` | Requests | `FEAT-OBS-001` | Measured for every completed request. |
| `dbQueryCount` | Requests | `FEAT-OBS-001` | Reactor-context-scoped count, independent of trace sampling. |
| `error.stack` | Exceptions | `FEAT-OBS-001` | Structured, redacted exception field; production output remains one physical line. |

All unconditional fields have one named producer. `FEAT-PLAT-001` owns the
existing propagation and log-field contracts; this feature owns structured
serialization and does not rebuild those mechanisms.

## P1.2 Metric Ownership

The section 16.2 catalogue is partitioned by registration owner. "Here" means
the Phase 0 observability baseline registers the cross-cutting instrument or
closed business-event contract. "Capability" means the feature that owns the
state transition or resource emits it through the baseline mechanism.

| Catalogue category | Metrics | Registration partition and owner |
|---|---|---|
| Golden signals | `http_server_requests` by route class and audience; `db_pool_acquire_duration`, `db_pool_pending`, `db_query_duration` by slice; `redis_command_duration`, `redis_errors_total`; `rabbitmq_queue_depth`, `queue_consumer_utilisation` by queue | Here: `FEAT-OBS-001` supplies the common HTTP, R2DBC, Redis, and broker instruments. The owning feature supplies stable route, slice, and queue metadata. |
| Exam path SLI | `exam_entry_duration`, `answer_accept_duration`, `attempt_submit_duration`; `concurrent_active_attempts` by session and platform-wide; `auto_submit_sweep_backlog`, `auto_submit_drain_duration` | Capability: exam delivery and attempt-lifecycle owners. |
| Feedback SLO | `feedback_latency_seconds`; `feedback_slo_population_total`, `feedback_slo_breach_total`, `feedback_slo_population_excluded_total`; `grading_first_attempt_success_ratio`, `grading_retry_count` | Capability: `FEAT-GRD-003` owns the population/exclusion and queue design; grading features own recording. `FEAT-OPS-005` consumes the measures. |
| Grading | `grading_status_count` by status, `grading_manual_review_total`, `grading_stale_claims_reclaimed_total`, `grading_failure_total` by category | Capability: grading owners. |
| PIN and access | `pin_validation_total` by outcome, `pin_lockout_active`, `pin_retrievable_read_total` by outcome, `otp_issue_total`, `otp_verify_total` by outcome, `otp_lockout_active` | Capability, except the closed `pin_validation_total` business-event contract is also asserted here: exam-access and result-access owners emit it. |
| Authorization | `authz_decision_total` by outcome, `authz_cache_invalidation_failure_total`, `authz_authoritative_read_duration` | Capability: IAM authorization owners. |
| Identity | `idp_reconciliation_unresolved_count` by mismatch type, `idp_provisioning_failure_total`, `workforce_auth_availability` | Capability: identity lifecycle owners. |
| Notification | `notification_dispatch_total` by channel/stream/state, `notification_dlq_depth` by queue, `notification_unreconciled_age` by stream, `notification_hard_bounce_total`, `notification_uncertain_outcome_total` by stream, `notification_provider_lookup_total` by result, `notification_unresolved_submission_count`, `notification_duplicate_message_total` | Capability: notification owners. |
| Feedback delivery | `sse_active_streams` by replica, `sse_fanout_publish_total`, `sse_fanout_miss_total`, `sse_fallback_poll_total`, `sse_stream_evicted_total` | Capability: result-delivery owner. |
| Database security context | `db_context_install_failure_total` by role, `db_context_missing_total`, `db_role_assumption_total` by role, `db_connection_reset_failure_total` | Capability emission: `FEAT-PLAT-002`; catalogue registration here under inbound record `TASK-PLAT2-OBS-001`. |
| Compliance controls | `compliance_approval_recorded_total` by subject, `compliance_control_change_total` by control, `retention_policy_version_active` by `policy_key`, `retention_disposition_policy_version` on every disposition | Capability: compliance and retention owners. |
| Business (`NFR-OBS-001`) | `exam_started_total`, `exam_finished_total`, `pin_validation_total`, `result_published_total`, `correction_applied_total`, `provisional_feedback_released_total` | Here: the six-name MVP enumeration and registration are closed and build-asserted; capability features emit through `BusinessEventRecorder`. |
| Business, Post-MVP | Sync outcomes, payment outcomes | Post-MVP: deliberately absent from the runtime enumeration and registry, recorded as declared-absent rather than missing (`TASK-OBS1-DEFECT-005`). |
| Data protection | `retention_disposition_total` by category/outcome, `retention_suppressed_total` by reason, `legal_hold_active_count`, `dsr_open_count` | Capability: privacy, retention, and legal-hold owners. |
| Integrity | `audit_chain_verification_result`, `audit_emit_failure_total`, `audit_chain_head_lock_wait_seconds` by shard, `audit_chain_shard_skew`, `audit_checkpoint_overdue_count`, `audit_epoch_seal_total` by result, `audit_epoch_disposition_total` by outcome, `audit_epoch_seal_cas_retry_total`, `audit_root_chain_fork_detected_total`, `audit_root_chain_seq` | Capability: `FEAT-AUD-001`. |
| Durability | `postgres_backend_utilisation`, `db_pool_max_size` by workload, `db_connections_allocated`, `db_connections_committed`, `db_headroom_remaining`, `sync_standby_quorum_state`, `sync_standby_streaming_count`, `sync_quorum_unavailable_seconds`, `sync_standby_promotion_seconds` | Capability: persistence and Platform Ops owners; `FEAT-OPS-004`/`FEAT-OPS-005` consume the four connection-envelope figures. |

The plan card's shorter four-event wording does not narrow section 16.2. The
six MVP names above are authoritative for this feature, while sync and payment
outcomes remain explicitly absent until their Post-MVP capabilities exist.

## P1.3 Span Inventory and Sampling Card

W3C trace context connects submission, outbox, relay, broker, grading,
result, and notification. Every span carries the safe correlation join and
uses the registered `<module>.<verbNoun>` slice naming contract where a slice
is the boundary.

| Span class | Safe span content | Sampling rule |
|---|---|---|
| HTTP server | Route template/class and HTTP semantic attributes; never raw URLs containing identifiers | Head-sample 100% for exam-entry and grading routes; other routes enter the 10% collector tail policy; every error-marked trace is retained by the collector. |
| Slice handler | Registered `<module>.<verbNoun>` name and closed contract attributes | Same route-class decision as its parent; creation and context propagation occur even when export is not selected. |
| Database statement | Statement operation/name only | Inherits the trace decision; SQL parameter values are prohibited. |
| Redis command | Command name and safe server semantic attributes | Inherits the trace decision; keys and values are prohibited. |
| Broker publish | Destination class and safe messaging semantic attributes | Inherits the producer trace and writes W3C context to the message carrier. |
| Broker consume | Destination class and safe messaging semantic attributes | Continues valid W3C context or links to the producer context according to the later `P2.10` design. |
| Outbox relay batch | Batch operation and bounded outcome metadata | Inherits or links through the outbox row; row payload is prohibited. |
| External provider call | Provider operation class and safe outcome | Inherits the trace decision; request/response payloads and credentials are prohibited. |

The approved `P0.8` trace contract lists five prohibited attribute entries,
which are retained here even though the task text calls them four categories:

1. PIN.
2. OTP.
3. Token or credential.
4. Answer content.
5. Personal data, including email and name.

The task wording does not authorize dropping a signed prohibition. Error
retention is a collector tail-sampling obligation: an error cannot reliably
be known when a head decision is made. Without that collector behavior, the
architecture's 100% error-retention rule is unmet.

## P1.4 Per-Request Query-Budget Card

| Route class | Query budget | Architecture rationale |
|---|---:|---|
| Timer/state refresh | 1 | One indexed `attempt` read. |
| Question navigation | 1-2 | Presentation plus question text from `attempt_*` tables. |
| Answer save | 6 | Lifecycle check, locked attempt, operation insert, answer upsert, audit insert, and audit chain-head upsert in one transaction. |
| Authorized workforce request | 2 + slice | PostgreSQL user lifecycle and membership reads, plus the owning slice's declared budget; effective permissions come from Redis. |
| Exam entry | Approximately 10 | The heaviest request path, still within one transaction. |

These limits are measured on every request by the Reactor-context query
counter, not inferred from sampled spans. They are the exact acceptance
baseline for `P7.12`; a route-specific assertion must distinguish the fixed
workforce overhead from the owning slice budget.

## P1.5 Telemetry-Hygiene Card

| Source | Obligation applied to telemetry | Phase 6 acceptance implication |
|---|---|---|
| `REQ-SEC-004` | An exam-access PIN is never logged in plaintext; plaintext PINs are also prohibited from stores, audit payloads, and error responses. | Adversarial PIN values must be absent from structured logs, span data, labels, and exported payloads. |
| `REQ-RSLT-021` | OTP values and other authentication secrets are prohibited from audit records; section 16.1 and the CI stage 10 leak-scan contract extend the same hygiene boundary to operational telemetry. | OTPs, tokens, authorization values, passwords, keys, and equivalent credentials must be rejected or redacted at every telemetry surface. |
| `REQ-PRIV-004` | Personal-data access is role-restricted and audited; operational telemetry is not an alternate identity store. | Candidate identity is omitted unless an approved diagnostic need exists; email and name are never emitted directly. A candidate reference, if required, is a keyed hash using a per-environment salt. |
| `REQ-AUD-002` | Audit data is tenant-scoped, queryable for compliance, and stored separately from operational logs. | Logs and audit use distinct sinks, retention, and guarantees. Logs may be sampled or dropped; audit records may not. |
| Architecture section 16.1 | Candidate email and name are hashed or omitted, secret-named fields are redacted, and a domain type exposing such a field to logging fails the build. | The same centrally owned `SecretFieldPattern` and reviewed allowlist govern logs, span attributes, and metric labels; no local copy or exception is permitted. |

The least-data rule wins where sources differ in scope: operational logs omit
email and name by default, and a keyed reference is allowed only for an
approved diagnostic use case. This card is the security-review baseline for
Phase 6 and the negative-test matrix in Phase 7.

## P1.6 Inbound Observability-Gap Register

| Gap record and raiser | Metric/catalogue limb | Metric receiver | Panel/alert limb | Panel/alert receiver |
|---|---|---|---|---|
| `TASK-PLAT2-OBS-001`, `FEAT-PLAT-002` | Register `db_context_missing_total`, `db_context_install_failure_total` by role, `db_role_assumption_total` by role, and `db_connection_reset_failure_total`; retain the emitter's closed role labels. | `FEAT-OBS-001` `P4.14` and cardinality guard `P4.16` | Add the database security-context group to platform-health dashboard 6; preserve the existing P1 missing-context and P2 install/reset alert routing and exercises. | `FEAT-OPS-004`, handed over by `FEAT-OBS-001` `P8.5`/`P9.5` |
| `TASK-PLAT3-OBS-001`, `FEAT-PLAT-003` | Register `problem_detail_emitted_total` by code, `problem_detail_unmapped_total`, `idempotency_replay_total` by outcome, and `idempotency_store_unavailable_total`; retain exemplars without correlation labels. | `FEAT-OBS-001` `P4.13` and cardinality guard `P4.16` | Add a P2 sustained-unmapped-rate alert with the recorded first action, plus unmapped-rate and top-error-code panels. | `FEAT-OPS-004`, handed over by `FEAT-OBS-001` `P8.5`/`P9.5` |
| `TASK-PLAT5-OBS-001`, `FEAT-PLAT-005` | Register `migration_lock_held_seconds` and `migration_outcome_total` by bounded outcome. The emitting feature also supplies duration, forbidden-operation, and freeze-refusal signals. | `FEAT-OBS-001` `P4.15` and cardinality guard `P4.16` | Add the P2 lock-threshold and P1 production-failure alerts with their recorded first actions, plus the migration panel group. | `FEAT-OPS-004`, handed over by `FEAT-OBS-001` `P8.5`/`P9.5` |
| `TASK-PLAT4-OBS-001`, `FEAT-PLAT-004` | Preserve the seven-metric outbox set from its `P9.1`: backlog by state, oldest pending age, relay published/failure, relay tick count/duration, stale claims reclaimed, and failed events. `FEAT-PLAT-004` emits/registers it; this feature applies naming and cardinality conformance, with no `tenant_id`. | `FEAT-PLAT-004` `P9.1`, governed by `FEAT-OBS-001` `P4.16` | Preserve the architecture backlog P2; add proposals for failed rows, relay-liveness gaps, DLQ depth, and unhandled versions, plus backlog/age/throughput/DLQ panels on dashboard 6. | `FEAT-OPS-004`, handed over by `FEAT-OBS-001` `P8.5`/`P9.5` |

Every inbound record now has two receivers. Metric registration or conformance
is not evidence that a panel, alert route, or exercise exists; those launch
artifacts remain `FEAT-OPS-004` obligations under `L5`.

## P1.7 Verification Ownership

Architecture section 19.8 is authoritative for scenario meaning. This
feature contributes only the limbs below and creates no replacement
`ARC-VERIFY` identifier.

| Verification | Real section 19.8 meaning and gate | Ownership boundary for this feature |
|---|---|---|
| CI stage 10 secret-leak scan, log limb | Cross-surface scan for PIN, OTP, token, and answer-key leakage; blocking CI stage 10. It is a named pipeline assertion rather than a dedicated register identifier. | `FEAT-OBS-001` owns the log/export limb and its redactor. `FEAT-AUD-001` owns audit payloads, `FEAT-PLAT-003` owns error responses, and `FEAT-SEC-001` consumes the combined release evidence. |
| `ARC-VERIFY-009` | Static PIN protection and profile-isolation checks; CI stage 10 plus integration. | `FEAT-EXAM-002` owns the PIN path. `FEAT-OBS-001` contributes the log redactor and proves that attempted PIN emission does not cross the telemetry boundary; it makes no runtime-key claim. |
| `ARC-VERIFY-013` | Fault injection yields only allowlisted `ProblemDetail` bodies carrying a correlation identifier; CI stage 10. | `FEAT-PLAT-003` owns the error contract. `FEAT-OBS-001` consumes the identifier and proves the same value joins problem, log, span, and exemplar. |
| `ARC-VERIFY-017` | `feedback_slo_population_excluded_total` is emitted and remains zero throughout CI stage 17. | `FEAT-GRD-003` owns emission and the zero-exclusion rule; `FEAT-OPS-005` owns the performance run. `FEAT-OBS-001` supplies catalogue/cardinality conformance only. |
| `ARC-VERIFY-018` | The real register entry is startup rejection of out-of-range configuration; startup plus CI stage 8. | `FEAT-OBS-001` owns the `ARC-OBS-004` observability-configuration limb: absent endpoint, out-of-range sampling, absent redaction allowlist, and absent hash-salt reference fail boot. Other feature settings retain their owners. |
| Feature-card propagation expectation | HTTP to transaction to outbox to broker to consumer; CI stage 8. No dedicated register identifier exists. | `FEAT-OBS-001` owns the end-to-end assertion while consuming `FEAT-PLAT-003` and `FEAT-PLAT-004` carriers. |
| Feature-card business-event completeness expectation | Six-event closed enumeration; CI stages 4 and 5. No dedicated register identifier exists. | `FEAT-OBS-001` owns the positive and negative completeness assertions. |

`TASK-OBS1-DEFECT-002` records that plan section 14.4 omitted all three
feature-card expectations; they remain named CI assertions with retained
evidence instead of receiving invented identifiers.

`TASK-OBS1-DEFECT-006` records the two plan section 14.4 errors around
`ARC-VERIFY-018`: the plan assigns it to `FEAT-PLAT-002` and describes it as
RLS returning zero rows without a tenant predicate. That description is
actually `ARC-VERIFY-005`; `ARC-VERIFY-018` is configuration startup
validation. This discovery record follows the real register meaning.

## P1.8 Metric-Label Cardinality Risk Register

Every registered metric must declare its permitted label keys and a maximum
series budget. Labels not listed in that declaration are rejected at
registration; values outside a closed set or bounded catalogue are rejected
at recording time.

| Candidate label or affected catalogue metrics | Cardinality risk | Bounded disposition for `P2.7` |
|---|---|---|
| `tenantId` on any section 16.2 metric | Tenant count is unbounded and would multiply every other dimension. | Forbidden globally as a metric label. Keep it in protected logs and span attributes. Use a trace exemplar to move from an aggregate time series to the correlated trace and log. |
| `correlationId` on any request, error, or business-event metric | Effectively unique per request. | Forbidden globally as a metric label. Preserve it in logs, spans, response/problem fields, and metric exemplars. |
| `audit_root_chain_seq` described as current sequence "by tenant" | The approved wording explicitly creates the forbidden tenant dimension. | Publish aggregate integrity state/counts without a tenant label; emit the affected tenant in a protected log/span and expose per-tenant sequence only through an authorized diagnostic surface. This is a catalogue correction, not an exception. |
| `http_server_requests` raw URI, route instance, method argument, or status text | User/resource identifiers and arbitrary paths make the series unbounded. | Only registered route class, closed audience, HTTP method, normalized status class, and bounded outcome. |
| `db_query_duration` SQL text, parameter, relation discovered at runtime, or connection id | Queries, identifiers, and parameters are unbounded and may contain secrets. | Label by registered slice and closed operation only; statement names remain in spans, parameter values nowhere. |
| `redis_command_duration` / `redis_errors_total` cache key, exception, or server message | Keys and exception text are unbounded and may identify a tenant or secret. | Closed command and outcome/reason enumerations only. |
| `rabbitmq_queue_depth`, `queue_consumer_utilisation`, and notification queue metrics | Arbitrary destination names can grow without bound. | Queue must come from the deployed, reviewed queue catalogue; no message id, routing key instance, tenant, or correlation label. |
| `concurrent_active_attempts` by session | A session identifier changes over time, but the simultaneous set is operationally bounded by the active-session domain and the metric is required for session headroom. | Permitted with an explicit maximum equal to the approved concurrent active-session ceiling; remove the series when the session closes. Platform-wide aggregation remains available. |
| `sse_active_streams` by replica | Replica identities churn, but the simultaneous set is bounded by the deployment replica ceiling. | Permitted with a maximum equal to the runtime-role scaling ceiling; stale replica series expire on shutdown/scrape staleness. |
| `retention_policy_version_active` by `policy_key` and disposition policy-version evidence | Free-form policy keys or versions would grow with every edit. | `policy_key` is a reviewed catalogue value and permitted by the secret-name allowlist; expose the active version as a value/gauge state, not as an ever-growing version label. |
| Audit lock/skew metrics by shard | A free-form shard id could grow with tenants or migrations. | Shard is a fixed integer in `[0,N)` from the approved audit configuration; changing `N` requires a catalogue-budget review. |
| Metrics labelled by code, route class, audience, slice, queue, workload, role, status, category, outcome, reason, channel, stream, result, mismatch type, subject, control, policy key, or shard | These are safe only while tied to a closed enum, registered route/slice, deployment catalogue, or other finite configuration. | Permit only declared values and assign the Cartesian-product maximum in the metric catalogue; unknown values fail registration/recording rather than becoming a new series. |
| Exception class/message, actor id, candidate id, attempt id, result id, release id, incident id, provider message id, request/answer content | Unbounded, identifying, secret-bearing, or all three. | Never metric labels. Use a stable bounded reason/category, then diagnose through an exemplar, trace, and protected structured log. |

Session and replica labels are therefore retained under explicit concurrent
bounds. Tenant and correlation labels are not retained under any metric,
including metrics where the approved prose currently suggests a tenant
dimension.

## P1.9 Telemetry-Degradation Card

The invariant is stronger than "best effort": telemetry failure may reduce
diagnostic coverage, but it must never fail, delay indefinitely, cancel, or
change the result of a request or background business operation.

| Failure mode | Required degradation behavior | Observable proof for `P7.16` |
|---|---|---|
| Collector unreachable | Export occurs off the request path through a bounded queue. Connection failure is bounded by the hard export timeout; retries/backoff remain exporter work. When capacity is exhausted, the oldest telemetry item is dropped. | The business request succeeds within its normal bound; export-failure and dropped-item counters rise and queue depth remains within its configured maximum. |
| Export queue full | The producer never waits for queue capacity and never performs synchronous export. Drop the oldest queued item, admit the newest item, and increment the signal-specific drop counter. | Under forced saturation, request latency/response semantics remain unchanged, queue depth never exceeds its bound, and dropped span/log counters equal observed loss. |
| Sink slow or hung | The exporter cancels each attempt at the hard timeout and releases exporter resources; no request thread or Reactor scheduler waits on the sink. Repeated slowness cannot create an unbounded retry or task backlog. | A deliberately stalled sink produces timeouts/self-metrics while application traffic completes and exporter worker/queue counts stay bounded. |
| Telemetry serialization failure | Reject and drop only the malformed telemetry item, increment the serialization/redaction rejection signal, and avoid recursively logging the same failed payload. Never surface the encoder exception into business control flow. | An adversarial event fails export, contains no leaked raw payload in fallback output, increments the rejection/drop signal, and its originating request still succeeds or fails solely for its business reason. |

The self-observability path is also bounded and must not recursively enqueue a
failure about its own failed export. Operators may lose individual telemetry
items during the incident; they must not lose platform availability because
of the telemetry system.

## P1.10 Consumer-Contract Table

Only foundation task lists exist in this repository today. For downstream
features, the "consumer task" column therefore names the approved plan-card
work item or launch condition rather than fabricating a task identifier. The
final column names the concrete `FEAT-OBS-001` task that must hand it over.

| Dependant | Consumer task that cannot start or close without this contract | Required `FEAT-OBS-001` deliverable | Producing / handoff task |
|---|---|---|---|
| `FEAT-OPS-004` | Build all seven section 16.5 dashboards, install/route the section 16.4 alert set, exercise every alert, and discharge launch condition `L5`. | Stable metric catalogue and names, structured-log and span contracts, self-health signals, and the panel/alert limbs of all four inbound gaps. Removal or rename of a published metric is a breaking change. | Registration `P4.11`-`P4.16`, self-signals `P4.25`, handoff `P8.5`, inbound discharge `P9.5`, and self-alert proposals `P9.6` |
| `FEAT-OPS-005` | Run CI stage 17 performance/capacity verification and discharge `L8`, including response/error SLIs, query-budget regressions, `ARC-VERIFY-017`, and the retained capacity record. | `dbQueryCount` on every request, `db_query_duration` by slice, the section 15.2 per-route assertion, metric exemplars, and stable golden-signal definitions. | Implementation `P4.12`/`P4.22`, budget test `P7.12`, and handoff `P8.6` |
| `FEAT-GRD-003` | Design and emit the feedback SLO population, breach, retry/success, latency, queue-wait, and `feedback_slo_population_excluded_total` signals; prove the excluded counter is zero under `ARC-VERIFY-017`. | Registered naming/cardinality rules, OTLP metric export, exemplar correlation, and the explicit rule that the excluded-population metric is capability-owned rather than silently supplied here. | Convention `P2.7`, registry/export infrastructure `P4.16`/`P4.24`, verification ownership `P7.3`/`P7.11`; consumed by the `P8.6` performance handoff |
| `FEAT-SEC-001` | Run the full cross-surface leak scan and use its log limb as blocking release evidence. | Redacting serializer, domain/span/label checks, capturing/export test substrate, and the blocking CI stage 10 log-leak result. | `P4.5`-`P4.7`, `P3.6`, `P3.8`, and `P7.4`-`P7.8` |
| Every later feature that adds a slice or background handler | Satisfy the universal Definition of Done clause "required telemetry exists" by declaring business events and bounded metrics, using the fixed log fields, creating the registered slice span, and adding query-budget and hygiene tests. | Vendor-neutral `BusinessEventRecorder` and `RequestTelemetry` ports, reference-slice instrumentation, no-vendor-import/cardinality/completeness gates, and the authoring guide. | Design `P2.1`/`P2.17`; implementation `P4.1`-`P4.3`, `P4.20`, `P4.27`, `P4.28`; guide `P10.4` |

These obligations are prerequisites, not transfers of ownership. Capability
features remain responsible for emitting their own domain telemetry;
`FEAT-OBS-001` supplies and enforces the platform mechanism, while
`FEAT-OPS-004` owns presentation and alert operations.
