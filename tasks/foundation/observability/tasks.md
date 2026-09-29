# Task List — `FEAT-OBS-001` Observability Baseline: Logs, Metrics, Tracing and Correlation

## Overview

|                       |                                                                                                                                                                                                                                          |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-OBS-001`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 track (b), §14.4                                                                                                                             |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §15.2 (per-request query budget), §16.1 (`ARC-OBS-001`, `ARC-OBS-002`), §16.2, §16.3 (`ARC-OBS-003`), §17.5 (`ARC-OBS-004` limb), §18.1 stages 4/5/8/10/17, §19.8, §19.9              |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                                          |
| Dependencies          | `FEAT-PLAT-001` (`WebFilter`, `ContextSnapshot` logging bridge, scheduler-hop propagation, span-naming contract, log-field contract), `FEAT-PLAT-003` (ULID correlation identifier, `ActorContext`, `SecretFieldPattern`), `FEAT-PLAT-004` (trace context across the outbox and broker) |
| Consumed by           | Every feature in the programme. Directly named: `FEAT-OPS-004` (dashboards and the alert set, launch condition `L5`), `FEAT-OPS-005` (performance evidence), `FEAT-GRD-003` (`ARC-VERIFY-017`), `FEAT-SEC-001` (the log limb of the leak scan) |
| Generated on          | 2026-09-03                                                                                                                                                                                                                               |
| Methodology           | Clean architecture. Telemetry is an **outward** concern: `shared.kernel` defines the ports (`BusinessEventRecorder`, `RequestTelemetry`), `platform.infra` holds every OpenTelemetry, Micrometer and log-encoder type, and no `domain` or `slice` class imports a vendor telemetry API. Enforced by this feature's own conformance rule |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                   |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                                     |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                |

**Objective.** Make every request, background task and error traceable end to end by a correlation
identifier, with structured logs, metrics and distributed traces emitted from the first feature onwards —
and with no PIN, OTP, credential or unnecessary personal data anywhere in the telemetry.

**Why this feature is Phase 0 and not Phase 6.** `FEAT-OPS-004` builds seven dashboards and the alert set as
launch condition `L5`, but a dashboard can only draw what was instrumented. Retrofitting instrumentation
means discovering the gaps during an incident, which is the one moment the gap cannot be closed. The plan
therefore places the *mechanism* here and the *presentation* in Phase 6. This task list ships the mechanism,
the conformance rule that keeps later slices instrumented by construction, and the four observability gap
records the sibling Phase 0 task lists have already raised against this feature.

### Confirmed implementation decisions

| Decision                      | Choice                                                                                                                                                                                                                       | Consequence                                                                                                                                                                              |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Layering                      | `shared.kernel` defines `BusinessEvent`, `BusinessEventRecorder` and `RequestTelemetry`; every OTel, Micrometer and encoder type lives in `platform.infra`. A `slice` may use the SLF4J façade and the ports, nothing else     | A business event is a **domain fact**, not an HTTP side effect. Recording it through a port keeps `CONSTRAINT-PLAT-006`'s vendor choice outside the business code and keeps `domain` pure  |
| Export contract               | **OTLP over gRPC to a collector endpoint from configuration.** No vendor SDK, no in-process scrape assumption, no product name in the application                                                                              | Neither document names a collector, metric store or log store (`TASK-OBS1-DEFECT-001`). Binding to OTLP is the only choice that does not silently pick an unowned product                 |
| Sampling                      | **Hybrid.** Head-based 100% on the exam-entry and grading route classes; everything else marked and decided **tail-based in the collector**, since "100% of errors" is undecidable at head                                     | §16.3's error rule cannot be honoured in-process, so the collector is load-bearing rather than optional — which is why `TASK-OBS1-DEFECT-001` is a blocker and not a preference            |
| `dbQueryCount`                | A **Reactor-context-scoped counter** incremented by an R2DBC statement listener. Never derived from spans                                                                                                                      | Tail sampling drops most non-exam traces, so a span-derived count would be missing exactly where a query-budget regression hides. §15.2's budget stays measurable on every request        |
| Business-event metric set     | An **asserted enumeration in code** — six MVP events per §16.2 (exam started, exam finished, PIN validation, result published, correction applied, provisional feedback released); the two Post-MVP events declared absent      | The plan's mitigation for its own risk row: "an asserted list rather than a convention". The plan card names four events, §16.2 names six — §16.2 adopted (`TASK-OBS1-DEFECT-005`)        |
| Secret-field matching         | `SecretFieldPattern` matched on **whole field names / word boundaries** with an enumerated permitted-`key` allowlist, not as a substring                                                                                       | A substring match on `key` redacts the `policy_key` label that §16.2 and `ARC-VERIFY-030`/`-032` require as disposition evidence. Raised to the pattern's owner (`TASK-OBS1-DEFECT-003`)  |
| Redaction scope               | The allowlist applies to log fields, **span attributes and metric labels** alike, and the build-failing check covers all three                                                                                                 | `ARC-OBS-002` specifies the check for logging only, while §16.3 forbids the same values in span attributes with nothing checking it (`TASK-OBS1-DEFECT-004`)                              |
| Candidate identity in logs    | Email and name **never**; a candidate reference appears only as a **keyed hash** with a per-environment salt drawn from the secret manager                                                                                     | §16.1 and `REQ-PRIV-004`. A per-environment salt also makes a cross-environment join on the hash impossible, so a staging log leak does not identify a production candidate               |
| Metric label cardinality      | `tenantId` and `correlationId` are **never** metric labels; each metric declares a maximum label cardinality, asserted in CI                                                                                                    | The correlation identifier is unique per request and the tenant count is unbounded. Both belong in logs and span attributes, where the join is cheap, not in a time series                |
| Export-failure behaviour      | Bounded queue, hard export timeout, drop-oldest, self-metrics on the drop. **No blocking exporter on any request path**                                                                                                        | The plan's reliability expectation, stated as a mechanism: an unreachable collector degrades observability and never availability                                                          |
| Logs are not the audit trail  | Separate sink, separate retention; sampling permitted on logs, prohibited on audit                                                                                                                                             | `REQ-AUD-002` and §16.1. Recorded explicitly because the two stores carry overlapping fields and are easy to conflate in configuration                                                    |
| `ARC-OBS-004` scope           | Only the **observability-configuration** limb is taken here — collector endpoint, sampling ratios, redaction allowlist and the business-event enumeration fail the boot when absent or out of range                             | §17.5's security and policy limbs belong to their own features, and `ARC-VERIFY-018` is mis-described in plan §14.4 (`TASK-OBS1-DEFECT-006`)                                              |

### Assumptions

1. **The propagation mechanism already exists.** `FEAT-PLAT-001 tasks.md` `P2.7`, `P2.9`, `P4.7`–`P4.9`, `P9.1` and
   `P9.2` shipped the `WebFilter`, the `ContextSnapshot` logging bridge, scheduler-hop propagation, the
   `<module>.<verbNoun>` span-naming contract and the log-field contract. This feature supplies the field
   **values**, the encoder, the meter registry and the export path; it does not rebuild the bridge.
2. **The correlation identifier and the secret pattern are the kernel's.** `FEAT-PLAT-003 tasks.md` `P4.13` and
   `P4.14` own the ULID form, its strict validation, replace-not-echo, and the single definition of
   `SecretFieldPattern`. This feature owns the log, metric and span limbs that consume them.
3. **The trace carrier already exists.** `FEAT-PLAT-004 tasks.md` `P2.1` and `P4.3` shipped `correlation_id` and
   the W3C trace context as **columns** on `outbox.outbox_event` and **headers** on the broker message, and
   its `P9.5` explicitly leaves the end-to-end join test to this feature. This feature therefore *consumes*
   the carrier and owns the assertion that the chain holds across it; it does not build the carriage.
4. **No schema change.** The plan's `Data impact` row is "None persistent in the platform's own stores".
5. **The observability backend is provisioned in parallel by Platform Ops** per plan §10 Phase 0. This
   feature depends on an OTLP endpoint and a log sink, not on a named product.
6. **Dashboards, panels and alert rules are `FEAT-OPS-004`'s** (§16.4, §16.5, launch condition `L5`). This
   feature ships metric registration and hands over each proposed panel and alert.
7. **Slices instrument themselves.** This feature ships the mechanism, the conformance rule, the authoring
   guide and the reference instrumentation on `FEAT-PLAT-001`'s conformance reference slice. Per-capability
   metrics belong to the feature that owns the capability.

### Blockers and defects carried into this task list

| ID                     | Statement                                                                                                                                                                                                                                                                                                       | Owning task       |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|
| `PLAN-BLOCKER-001`     | `ci/architecture-ratification.json` is `status: RATIFIED`; plan §10 gates Phase 0 **implementation** on stage 4a. Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`; not restated here                                                                                                                                 | `P0.1`            |
| `TASK-OBS1-DEFECT-001` | **No telemetry export target is named anywhere.** Neither `architecture.md` nor `plan.md` names a collector, metric store or log store; plan §10 lists "the observability stack provisioned" as a parallel Phase 0 prerequisite with **no owning feature**. This feature cannot export to an unnamed endpoint      | `P0.5`, `P3.5`    |
| `TASK-OBS1-DEFECT-002` | **No `ARC-VERIFY` scenario is assigned to `FEAT-OBS-001`** in plan §14.4, yet the feature card's testing expectations name three assertions: correlation propagation across the broker, business-metric completeness, and the log limb of the leak scan. All three ship as named CI assertions; no new register identifier is invented | `P0.6`, `P1.7`    |
| `TASK-OBS1-DEFECT-003` | `SecretFieldPattern` includes the token `key`. Matched as a substring it redacts `policy_key` — the label §16.2 and `ARC-VERIFY-030`/`-032` require on every disposition as evidence. Word-boundary matching with an enumerated allowlist adopted and raised to the pattern's single definition site                | `P0.7`, `P4.5`    |
| `TASK-OBS1-DEFECT-004` | `ARC-OBS-002`'s build-failing redactor is specified for **logging** only. §16.3 forbids PIN, OTP, token, answer content and personal data in span attributes, and §16.2 labels carry the same field names, with nothing checking either                                                                            | `P4.7`, `P7.6`    |
| `TASK-OBS1-DEFECT-005` | The feature card names four business events; §16.2 names six for MVP plus two Post-MVP. A completeness assertion built from the narrower list would assert the wrong set. §16.2 adopted as normative, with `sync outcomes` and `payment outcomes` declared explicitly absent                                        | `P1.2`, `P4.28`   |
| `TASK-OBS1-DEFECT-006` | `ARC-OBS-004` sits in this feature's architecture references but §17.5 is *configuration* validation, and plan §14.4 both assigns its scenario `ARC-VERIFY-018` to `FEAT-PLAT-002` **and** describes it as "row-level security zero rows without predicate" — which is `ARC-VERIFY-005`. Two mis-citations in one row | `P1.7`, `P10.9`   |
| `TASK-OBS1-DEFECT-007` | §16.3 requires 100% sampling of errors, which no head-based sampler can decide, and the architecture does not say where tail sampling runs. Hybrid head-plus-collector-tail adopted, which makes `TASK-OBS1-DEFECT-001`'s collector mandatory rather than a deployment convenience                                  | `P2.9`, `P3.5`    |
| `TASK-OBS1-OBS-001`    | **No CI stage asserts the §15.2 per-request query budget**, although §16.1 mandates `dbQueryCount` and §15.2 makes the budget the reason the latency targets close. A per-route budget assertion is proposed for CI 8 and a regression check for `FEAT-OPS-005`                                                     | `P3.9`, `P7.12`   |

