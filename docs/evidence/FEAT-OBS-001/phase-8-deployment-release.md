# FEAT-OBS-001 Phase 8 Deployment and Release Evidence

## P8.1 - Environment Configuration Surface

Verdict: **PUBLISHED**

Every environment supplies the same `cbt.observability` property tree through
the environment-variable mappings in `application.yaml`. There is no
environment-specific Java branch. `ObservabilityConfigurationValidator`
validates the resolved values before telemetry adapters start.

| Environment | Collector endpoint | Sampling ratios | Permitted key fields | Candidate-hash secret reference |
|---|---|---|---|---|
| Local | Explicit developer OTLP/gRPC endpoint; TLS may be disabled only under the `local` profile | Exam entry `1.0`; grading `1.0`; standard export `1.0`; standard tail `0.10` | Exactly `policy_key` | Explicit local-only file reference; never an inline secret |
| Test | Explicit loopback or in-process OTLP/gRPC sink selected by the test | Same four production ratios | Exactly `policy_key` | Isolated test fixture reference containing no production material |
| Staging | `https://otel-collector-gateway.observability-staging.svc.cluster.local:4317` | Same four production ratios | Exactly `policy_key` | Environment-specific secret mounted below `/run/secrets`; the value is not an application property |
| Production | `https://otel-collector-gateway.observability-production.svc.cluster.local:4317` | Same four production ratios | Exactly `policy_key` | Production-only secret mounted below `/run/secrets`; the value is not an application property |

The shared environment-variable interface is:

- `CBT_OBSERVABILITY_COLLECTOR_ENDPOINT` and the optional per-signal trace,
  metric, and log endpoint variables;
- `CBT_OBSERVABILITY_EXAM_ENTRY_HEAD_RATIO`,
  `CBT_OBSERVABILITY_GRADING_HEAD_RATIO`,
  `CBT_OBSERVABILITY_STANDARD_EXPORT_RATIO`, and
  `CBT_OBSERVABILITY_STANDARD_TAIL_RATIO`;
- `CBT_OBSERVABILITY_REDACTION_PERMITTED_KEY_FIELDS`;
- `CBT_OBSERVABILITY_CANDIDATE_HASH_SECRET_REFERENCE`.

Outside local development, the validator also requires HTTPS, mutual TLS,
trust and client-identity file references, and an absolute candidate-hash
secret path below `/run/secrets`. The signed collector contract fixes the
staging and production endpoints, per-environment workload identities, and
the requirement that candidate-hash secrets differ by environment. It records
references and ownership only; no secret value is committed.

Evidence:

- `src/main/resources/application.yaml`
- `ObservabilityProperties`
- `ObservabilityConfigurationValidator`
- `ci/dor/FEAT-OBS-001/P3.5-collector-contract.json`
- `ObservabilityConfigurationValidatorTest`

## P8.2 - Runtime-Role Parity Review

Verdict: **PASS - ONE TELEMETRY CONFIGURATION**

The `api`, `worker`, and `pindist` roles consume one `cbt.observability`
configuration declared before the profile-specific YAML documents. None of
the three profile documents overrides collector endpoints, protocol, TLS,
sampling, redaction, candidate hashing, export queues, trace continuation, or
metric-catalogue ceilings.

The deployment supplies `CBT_OBSERVABILITY_ROLE` as `api`, `worker`, or
`pindist`. That value becomes the OpenTelemetry resource role and the
structured-log `role`, making the workloads distinguishable without separate
configuration trees. Each service account receives its own SPIFFE identity,
but the projected trust, certificate, and private-key files use the same
property names and mount-path contract. Identity material changes; application
telemetry policy does not.

The role-specific YAML documents configure workload infrastructure only:
database pools for all roles, and RabbitMQ for `worker`. They do not contain a
second observability block. A future role-specific telemetry override would
invalidate this review and must be treated as a reviewed configuration-contract
change.

Evidence:

- the single top-level `cbt.observability` block in `application.yaml`;
- the `api`, `worker`, and `pindist` profile documents in the same file;
- the closed role validation in `ObservabilityConfigurationValidator`;
- the three-role identity matrices in the signed collector contract.

## P8.3 - Rollback Statement

Verdict: **CODE-AND-CONFIGURATION ROLLBACK**

`FEAT-OBS-001` adds no database schema or platform-owned durable data. Its
rollback is an application image/source revert together with the matching
externally supplied telemetry configuration. It needs no reverse migration
and must not modify audit data or another feature's store.