### Inbound gap records from the sibling Phase 0 task lists

Each was raised **to** this feature. Each is split into a metric limb discharged here and a panel or alert
limb handed to `FEAT-OPS-004`, so neither half is lost at the boundary.

| Record                 | Raised by         | Metric limb (here)                                                                                                                | Panel / alert limb (`FEAT-OPS-004`)                                        |
|------------------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `TASK-PLAT2-OBS-001`   | `FEAT-PLAT-002 tasks.md`  | `db_context_missing_total`, `db_context_install_failure_total`, `db_role_assumption_total`, `db_connection_reset_failure_total`     | A P1 exists with no dashboard panel to triage it — panel 5 or 6            |
| `TASK-PLAT3-OBS-001`   | `FEAT-PLAT-003 tasks.md`  | `problem_detail_emitted_total`, `problem_detail_unmapped_total`, `idempotency_replay_total`, `idempotency_store_unavailable_total`  | P2 on a sustained unmapped rate; a top-error-codes panel                   |
| `TASK-PLAT5-OBS-001`   | `FEAT-PLAT-005 tasks.md`  | `migration_lock_held_seconds`, `migration_outcome_total`                                                                           | P2 on lock duration, P1 on `outcome="FAILED"` in production; a migration panel |
| `TASK-PLAT4-OBS-001`   | `FEAT-PLAT-004 tasks.md`  | **Already defined in `FEAT-PLAT-004 tasks.md` `P9.1`** — the seven outbox metrics §16.2 omits entirely. What is inbound here is conformance to this feature's naming convention and cardinality guard (`P4.16`), which `P9.1` already anticipates with "no metric carries `tenant_id`" | The four alerts §16.4 lacks, plus outbox panels on dashboard 6 |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7` and is **not** restated. Under a
`temporaryArchitectureGate` (`implementationAllowed: false`) only Phase 1 and Phase 2 tasks are authorised.

1. [*] Confirm which authorisation scope is in force from `FEAT-PLAT-001 tasks.md` `P0.5` or `P0.6` and record it. Deliverable: one-line phase-log entry. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-001` has delivered the `WebFilter`, the `ContextSnapshot` logging bridge, scheduler-hop propagation, the span-naming contract and the log-field contract. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-001 tasks.md` `P4.7`–`P4.9`, `P9.1`, `P9.2`.
3. [*] Confirm `FEAT-PLAT-003` has delivered the ULID correlation identifier with strict validation, `ActorContext` and the single `SecretFieldPattern`. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-003 tasks.md` `P4.13`, `P4.14`, `P8.5`.
4. [ ] Confirm `FEAT-PLAT-004` has delivered the `outbox.outbox_event` row with its `correlation_id` and W3C trace-context columns, and the broker message headers that carry them. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-004 tasks.md` `P2.1`, `P4.3`. Acceptance: the carrier exists before `P4.23` consumes it — this feature does not build it.
5. [ ] Escalate `TASK-OBS1-DEFECT-001` to the Engineering Lead and Platform Ops: the telemetry export target is unowned in both documents. Deliverable: gap record naming an owner for the collector, metric store and log store, or an explicit deferral with an interim endpoint. Acceptance: settled before `P3.5`; the application binds to OTLP either way.
6. [ ] Raise `TASK-OBS1-DEFECT-002` to the Architecture Owner: no `ARC-VERIFY` scenario is assigned to this feature despite three named testing expectations. Deliverable: gap record proposing the three assertions be recorded against existing CI stages rather than a new identifier.
7. [ ] Raise `TASK-OBS1-DEFECT-003` to the `FEAT-PLAT-003` owner as a correction at the pattern's single definition site, with the `policy_key` conflict stated concretely. Deliverable: correction record. Acceptance: settled before `P4.5`, because the redactor is built on the pattern rather than beside it.
8. [ ] Obtain the feature's additional Definition of Ready: agreement on the structured-log field set, the business-event metric names and the trace-attribute conventions, from the Solution Architect, Security and Platform Ops. Deliverable: signed DoR record. Acceptance: the six MVP business-event names are fixed here, because a later rename invalidates every dashboard and alert built on them.
9. [ ] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 1 – Discovery and Analysis

Satisfies the additional Definition of Ready: the log field set, the metric names and the trace conventions
are agreed.

1. [ ] Transcribe the §16.1 field table row by row and mark, per field, whether the value comes from the kernel (`correlationId`, `actorType`, `actorId`, `tenantId`, `errorCode`), from the OTel SDK (`traceId`, `spanId`), from `FEAT-PLAT-001` (`module`, `slice`) or from this feature (`timestamp`, `level`, `logger`, `message`, `eventCode`, `durationMs`, `dbQueryCount`, `error.stack`). Deliverable: field-provenance table. Acceptance: every "Always" field has a named producer.
2. [ ] Transcribe the §16.2 metric catalogue and partition it three ways: registered here in Phase 0, registered by the owning capability feature, and Post-MVP. Deliverable: metric-ownership table. Acceptance: the six MVP business events are enumerated and `sync outcomes` / `payment outcomes` are recorded as explicitly absent (`TASK-OBS1-DEFECT-005`).
3. [ ] Transcribe the §16.3 span inventory — HTTP server, slice handler, database statement, Redis command, broker publish/consume, outbox relay batch, external provider call — with the sampling rule per class and the four prohibited attribute categories. Deliverable: span card.
4. [ ] Transcribe the §15.2 per-request query budget with its number per route: timer/state refresh 1, navigation 1–2, answer save 6, authorized workforce request 2 + slice, exam entry ~10. Deliverable: query-budget card — the acceptance basis for `P7.12`.
5. [ ] Enumerate the telemetry hygiene obligations and their sources: `REQ-SEC-004` (no plaintext PIN in any log), `REQ-RSLT-021` (no OTP or authentication secret), `REQ-PRIV-004` (personal-data access role-restricted and audited), `REQ-AUD-002` (logs are a separate store from audit), §16.1 (candidate email and name hashed or omitted). Deliverable: hygiene card — the acceptance basis for Phase 6.
6. [ ] Register the four inbound sibling gap records, splitting each into its metric limb and its panel-or-alert limb with the receiving feature named. Deliverable: completed inbound-gap table. Acceptance: no limb is left without a receiving feature.
7. [ ] Map this feature's verification obligations to their real §19.8 identifiers: the log limb of the CI 10 secret-leak scan (owned here), `ARC-VERIFY-009`'s log clause (`FEAT-EXAM-002` owns the PIN limb; this feature supplies the redactor it relies on), `ARC-VERIFY-013`'s correlation clause (`FEAT-PLAT-003`), `ARC-VERIFY-017` (`FEAT-GRD-003`), `ARC-VERIFY-018` (configuration, and mis-described in plan §14.4). Deliverable: verification-ownership table recording `TASK-OBS1-DEFECT-002` and `-006`.
8. [ ] Identify every §16.2 metric with an unbounded label candidate and record the bounded alternative — tenant and correlation identifier to logs and span attributes, session and replica labels kept because their domains are bounded. Deliverable: cardinality risk register — the input to `P2.7`.
9. [ ] Enumerate the telemetry degradation contract from the plan's reliability expectation: an unreachable collector, a full queue, a slow sink and a serialisation failure must each degrade observability only. Deliverable: degradation card — the acceptance basis for `P7.16`.
10. [ ] List every consumer obligation this feature must satisfy before its dependants start, with the dependant feature and its task. Deliverable: consumer-contract table covering `FEAT-OPS-004`, `FEAT-OPS-005`, `FEAT-GRD-003` and the per-feature instrumentation obligation every later slice inherits.

---

# Phase 2 – Architecture and Design