Published telemetry contracts are additive compatibility boundaries. A
rollback image must continue to emit every metric name and structured-log
field already consumed by `FEAT-OPS-004`, or the dashboard and alert owner
must approve and deploy the corresponding contract change first. Removing or
repurposing a published metric, label, log field, span name, or attribute is a
breaking operational-interface change; a source revert does not waive that
rule.

The collector may be unreachable during rollback without blocking requests.
Bounded queues, hard export deadlines, drop-oldest behavior, and local
self-observability remain required in both the outgoing and rollback images.
Rollback therefore restores application code and configuration, not an older
unbounded or synchronous export path.

Evidence:

- the plan feature card records no persistent platform data impact;
- no `FEAT-OBS-001` Flyway migration exists;
- `config/observability/metric-cardinality.json` is the published metric-name
  and label inventory;
- `P7.16` and `P7.17` prove collector failure does not affect availability.

## P8.4 - FEAT-PLAT-004 Reciprocal Closure

Verdict: **CLOSED FOR FEAT-OBS-001**

`FEAT-PLAT-004` supplied the required carrier: `correlation_id`, `traceparent`,
and `tracestate` on the outbox row and the corresponding broker headers. The
observability adapter consumes those values; it does not create a parallel
carrier or reinterpret an unvalidated header.

The retained `P7.1`/`P7.2` integration result exercises HTTP, a Reactor
scheduler hop, a PostgreSQL transaction, the outbox row, relay batch,
RabbitMQ message, and consumer span against real PostgreSQL and RabbitMQ
Testcontainers. Its three cases pass, including the relay-batch and scheduler
variants. This discharges the end-to-end join that `FEAT-PLAT-004` deliberately
deferred to `FEAT-OBS-001`; the outbox feature remains owner of its carrier
verification task and this record does not change that sibling task list.

Evidence:

- `docs/evidence/FEAT-OBS-001/P7.1-propagation-test-result.json`
- `ObservabilityPropagationIntegrationTest`
- `TraceContextContinuationTest`
- `src/main/resources/db/migration/outbox/V3__create_outbox_event.sql`
- `FEAT-PLAT-004` task `P9.5` carrier/end-to-end ownership statement

## P8.5 - FEAT-OPS-004 Handover

Verdict: **PUBLISHED FOR INTAKE**

`docs/evidence/FEAT-OBS-001/P8.5-feat-ops-004-handover.md` publishes four
separate records for the registered metric inventory, structured-log field
contract, span contract, and the panel/alert limbs of all four inbound gaps.
It names `FEAT-OPS-004` as the dashboard, alert, routing, and exercise owner
for launch condition `L5` and explicitly preserves each downstream closure
criterion.

The handover does not represent proposed dashboards or alerts as live. The
outbox metric limb is also recorded as pending its owning sibling task rather
than being invented by this feature.

## P8.6 - FEAT-OPS-005 Query-Telemetry Handover

Verdict: **PUBLISHED FOR INTAKE**

`docs/evidence/FEAT-OBS-001/P8.6-feat-ops-005-handover.md` publishes the
sampling-independent `dbQueryCount`, bounded `db_query_duration` timer, and
blocking per-route query-budget gate as the performance feature's
`ARC-PERF-004` and section 15.2 inputs. It distinguishes the telemetry input
from the downstream stage 17 measurement and capacity evidence owned by
`FEAT-OPS-005`.

## P8.7 - Phase 0 Correlation Exit Criterion

Verdict: **MET FOR FEAT-OBS-001**

`docs/evidence/FEAT-OBS-001/P8.7-phase-0-exit-criterion.md` records the plan
section 10 criterion against the real-infrastructure `P7.1`/`P7.2` proof and
the operator-facing `P9.3` confirmation. The record limits its verdict to this
feature's correlation contribution and does not imply that unrelated Phase 0
criteria are complete.

## P8.8 - Deferral Register

Verdict: **OWNERSHIP RECORDED**

`docs/evidence/FEAT-OBS-001/P8.8-deferral-register.md` assigns the seven
dashboards, alert set and thresholds to `FEAT-OPS-004`; performance runs to
`FEAT-OPS-005`; capability metrics to their feature owners; and frontend
telemetry outside programme scope. It keeps the approved plan's missing
observability-backend feature ownership explicit while distinguishing that
gap from the signed Platform Ops application-integration contract.