1. [ ] Design the telemetry layering: `BusinessEvent`, `BusinessEventRecorder` and `RequestTelemetry` in `shared.kernel` with no vendor type; every OTel, Micrometer and encoder class in `platform.infra`. Deliverable: layering note plus the statement of the conformance rule that enforces it. Acceptance: a `slice` may reach telemetry only through the SLF4J façade and the two ports.
2. [ ] Design the structured log event as a **typed record** rather than a free-form map: field names, types, nullability and which fields are unconditionally present. Deliverable: log-schema specification. Acceptance: a field absent from the schema cannot be logged, which is what makes the §16.1 contract assertable instead of aspirational.
3. [ ] Design the JSON encoder to `ARC-OBS-001`: one event per line, RFC 3339 UTC timestamps, exceptions as a structured `error.stack` field, and no multi-line output in any non-local profile. Deliverable: encoder design note.
4. [ ] Design the redacting serialiser: word-boundary matching over `SecretFieldPattern`, the enumerated permitted-`key` allowlist with `policy_key` justified in it, and the redaction placeholder. Deliverable: redactor design note (`TASK-OBS1-DEFECT-003`).
5. [ ] Design the build-time redaction check over domain types, and its extension to span attributes and metric labels. Deliverable: check design note (`ARC-OBS-002`, `TASK-OBS1-DEFECT-004`). Acceptance: a domain type exposing a secret-named field to logging, tracing or a label fails the build, not the review.
6. [ ] Design the candidate-identifier hasher: keyed hash, per-environment salt resolved from the secret manager, and the rule that email and name are never emitted in any form. Deliverable: identifier-hashing design note. Acceptance: states that the salt is per environment so a hash cannot be joined across environments.
7. [ ] Design the metric naming convention and the label-cardinality budget: a declared maximum per metric, `tenantId` and `correlationId` forbidden as labels, and the assertion that enforces both. Deliverable: metric convention plus the per-metric budget table from `P1.8`.
8. [ ] Design the business-event recording path end to end: the enumeration, the port, the Micrometer adapter, the alignment of `eventCode` with the audit event type per §16.1, and the completeness assertion. Deliverable: business-event design note.
9. [ ] Design sampling: head-based 100% on the exam-entry and grading route classes, error-marked spans retained, and the tail decision delegated to the collector. Deliverable: sampling design note (`TASK-OBS1-DEFECT-007`). Acceptance: records plainly that without a tail-sampling collector the §16.3 error rule is unmet, so the deployment prerequisite is not optional.
10. [ ] Design trace-context propagation across the async boundary: `traceparent` and `tracestate` carriage in the outbox row and the broker message, and the consumer-side decision between a child span and a span link. Deliverable: propagation design note handed to `FEAT-PLAT-004`. Acceptance: the causal chain submission → outbox → relay → broker → grading consumer → result → notification is one navigable trace.
11. [ ] Design the per-request query counter: a Reactor-context-scoped counter incremented by an R2DBC statement listener, emitted as `dbQueryCount` on the request log line and as `db_query_duration` by slice. Deliverable: query-telemetry design note. Acceptance: independent of sampling, and never derived from spans.
12. [ ] Design the export pipeline: OTLP/gRPC endpoints for traces, metrics and logs; bounded queues; a hard export timeout; drop-oldest on overflow; and the prohibition on any exporter that can block a request thread or a Reactor scheduler. Deliverable: export design note.
13. [ ] Design the observability-configuration startup validation limb of `ARC-OBS-004`: the required settings, their ranges, and boot failure on an absent or out-of-range value. Deliverable: startup-validation design note (`TASK-OBS1-DEFECT-006`). Acceptance: a silently defaulted sampling ratio or an absent redaction allowlist fails the boot rather than shipping unnoticed.
14. [ ] Design the feature's self-observability: export success rate, dropped spans, dropped log events, redaction rejections and queue depth. Deliverable: self-observability set. Acceptance: the plan's own observability expectation — "its own health is itself monitored".
15. [ ] Design the correlation join: the same identifier in the response header, every log line, the `ProblemDetail`, the span and the metric exemplar, across a scheduler hop and the relay. Deliverable: join design note — the acceptance basis for `P7.14`.
16. [ ] Design the log-versus-audit separation: distinct sinks, distinct retention, sampling permitted on logs and prohibited on audit, and the configuration review that keeps them distinct. Deliverable: separation note (`REQ-AUD-002`).
17. [ ] Design the reference instrumentation on `FEAT-PLAT-001`'s conformance reference slice as the worked example every later slice copies, plus the instrumentation review checklist. Deliverable: reference-instrumentation design note.

---

# Phase 3 – Data and Infrastructure

1. [ ] Record that this feature makes **no schema change**: telemetry is exported and nothing is persisted in the platform's own stores. Deliverable: one-line data-impact statement referenced by CI stage 12's exclusion.
2. [ ] Add the OpenTelemetry SDK and Micrometer through BOMs, resolvable only from `platform.infra`. Deliverable: dependency block. Acceptance: no vendor telemetry type is on the compile classpath of any `domain` package.
3. [ ] Define the twelve-factor configuration surface: collector endpoint, protocol, TLS, head-sampling ratios per route class, the redaction allowlist, the hash-salt secret reference and the export queue bounds. Deliverable: configuration schema consumed by `P4.26`.
4. [ ] Wire the JSON encoder as the only production log appender and forbid a pattern-layout console appender in every non-local profile. Deliverable: logging configuration plus the profile assertion.
5. [ ] Agree the collector contract with Platform Ops: endpoint, workload identity, TLS, and the **tail-sampling processor** the §16.3 error rule depends on. Deliverable: collector contract record (`TASK-OBS1-DEFECT-001`, `-007`). Acceptance: the tail-sampling requirement is stated as a requirement on the stack, not an assumption about it.
6. [ ] Add the in-memory test substrate — OTel in-memory span and metric exporters plus a capturing log appender — for CI stages 5 and 7. Deliverable: test fixtures. Acceptance: a slice test can assert on an emitted span, meter and log line without a running collector.
7. [ ] Add an OTLP sink to CI stage 8 as a Testcontainer or stub so export is proven end to end against real serialisation. Deliverable: integration substrate.
8. [ ] Extend the CI stage 10 secret-leak scan with the **log** limb, alongside the existing audit and error-response limbs. Deliverable: scan configuration. Acceptance: BLOCKING, per §18.1 stage 10 and `NFR-SEC-002`.
9. [ ] Add the business-event completeness assertion, the label-cardinality assertion and the proposed per-route query-budget assertion to the pipeline at stages 4, 5 and 8 respectively. Deliverable: three gate configurations (`TASK-OBS1-OBS-001`).

---

# Phase 4 – Backend Implementation

1. [ ] Implement `BusinessEvent` in `shared.kernel` as a closed type over the six MVP event codes, carrying only the `eventCode` and its bounded dimensions. Deliverable: kernel type. Acceptance: no vendor import; the type compiles with the kernel-purity rule green.
2. [ ] Implement the `BusinessEventRecorder` port in `shared.kernel`. Deliverable: port interface.
3. [ ] Implement the `RequestTelemetry` port in `shared.kernel` for the per-request query count and duration. Deliverable: port interface.
4. [ ] Implement the typed log-event record from `P2.2`. Deliverable: log-event type plus its construction site.
5. [ ] Implement the redacting serialiser from `P2.4` on top of the kernel's `SecretFieldPattern`, with word-boundary matching and the permitted-`key` allowlist. Deliverable: redactor. Acceptance: `policy_key` survives; `pin`, `otp`, `token`, `secret`, `password`, `authorization` and any other `*_key` do not.
6. [ ] Implement the build-time redaction check over domain types exposed to logging. Deliverable: check plus its failure message (`ARC-OBS-002`).
7. [ ] Extend the check to span attributes and metric labels. Deliverable: two additional check limbs (`TASK-OBS1-DEFECT-004`).
8. [ ] Implement the keyed candidate-identifier hasher with the per-environment salt from the secret manager. Deliverable: hasher plus its configuration binding.
9. [ ] Implement the JSON encoder from `P2.3`. Deliverable: encoder. Acceptance: an exception produces a single line with a structured `error.stack`, never a multi-line trace.
10. [ ] Implement the log-field enricher reading `FEAT-PLAT-001`'s `ContextSnapshot` bridge, populating the §16.1 always-present fields plus the conditional actor and tenant fields. Deliverable: enricher. Acceptance: a log line emitted from a Reactor operator after a scheduler hop still carries the correlation identifier.
11. [ ] Implement the Micrometer adapter for `BusinessEventRecorder` in `platform.infra`. Deliverable: adapter.
12. [ ] Register the golden-signal and saturation meters available at Phase 0: `http_server_requests` by route class and audience, `db_pool_acquire_duration`, `db_pool_pending`, `db_query_duration` by slice, `redis_command_duration`, `redis_errors_total`. Deliverable: meter registrations. Acceptance: the error-rate meter distinguishes platform 5xx from 4xx, since `NFR-PERF-001`'s SLI excludes client errors.
13. [ ] Register the `TASK-PLAT3-OBS-001` metrics: `problem_detail_emitted_total` by `code`, `problem_detail_unmapped_total`, `idempotency_replay_total` by outcome, `idempotency_store_unavailable_total`. Deliverable: four registrations plus evidence of increment.
14. [ ] Register the `TASK-PLAT2-OBS-001` metrics: `db_context_missing_total`, `db_context_install_failure_total` by role, `db_role_assumption_total` by role, `db_connection_reset_failure_total`. Deliverable: four registrations plus evidence of increment.
15. [ ] Register the `TASK-PLAT5-OBS-001` metrics: `migration_lock_held_seconds` and `migration_outcome_total` by outcome, emitted from the migration Job's platform scope. Deliverable: two registrations plus evidence.
16. [ ] Implement the label-cardinality guard from `P2.7`, rejecting a registration that declares an unbounded label. Deliverable: guard plus its failure message.
17. [ ] Implement the OTel tracer configuration: resource attributes, the `<module>.<verbNoun>` naming from `FEAT-PLAT-001 tasks.md` `P2.9`, and the mandatory attribute set. Deliverable: tracer configuration.
18. [ ] Implement the span-attribute redaction filter. Deliverable: filter (§16.3, `TASK-OBS1-DEFECT-004`).
19. [ ] Implement the hybrid sampler: head-based per route class, error-marked spans retained for the collector's tail decision. Deliverable: sampler.
20. [ ] Implement HTTP-server and slice-handler span instrumentation, and apply it to the conformance reference slice as the worked example. Deliverable: instrumentation plus a captured trace.
21. [ ] Implement R2DBC statement spans carrying the statement **name only** and never a parameter value. Deliverable: instrumentation. Acceptance: asserted by a negative test in `P7.7`, since a parameter value is where a PIN would leak into a trace.
22. [ ] Implement the query counter listener and emit `dbQueryCount` on the request log line and `db_query_duration` by slice. Deliverable: counter plus emission.
23. [ ] Implement the consumer-side trace continuation from `FEAT-PLAT-004`'s existing carrier — reading the row columns and message headers, and deciding child span versus span link per `P2.10`. Deliverable: continuation implementation. Acceptance: consumes `FEAT-PLAT-004 tasks.md` `P4.3`'s carriage without reimplementing it, and a message whose trace context is absent starts a new trace rather than failing.
24. [ ] Implement the OTLP exporters for traces, metrics and logs with the bounded-queue, hard-timeout, drop-oldest contract from `P2.12`. Deliverable: exporters. Acceptance: no exporter can block a request thread or a Reactor scheduler.
25. [ ] Implement the self-observability metrics from `P2.14`: export success rate, dropped spans, dropped log events, redaction rejections, queue depth. Deliverable: five registrations.
26. [ ] Implement the observability-configuration startup validation from `P2.13`. Deliverable: validator. Acceptance: an absent collector endpoint, an out-of-range sampling ratio, an absent redaction allowlist or an absent hash-salt reference each fail the boot.
27. [ ] Implement the conformance rule: no OTel or Micrometer import in `domain` or `slice`, and a business metric may be recorded only through `BusinessEventRecorder`. Deliverable: rule added to the CI stage 4 suite with its failure message.
28. [ ] Implement the business-event completeness assertion over the closed enumeration. Deliverable: assertion (`TASK-OBS1-DEFECT-005`). Acceptance: removing an MVP event or adding one without registering its metric fails the build; the two Post-MVP events are recorded as declared-absent rather than missing.

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-OBS-001` is a backend platform feature with no user interface. It does fix one
contract the frontend volumes consume — the `X-Correlation-Id` response header, published by
`FEAT-PLAT-003 tasks.md` `P8.1` and confirmed traceable end to end here in `P9.3` — so a client can quote a single
identifier to support. Frontend telemetry (browser errors, real-user timings) is out of scope for this
feature and for the plan's Phase 0.

---

# Phase 6 – Security and Hardening

Telemetry hygiene is a security control, not a tidiness concern: `NFR-SEC-002` makes the CI stage 10 suite
a release gate, and its log limb is this feature's.

1. [ ] Attempt to log a plaintext PIN through every path — a domain field, a map value, a nested object, an exception message and a `toString()` — and confirm each is redacted or refused. Deliverable: five adversarial attempts, each recorded (`REQ-SEC-004`).
2. [ ] Repeat the same five attempts for an OTP, an authentication token and a password. Deliverable: adversarial-attempt matrix (`REQ-RSLT-021`).
3. [ ] Verify no candidate email or name reaches a log line, a span attribute or a metric label from any path, and that the candidate reference appears only as a keyed hash. Deliverable: personal-data hygiene evidence (`REQ-PRIV-004`, §16.1).
4. [ ] Verify answer content never reaches a span attribute or a log line, including through an R2DBC statement parameter and a serialisation failure message. Deliverable: answer-content evidence (§16.3).
5. [ ] Verify the redactor fails closed: an unknown field type, a cyclic object and a serialiser exception each produce a redacted placeholder rather than an unredacted value or a dropped line. Deliverable: three fail-closed cases.
6. [ ] Verify a hostile log value — newline, control character, ANSI escape, JSON fragment, over-long string — cannot forge a second log event or break the JSON envelope. Deliverable: log-injection hardening evidence. Acceptance: asserted against the sink output, not the encoder's return value.
7. [ ] Confirm the correlation identifier carries no personal data and is not derived from an actor, tenant or email, and that a malformed inbound header was already replaced by the kernel rather than echoed into a log line. Deliverable: correlation-privacy review referencing `FEAT-PLAT-003 tasks.md` `P6.4`.
8. [ ] Confirm the hash salt resolves from the secret manager, is distinct per environment, and appears in no image, config map or committed file. Deliverable: secret-handling review (`REQ-SEC-008`, `ARC-SEC-013`).
9. [ ] Confirm telemetry export credentials use workload identity, that the collector endpoint is TLS-protected, and that telemetry leaves no personal data at the boundary. Deliverable: export-boundary review.
10. [ ] Review the log and audit sinks for separation of store, retention and access, and confirm no operational log path writes to the audit store or vice versa. Deliverable: separation review (`REQ-AUD-002`).

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation of this feature: it is the mechanism by which every later feature's telemetry
is judged, so its own assertions must be executable rather than inspected. Plan §14.4 assigns it no
`ARC-VERIFY` scenario (`TASK-OBS1-DEFECT-002`), so the three named expectations ship as CI assertions
against existing stages.

1. [ ] Implement the correlation-propagation test across HTTP → transaction → outbox → broker → consumer, asserting one identifier and one navigable trace end to end. Deliverable: propagation test in CI stage 8 (`NFR-OBS-002`). Acceptance: the plan's first named testing expectation, executed against real Testcontainers broker and database, not stubs.
2. [ ] Extend the propagation test across a scheduler hop and the outbox relay batch, since the relay is where a `ThreadLocal`-shaped assumption would silently break the chain. Deliverable: two additional cases.
3. [ ] Test the business-event metric completeness assertion, including the negative case where an MVP event is removed and the case where a Post-MVP event is added early. Deliverable: assertion test plus two negative cases. Acceptance: the plan's third named testing expectation.
4. [ ] Run the log limb of the CI stage 10 secret-leak scan and confirm a clean baseline. Deliverable: scan result, BLOCKING. Acceptance: the plan's second named testing expectation and part of its Definition of Done.
5. [ ] Add a negative test introducing a secret-named field on a domain type exposed to logging and assert the build fails. Deliverable: negative test with the field reverted and the failure retained as evidence.
6. [ ] Add the equivalent negative tests for a secret-named span attribute and a secret-named metric label. Deliverable: two negative tests (`TASK-OBS1-DEFECT-004`).
7. [ ] Add a negative test asserting an R2DBC statement parameter cannot reach a span attribute. Deliverable: negative test.
8. [ ] Test the redactor against the `policy_key` allowlist case and against every other `*_key` name, proving the allowlist is enumerated rather than permissive. Deliverable: allowlist boundary test (`TASK-OBS1-DEFECT-003`).
9. [ ] Add a negative test introducing an OTel or Micrometer import into a `slice` and a `domain` package, asserting the conformance rule fails the build in each case. Deliverable: two negative tests.
10. [ ] Add a negative test recording a business metric directly through Micrometer from a slice, asserting the rule rejects it. Deliverable: negative test.
11. [ ] Test the label-cardinality guard: a metric declaring `tenantId` or `correlationId` as a label fails registration. Deliverable: two negative tests.
12. [ ] Assert the §15.2 per-route query budget from `P1.4` against the reference slice and the routes available at Phase 0, and register the assertion as the proposed CI 8 gate. Deliverable: query-budget test plus the gate proposal (`TASK-OBS1-OBS-001`). Acceptance: an added query on a budgeted route fails the assertion rather than surfacing as a latency regression in Phase 6.
13. [ ] Test the log schema: every §16.1 always-present field is present on every line, and a field absent from the schema cannot be emitted. Deliverable: schema conformance test.
14. [ ] Test the correlation join four ways — response header, log line, span, `ProblemDetail` — on the same request, extending `FEAT-PLAT-003 tasks.md` `P7.10` to include the span and the metric exemplar. Deliverable: join test.
15. [ ] Test the sampler: 100% retention on the exam-entry and grading route classes, error spans marked for retention, and the configured ratio applied elsewhere. Deliverable: sampling test. Acceptance: records that the error-retention half is completed by the collector, per `TASK-OBS1-DEFECT-007`.
16. [ ] Integration-test the export degradation contract with the collector **stopped**: requests succeed at unchanged latency, the queue bounds hold, drops are counted, and no request fails. Deliverable: degradation test. Acceptance: run with the container stopped, not with a mocked exporter failure.
17. [ ] Test the export contract under a slow sink — an artificial export delay beyond the hard timeout — and assert no request thread or Reactor scheduler blocks. Deliverable: slow-sink test.
18. [ ] Test the observability-configuration startup validation: an absent endpoint, an out-of-range sampling ratio, an absent redaction allowlist and an absent hash-salt reference each fail the boot. Deliverable: four startup tests (`ARC-OBS-004` limb).
19. [ ] Test the self-observability metrics increment under a forced export failure and a forced redaction rejection. Deliverable: two tests.
20. [ ] Register the retained artifacts in the §19.9 verification evidence register: the propagation test report, the business-event completeness result and the log-limb leak-scan result. Deliverable: register entries, with `TASK-OBS1-DEFECT-002`'s missing-identifier gap noted against each.
21. [ ] Run the full pipeline on a clean checkout and confirm stages 4, 5, 8 and 10 are blocking and green for this feature's contributions. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
22. [ ] Verify each acceptance outcome in the `FEAT-OBS-001` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

1. [ ] Publish the observability configuration surface per environment — collector endpoint, sampling ratios, allowlist, salt reference — with no environment-specific code branch. Deliverable: configuration record per environment (`ARC-OPS-004`).
2. [ ] Confirm the telemetry configuration is identical across the three runtime roles from `FEAT-PLAT-006`, differing only in the resource attribute that names the role. Deliverable: role-parity review. Acceptance: `api`, `worker` and `pindist` are distinguishable in telemetry without three configurations to maintain.
3. [ ] State the rollback path: the feature is code and configuration only with no schema change, so a rollback is a code revert; a metric or log field is additive, and **removing** a published metric name is a breaking change for `FEAT-OPS-004`'s dashboards and alerts. Deliverable: rollback statement.
4. [ ] Close the reciprocal obligation with `FEAT-PLAT-004`: confirm the carrier it shipped satisfies the end-to-end join, and record that `FEAT-PLAT-004 tasks.md` `P9.5`'s deferred join test is discharged by `P7.1`. Deliverable: reciprocal closure record.
5. [ ] Hand the registered metric inventory, the log field contract, the span contract and the four inbound gap records' panel-and-alert limbs to `FEAT-OPS-004`. Deliverable: four handover records referenced by launch condition `L5`.
6. [ ] Hand the `dbQueryCount` and `db_query_duration` telemetry plus the query-budget assertion to `FEAT-OPS-005` as the input for the `ARC-PERF-004` and §15.2 evidence. Deliverable: handover record.
7. [ ] Confirm the Phase 0 exit criterion "correlation identifiers propagating end to end" is met by `P7.1` and `P9.3`, and record it against plan §10. Deliverable: exit-criterion evidence record.
8. [ ] Record the deferrals with their owning features: the seven dashboards, the alert set and every threshold (`FEAT-OPS-004`, `L5`); performance measurement runs (`FEAT-OPS-005`); per-capability metrics (each owning feature); frontend telemetry (out of programme scope); the observability backend product itself (**unowned** — `TASK-OBS1-DEFECT-001`, escalated in `P0.5`). Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Confirm the six MVP business-event metrics are live and increment on their domain events, with `eventCode` aligned to the audit event type per §16.1. Deliverable: six metrics plus evidence (`NFR-OBS-001`).
2. [ ] Confirm the §16.1 field set is present on every log line in a running deployment, and that `actorId` is a safe identifier only — never an email, never a name. Deliverable: log-field conformance record from live output.
3. [ ] Confirm the correlation identifier is the join key across logs, metric exemplars and traces end to end, including across the relay and a scheduler hop. Deliverable: `NFR-OBS-002` diagnosability evidence — the Phase 0 exit criterion.
4. [ ] Confirm the self-observability set is live and that a collector outage is visible in it within one export interval. Deliverable: self-observability evidence. Acceptance: the feature's own failure is observable without depending on the path that failed.
5. [ ] Hand the four inbound gap records' alert and panel proposals to `FEAT-OPS-004` with their severities and first actions intact, and confirm receipt. Deliverable: completed inbound-gap discharge record.
6. [ ] Propose the two alerts this feature's own health warrants, for `FEAT-OPS-004` to define: a **P2** on a sustained telemetry-export failure rate with "check the collector and confirm no request-path impact" as its first action, and a **P2** on a sustained redaction-rejection rate, since a rising rejection rate means a new code path is attempting to log a secret. Deliverable: two alert proposals.
7. [ ] Write the operations runbook for tracing a reported failure from a correlation identifier: where the identifier appears, how to move from log line to trace to metric exemplar, and what the client saw. Deliverable: runbook — the artifact `FEAT-OPS-002`'s operator surfaces link to.
8. [ ] Write the operations runbook for a collector or sink outage: the expected symptom set, the confirmation that no request failed, the drop counters to read, and the recovery order. Deliverable: runbook.
9. [ ] Confirm log retention and sampling policy is configured on the log sink and is distinct from audit retention. Deliverable: retention configuration record (`REQ-AUD-002`).

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the structured-log field contract as the normative platform interface, with the provenance table from `P1.1`. Deliverable: `docs/logging-contract.md`.
2. [ ] Publish the metric catalogue and naming convention, marking each metric's owning feature and its label-cardinality budget. Deliverable: `docs/metric-catalogue.md` — the input `FEAT-OPS-004` builds panels from.
3. [ ] Publish the tracing contract: span naming, the span inventory, mandatory attributes, the prohibited attribute categories and the sampling rules. Deliverable: `docs/tracing-contract.md`.
4. [ ] Write the instrumentation authoring guide for slice authors: how to record a business event through the port, what to log and what never to, which fields arrive automatically, and how to run the telemetry assertions locally. Deliverable: `docs/instrumentation-authoring.md` — the guide every later feature follows to satisfy the universal DoD's "required telemetry exists" clause.
5. [ ] Write the telemetry hygiene guide: the redaction pattern, the permitted-`key` allowlist and why it is enumerated, the personal-data rules, and what the CI stage 10 log limb will reject. Deliverable: `docs/telemetry-hygiene.md`.
6. [ ] Write the query-budget guide: the §15.2 per-route numbers, how `dbQueryCount` is measured, and what to do when a route exceeds its budget. Deliverable: `docs/query-budget.md`.
7. [ ] Document the telemetry degradation contract for operations and for reviewers: what an unreachable collector does and does not affect. Deliverable: `docs/telemetry-degradation.md`.
8. [ ] Document the observability layering decision — ports in the kernel, adapters in `platform.infra`, no vendor type in `domain` or `slice` — with the conformance rule that enforces it. Deliverable: `docs/observability-architecture.md`. Acceptance: states why a business event is recorded through a port rather than from an HTTP filter.
9. [ ] Raise `TASK-OBS1-DEFECT-001` through `-007` and `TASK-OBS1-OBS-001` to the Architecture Owner as baseline defects, each with the resolution this feature adopted, and raise `-003` to the `FEAT-PLAT-003` owner as a correction at the `SecretFieldPattern` definition site. Deliverable: eight defect records.
10. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, CI stages and retained artifacts, noting that no `ARC-VERIFY` identifier is assigned. Deliverable: updated matrix rows.
11. [ ] Run a walkthrough with the engineering team covering the port-not-vendor rule, the log schema, the redaction allowlist, the business-event enumeration and the query budget. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                            | Architecture reference | Tasks                                                     | Verification                                                    |
|-------------------------------------------------------------------|------------------------|-----------------------------------------------------------|-----------------------------------------------------------------|
| `NFR-OBS-002` end-to-end diagnosability by correlation identifier | §16.1, §16.3           | `P2.15`, `P4.10`, `P4.23`, `P7.1`, `P7.2`, `P7.14`, `P9.3` | Propagation test across the broker (CI 8); four-way join test    |
| `NFR-OBS-001` business events measurable                          | §16.2                  | `P1.2`, `P2.8`, `P4.1`, `P4.11`, `P4.28`, `P9.1`          | Completeness assertion over a closed enumeration (CI 4)         |
| `CONSTRAINT-PLAT-006` structured logs, metrics, OTel tracing       | §16.1–§16.3            | `P3.2`, `P4.9`, `P4.17`, `P4.24`                          | Vendor types confined to `platform.infra` by `P4.27`            |
| `REQ-SEC-004` no plaintext PIN in any log                          | §16.1                  | `P4.5`, `P4.6`, `P6.1`, `P7.4`, `P7.5`                    | CI 10 log limb, BLOCKING; five adversarial attempts             |
| `REQ-RSLT-021` no OTP or authentication secret in telemetry        | §16.1                  | `P4.5`, `P6.2`, `P7.4`                                    | CI 10 log limb; adversarial-attempt matrix                      |
| `REQ-PRIV-004` personal data restricted                            | §16.1                  | `P2.6`, `P4.8`, `P6.3`                                    | Keyed hash with a per-environment salt; hygiene evidence        |
| `REQ-SEC-010` correlation identifier on every error response       | §10.4, §16.1           | `P2.15`, `P7.14`                                          | `ARC-VERIFY-013` (`FEAT-PLAT-003` owns; joined here)            |
| `REQ-AUD-002` logs are not the audit trail                         | §16.1                  | `P2.16`, `P6.10`, `P9.9`                                  | Separation review; distinct sinks and retention                 |
| `ARC-OBS-001` JSON to stdout, one event per line                   | §16.1                  | `P2.2`, `P2.3`, `P4.4`, `P4.9`, `P7.13`                   | Schema conformance test                                         |
| `ARC-OBS-002` logging serialisation allowlist, build-failing        | §16.1                  | `P2.4`, `P2.5`, `P4.5`–`P4.7`, `P7.5`, `P7.6`, `P7.8`     | Negative tests at all three emission surfaces                   |
| `ARC-OBS-003` OTel with W3C context across outbox and broker       | §16.3                  | `P2.9`, `P2.10`, `P4.17`–`P4.21`, `P4.23`, `P7.15`        | Propagation test; sampling test                                 |
| `ARC-OBS-004` startup validation (observability limb only)         | §17.5                  | `P2.13`, `P4.26`, `P7.18`                                 | Four startup-failure tests (`TASK-OBS1-DEFECT-006`)             |
| `ARC-PERF-004` / §15.2 per-request query budget                    | §15.2, §16.1           | `P1.4`, `P2.11`, `P4.22`, `P7.12`                         | Per-route budget assertion proposed for CI 8 (`TASK-OBS1-OBS-001`) |
| `NFR-PERF-001` error-rate SLI excludes 4xx                         | §16.2                  | `P4.12`                                                    | Meter distinguishes platform 5xx from client 4xx                |
| Reliability — export failure never affects availability            | plan §8.1              | `P1.9`, `P2.12`, `P4.24`, `P7.16`, `P7.17`                | Degradation test with the collector stopped                     |
| Clean-architecture layering — ports in the kernel                  | §8.4, §16              | `P2.1`, `P4.1`–`P4.3`, `P4.27`, `P7.9`, `P7.10`           | Conformance rule in CI 4 plus three negative tests              |
| Metric label cardinality bounded                                   | §16.2                  | `P1.8`, `P2.7`, `P4.16`, `P7.11`                          | Registration guard plus two negative tests                      |
| Self-observability of the observability feature                    | plan §8.1              | `P2.14`, `P4.25`, `P7.19`, `P9.4`, `P9.6`                 | Metrics live; two alerts proposed to `FEAT-OPS-004`             |
| Inbound gap records from the sibling Phase 0 lists                 | §16.2, §16.4           | `P1.6`, `P4.13`–`P4.16`, `P9.5`                           | Metric limb discharged here; alert limb handed over             |
| `PLAN-BLOCKER-001`                                                 | plan §10, §18.3        | `P0.1`                                                     | Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`                    |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence
from oversight.

| Excluded                                                                                                        | Owner                                              |
|-----------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| The seven §16.5 dashboards, the §16.4 alert set, every threshold, severity and routing, and the alert exercises   | `FEAT-OPS-004` (launch condition `L5`)             |
| Performance measurement runs and the `ARC-PERF-006` envelope assertion                                           | `FEAT-OPS-005`                                     |
| The `WebFilter`, the `ContextSnapshot` logging bridge, scheduler-hop propagation and the span-naming contract     | `FEAT-PLAT-001`                                    |
| The ULID correlation identifier, its validation, replace-not-echo, `ActorContext` and `SecretFieldPattern`         | `FEAT-PLAT-003`                                    |
| The outbox row, its trace-context column and the broker relay                                                    | `FEAT-PLAT-004`                                    |
| The audit store, the hash chain and the audit event catalogue                                                    | `FEAT-AUD-001`                                     |
| `feedback_slo_population_excluded_total` and `ARC-VERIFY-017`                                                    | `FEAT-GRD-003`, `FEAT-OPS-005`                     |
| The PIN limb of `ARC-VERIFY-009` and the PIN cryptographic paths                                                 | `FEAT-EXAM-002`                                    |
| Per-capability metrics for exam delivery, grading, notification, identity, retention and audit                    | Each owning capability feature                     |
| Extending the leak scan and fault injection to every endpoint, and the `L6` release gate                          | `FEAT-SEC-001`                                     |
| Operator diagnostic surfaces and the read-only diagnostic role                                                   | `FEAT-OPS-002`                                     |
| The security and policy limbs of §17.5 startup validation (`ARC-VERIFY-018`, `ARC-VERIFY-022`)                    | `FEAT-PLAT-006`, `FEAT-IAM-003`, `FEAT-NOTF-004`   |
| Frontend and real-user telemetry                                                                                 | Out of programme scope; not in `plan.md`           |
| **The observability backend itself — collector, metric store, log store, sizing, retention and HA**              | **Unassigned; escalated in `P0.5`**                |
| Ratification of the architecture baseline as a governance act                                                    | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] The correlation-propagation test across HTTP → transaction → outbox → broker → consumer is **green** (`P7.1`, `P7.2`).
2. [ ] The secret-leak scan's **log** limb is BLOCKING and green in CI stage 10 (`P3.8`, `P7.4`).
3. [ ] The `NFR-OBS-001` business-event metric set is **asserted complete**, not conventional (`P4.28`, `P7.3`).
4. [ ] Every request and error is traceable by a correlation identifier that also appears in the client-facing error response (`P7.14`, `P9.3`).
5. [ ] Traces span the outbox and broker boundary (`P4.23`, `P7.1`).
6. [ ] No log line, span attribute or metric label contains a PIN, OTP or credential (`P6.1`, `P6.2`, `P7.4`–`P7.7`).
7. [ ] The per-request query count is measured on every request, independent of sampling (`P4.22`, `P7.12`).
8. [ ] Telemetry export failure degrades observability and never availability, proven with the collector stopped (`P7.16`, `P7.17`).
9. [ ] The feature's own health — export success rate and dropped-span count — is monitored (`P4.25`, `P9.4`).

### Universal (plan §8.0), as far as this feature can discharge it

10. [ ] All mapped acceptance outcomes verified (`P7.22`).
11. [ ] Unit, slice and integration tests pass, including the telemetry assertions added at stages 4, 5, 8 and 10 (`P7.21`).
12. [ ] CI stage 4 is green for the code this feature adds, including the new no-vendor-telemetry-in-`domain`-or-`slice` rule (`P4.27`, `P7.9`).
13. [ ] Error responses carry a correlation identifier and leak no internal detail — joined to logs, spans and exemplars here (`P7.14`).
14. [ ] **Required telemetry exists** (`P4.11`–`P4.15`, `P9.1`) — this feature is the mechanism by which every other feature discharges that clause of the universal DoD.
15. [ ] No credential or secret exists in source; the hash salt and export credentials resolve at runtime (`P6.8`, `P6.9`).
16. [ ] The rollback path is stated, including that removing a published metric name is a breaking change for `FEAT-OPS-004` (`P8.3`).
17. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
18. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.10`).
19. [ ] **Not dischargeable by this feature, and recorded as such:** tenant isolation coverage by the isolation matrix (no endpoint is added; `FEAT-PLAT-002` and `FEAT-SEC-001` own it); in-transaction audit emission (`FEAT-AUD-001` — this feature aligns `eventCode` with the audit event type but writes no audit record); the OpenAPI breaking-change diff (CI stage 9 — no API surface is added); migration verification (CI stage 12, `FEAT-PLAT-005` — this feature makes no schema change); the dashboards and the alert set (`FEAT-OPS-004`, launch condition `L5`); the observability backend itself (**unowned**, `TASK-OBS1-DEFECT-001`).
