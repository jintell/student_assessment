# Expand/Contract Migration Pipeline Design

| Attribute | Value |
|---|---|
| Status | Approved for implementation |
| Feature | `FEAT-PLAT-005` |
| Architecture baseline | `arch-v1.4` |
| Approved thresholds | `config/lock-duration-thresholds.yml` |

This document is the implementation contract for migration pipeline tasks
`P2.1` through `P2.16`. The pipeline is build and infrastructure tooling. Its
core policies do not depend on application code, and runtime integrations are
reached only through explicit ports.

## P2.1 Migration Script Header Contract

Every versioned migration starts with exactly these four directives, in this
order, before any SQL token or non-header comment:

```sql
-- cbt:phase EXPAND
-- cbt:module delivery
-- cbt:transactional false
-- cbt:justification FEAT-DLV-002 add answer lookup index
```

The grammar is:

```text
header          = phase LF module LF transactional LF justification LF
phase           = "-- cbt:phase " ("EXPAND" | "MIGRATE" | "CONTRACT")
module          = "-- cbt:module " module-name
transactional   = "-- cbt:transactional " ("true" | "false")
justification   = "-- cbt:justification " printable-text
module-name     = "academic" | "audit" | "authoring" | "correction" |
                  "delivery" | "examaccess" | "grading" | "iam" |
                  "notification" | "outbox" | "people" | "platform" |
                  "questionbank" | "result" | "tenancy"
```

Files are UTF-8 without a byte-order mark and use LF line endings. Directive
names and enum values are case-sensitive. `printable-text` is a trimmed,
single-line explanation containing at least one non-whitespace character.
Blank lines may follow the fourth directive but may not appear within the
header. Duplicate, reordered, unknown, or extra `cbt:` directives are errors.
The module must match the script's
`db/migration/<module>` location. `CREATE INDEX CONCURRENTLY`, `DROP INDEX
CONCURRENTLY`, and `REINDEX CONCURRENTLY` require `transactional false`; all
other initially permitted shapes require `transactional true`.

The gate reads only the first four lines into a bounded header parser. It
validates the complete directive set and values before opening the remaining
content for SQL parsing. There is no inferred module, transaction mode, or
default phase. A failure reports the repository-relative filename, line when
available, the offending value without expanding secrets, and the exact
expected directive set shown above.

## P2.2 Closed DDL Allowlist Grammar

The analyser parses each complete statement into a PostgreSQL-aware syntax
tree, normalizes unquoted identifiers to lower case, and schema-qualifies every
relation from the header module. Matching is against typed tree shapes and
semantic predicates, never source text or regular expressions. Comments and
whitespace are discarded by the parser; dynamic SQL, procedural bodies,
client meta-commands, parser recovery nodes, and multiple operations hidden in
one `ALTER TABLE` are rejected.

The initial closed allowlist is:

| Phase | Permitted parsed statement shape | Required predicates |
|---|---|---|
| `EXPAND` | `CREATE TABLE` | New table in the header module; every column nullable or supplied with a constant default; no table rewrite expression |
| `EXPAND` | `ALTER TABLE ... ADD COLUMN` | One column, module-owned table, nullable or constant default, and no inline uniqueness, primary-key, foreign-key, or check validation |
| `EXPAND` | `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` | One check or foreign-key constraint on a module-owned table; validation is deferred |
| `EXPAND` | `CREATE [UNIQUE] INDEX CONCURRENTLY` | Explicit schema-qualified index and table in the header module; `transactional false` |
| `MIGRATE` | `ALTER TABLE ... VALIDATE CONSTRAINT` | Existing named constraint on a module-owned table; one operation only |
| `MIGRATE` | `ALTER TABLE ... ALTER COLUMN ... SET DEFAULT` | Constant default only; one module-owned table and column |
| `CONTRACT` | `ALTER TABLE ... DROP COLUMN` | One explicitly named obsolete column in the header module |
| `CONTRACT` | `ALTER TABLE ... DROP CONSTRAINT` | One explicitly named obsolete constraint in the header module |
| `CONTRACT` | `ALTER TABLE ... ALTER COLUMN ... DROP DEFAULT` | One explicitly named obsolete default in the header module |
| `CONTRACT` | `DROP INDEX CONCURRENTLY` | Explicit module-owned index; `transactional false` |
| `CONTRACT` | `DROP TABLE` | One explicitly named obsolete module-owned table; no `CASCADE` |

`COMMENT ON` is metadata, but it is admitted only when it immediately follows
an allowed create/add shape and targets that object. Transaction control,
`SET`, DML, grants, ownership changes, schema changes, extensions, functions,
triggers, `CASCADE`, unqualified relations, and every other statement shape
are rejected. Backfill DML belongs to the `P2.9` worker harness, not a Flyway
script. A regular `CREATE INDEX` is never permitted. The exam-critical registry
starts with `delivery.answer`, `delivery.answer_operation`,
`delivery.attempt`, and `audit.audit_event`; an owning feature extends it when
it adds another relation to that path.

An unrecognized or partially parsed shape is a failure, not a pass. The error
contains the filename, statement ordinal, phase, stable rejection code, and
the parser's normalized statement form. It does not echo comments or literal
values. Adding a shape requires an ADR-019 allowlist amendment, Platform Ops
and Engineering Lead review, updated positive and negative conformance cases,
and a new signed approval record through
`verifyClosedDdlAllowlistApproval`; configuration alone cannot expand it.

## P2.3 Static Analyser Module

The analyser is the standalone Gradle subproject `migration-verify`. It applies
only the Java library, application, test, coverage, formatting, and static
analysis plugins needed for a command-line build tool. Its production
dependency graph contains the selected PostgreSQL-capable SQL parser, a JSON
serializer for reports and manifests, and the Java runtime. Parser and
serializer versions are locked by `P3.2`; Spring Boot, Spring Modulith, R2DBC,
Flyway, and the root application's output are absent.

The module follows a small clean-architecture boundary:

```text
org.meldtech.migrationverify
|- core       immutable header, phase, statement and violation models
|- policy     header and closed-allowlist rules
|- port       SQL parser, migration source and result-output interfaces
|- adapter    parser, filesystem, command-line and JSON implementations
`- measure    PostgreSQL dataset and lock-measurement adapters
```

`core`, `policy`, and `port` use only the Java standard library. Parser syntax
tree types are translated at the parser-adapter boundary and never escape into
policy code. The command-line adapter accepts explicit paths and returns a
non-zero status for invalid input or an analyser failure; it has no environment
dependent policy switches.

`migration-verify` declares no Gradle `project(...)` dependency. A build check
inspects its resolved compile graph and compiled imports, failing on any root
application output, project dependency, or package beginning
`org.meldtech.platform` or the legacy `com.cbt.platform`. This is stricter than
the stated acceptance criterion and prevents application refactoring from
weakening the analyser. Tests use parser fixtures and ephemeral PostgreSQL only;
they do not start the application context.

## P2.4 Release Manifest Schema

Each release publishes one UTF-8 JSON manifest with this logical schema:

```json
{
  "schemaVersion": 1,
  "release": "2026.09.0",
  "classification": "EXPAND",
  "previousImageDigest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "migrationSetChecksum": "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "migrations": [
    {
      "module": "delivery",
      "path": "db/migration/delivery/V2__answer_lookup_index.sql",
      "phase": "EXPAND",
      "transactional": false,
      "sha256": "sha256:89abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567"
    }
  ]
}
```

`schemaVersion`, `release`, `classification`, `previousImageDigest`,
`migrationSetChecksum`, and `migrations` are required exactly once; unknown
fields fail schema validation. `classification` is exactly one of `EXPAND`,
`MIGRATE`, or `CONTRACT`, including for a release whose migration list is
empty. It is never an array or inferred from scripts. Image references must be
immutable OCI `sha256` digests; tags and source revisions are invalid.

Migration entries are sorted bytewise by module then repository-relative path.
Each entry is populated from the validated header and content digest. The set
checksum is SHA-256 over a canonical stream of each entry's path, NUL byte,
content SHA-256 bytes, and LF, in that order. Paths are unique, normalized,
inside an owned Flyway location, and cannot contain `..` or symbolic-link
escapes.

Generation fails unless every entry's `phase` equals the single manifest
`classification`. Consequently an `EXPAND` and `CONTRACT` migration can never
share a manifest or release, resolving `TASK-PLAT5-DEFECT-005`. Consumers
recompute the migration set checksum before analysis or execution and fail
closed on an absent entry, extra entry, ordering difference, digest mismatch,
unknown schema version, or unavailable previous image digest.

## P2.5 Lock-Hold Measurement Harness

Stage 12 runs statements through an instrumented JDBC executor and observes the
migrator from a separate connection. At startup the executor assigns a unique,
non-secret run identifier to `application_name`, records its
`pg_backend_pid()`, and exposes the current statement ordinal to the sampler.
The observer polls every 10 ms with one catalog query joining `pg_locks` to
`pg_stat_activity`, `pg_class`, and `pg_namespace`, restricted to that backend.
It records granted relation locks separately from waiting locks.

For every newly observed granted lock, the collector stores the statement that
acquired it, schema-qualified relation, exact PostgreSQL lock mode, first-seen
monotonic time, last-seen time, and transaction identity. Sampling continues
through commit or rollback. A lock retained after its statement finishes is
therefore charged to the acquiring statement until PostgreSQL releases it; it
is not reassigned to the next statement. Re-entrant samples extend one
continuous interval, while a release and reacquisition creates a new interval.
The reported maximum for `(statement, relation, lock mode)` is the greatest
continuous interval, conservatively rounded up by one sampling interval.

The harness marks a run invalid if a catalog query fails, the target backend
changes, statement attribution is ambiguous, or the gap between samples
exceeds 20 ms while a statement or transaction is active. The 10 ms interval
is below one tenth of the smallest 100 ms warning threshold, and conservative
rounding prevents a borderline hold being reported low. Wait duration and
wall-clock statement duration are retained as diagnostics but never
substituted for granted-lock hold duration.

Verdicts use exact lock compatibility, not a generic "statement took too
long" rule. `AccessExclusiveLock` and other modes that block the measured exam
read/write workload are checked against the approved relation threshold.
`ShareUpdateExclusiveLock` from an allowed concurrent index operation is
reported distinctly as online-compatible because it permits ordinary reads
and writes; its acquisition is bounded by `P2.6` and its statement runtime by
the stage's statement timeout. Thus a long `CREATE INDEX CONCURRENTLY` is not
misreported as a long blocking lock, while an actual blocking lock remains a
gate failure.

## P2.6 Preventive Lock Timeout

The executor selects `lock_timeout` from the analyser's complete set of touched
relations immediately before each statement. A statement touching any
exam-critical relation uses `250ms`; an ordinary statement touching only
non-critical relations uses `2s`. Transactional scripts apply the value with
`SET LOCAL lock_timeout` inside the migration transaction. Non-transactional
scripts use `SET lock_timeout` and reset it in a `finally` path before the
connection is reused. An unresolved or mixed relation set takes the most
restrictive value, and failure to set or verify the session value aborts the
migration.

Allowed `CREATE INDEX CONCURRENTLY`, `DROP INDEX CONCURRENTLY`, and `REINDEX
CONCURRENTLY` statements use a distinct `2s` acquisition timeout. They may
legitimately wait for `ShareUpdateExclusiveLock`, which is compatible with the
ordinary reads and writes the online form exists to preserve. Applying the
250 ms exam-critical value globally would create false failures under normal
catalogue maintenance or another online index operation, while applying 2 s
globally would permit a genuinely blocking lock request to wait eight times
too long on the exam path.

Only PostgreSQL lock-timeout failure (`SQLSTATE 55P03`) on an allowed
non-transactional concurrent-index statement is retried. The executor makes at
most three attempts, with fixed 250 ms then 750 ms interruptible backoff,
rechecks for an `INVALID` index through `P2.7` before each retry, and records
each attempt. Lock acquisition wait is therefore bounded to 7 s in total;
other errors fail immediately. The independent `statement_timeout` from
`P3.7` bounds work after acquisition and is never replaced by retry logic.

## P2.7 Invalid-Index Reconciliation

Reconciliation is an explicit idempotent operator command scoped by module,
failed migration version, and expected index. It first takes a module-specific
PostgreSQL advisory lock so two operators cannot repair the same history. It
then queries `pg_index` joined to `pg_class` and `pg_namespace` for
`indisvalid = false`, excluding system schemas. The lookup includes the named
index and concurrent-reindex `_ccnew` and `_ccold` artifacts on the same table.

The command refuses an unqualified name, a relation outside the header module,
an index not attributable to the failed script, or any collision with a valid
index. For each attributable invalid object it executes exactly one
schema-qualified `DROP INDEX CONCURRENTLY IF EXISTS` on an autocommit
connection. It never uses `CASCADE` and never wraps the drop in a transaction.
The catalog query is repeated until none of those invalid objects remains; an
unexpected invalid index is reported for manual review rather than dropped.

Only then may the module's Flyway history be repaired. Before invoking Flyway
`repair`, the command snapshots all successful history rows and proves their
versions, descriptions, types, and checksums match the currently resolved
migrations. It aborts on any successful-migration drift. Repair is configured
with only the affected module location, `platform_migrations` history schema,
and that module's history table. Afterward, the snapshot must be unchanged and
only the failed non-transactional row may have been removed. The command then
reruns the invalid-index query and the static analyser before allowing retry.

If neither an attributable invalid index nor the failed history row exists,
the command reports `NO_ACTION` and succeeds. If the first invocation stops
after the concurrent drop but before history repair, the next invocation sees
the index absent and safely continues. A completed invocation repeated with
the same arguments also returns `NO_ACTION`; it never drops a valid index or
rewrites successful migration history.

## P2.8 Production-Shaped Dataset Generator

The committed `migration/volumetrics.yaml` profile is the only dataset input.
It declares a schema version, profile version, fixed default seed, scale, and
one entry for every discovered application table. Each table entry declares
the exact row-count expression, primary-key strategy, parent references,
generation order, per-column synthetic strategy, and workload distribution.
A table that does not yet exist has an explicit zero row; a discovered table
without a profile entry fails generation.

Profiles derive their cardinalities from `P1.7`: 5,000 candidates in a
session, 50,000 platform-wide, about 60 presented questions, about 80 answer
saves including revisions, about 274 requests per candidate, and the 5,000
submission close burst. Values come from a specified, versioned deterministic
pseudorandom algorithm whose streams are split by schema, table, and column.
IDs, timestamps relative to a fixed epoch, enums, text, and skew are generated
from those streams. The generator never uses locale data, current time,
iteration order, random UUID APIs, Faker libraries, network input, or database
sequences to determine values.

Foreign-key dependencies form a directed acyclic load plan. Parent keys are
materialized before children; cyclic schemas require an explicitly reviewed
deferred-constraint load step and otherwise fail. Null frequency, tenant and
session skew, answer revisions, hot attempts, and close-burst rows are modeled
declaratively. All names, addresses, contacts, answers, and identifiers are
synthetic tokens with no production-derived dictionary. Production dumps,
extracts, snapshots, backups, and seeds are rejected by provenance checks.

Generation emits a canonical UTF-8/LF bundle: sorted table files with fixed
column order, deterministic row order, explicit null encoding, a manifest of
row counts and SHA-256 values, and one bundle checksum. Identical generator
version, profile bytes, scale, and seed must produce byte-for-byte identical
files and checksums. Stage 12 loads that bundle into the schema created by the
real migrations, verifies constraints and row checksums, then runs
`VACUUM (ANALYZE)` so actual indexes and PostgreSQL statistics describe the
loaded cardinality and skew. The report records the PostgreSQL image digest,
generator version, profile version and checksum, scale, and seed so the exact
input can be regenerated.

## P2.9 Resumable Backfill Harness

A backfill is a named, versioned worker operation, never a Flyway migration.
It starts through the `worker`-role entry point with a committed definition
containing the owning module, source and target columns, ordered primary-key
codec, batch size, rows-per-second limit, and deterministic row transform. The
definition checksum is immutable once a run starts. The migration Job neither
loads nor invokes backfill definitions, and release success never waits for a
backfill to complete. This resolves `TASK-PLAT5-DEFECT-004`.

Each owning schema stores a checkpoint keyed by backfill name and version with
definition checksum, exclusive cursor, captured upper bound, status, processed
count, and update time. One worker holds a transaction-scoped advisory lock for
that key. A batch selects at most the configured size in primary-key order from
`cursor < key <= upper_bound`, derives the target value, and writes only when
the row is not already in the desired state. The row updates and cursor advance
to the greatest selected key commit in the same short transaction. Failure
rolls both back; restart reads the last committed cursor. Reprocessing a row is
safe because the transform is deterministic and the update predicate is
idempotent.

Throughput uses a monotonic token bucket applied between committed batches.
Batch size and rate are positive, bounded configuration values; changing
either does not change the definition checksum or result. The worker pauses
for cancellation, an `OPEN` session window, or an `UNKNOWN` session-window
answer, then resumes from the checkpoint. Completion requires the cursor to
reach the captured upper bound and a verification query to find no eligible
row at or below it.

The harness never issues `LOCK TABLE`, table-wide updates, DDL, or a lock mode
stronger than the `RowExclusiveLock` inherent in its bounded row updates. It
holds row locks only for one batch transaction, uses the approved lock and
statement timeouts, and releases the advisory lock when paused. Thus it never
holds an explicit or blocking table-level lock, never blocks a release, and is
safe to interrupt and restart without duplicate work.

## P2.10 N-1 Against N Compatibility

The runner reads `previousImageDigest` only from the validated release manifest
and pulls that immutable OCI digest from the retained registry. A tag, local
image with the same name, checkout, or source rebuild is never an alternative;
an absent or unverifiable digest fails stage 12. The pulled image's resolved
digest is compared again before the container starts.

Against the pinned PostgreSQL 17 container, the runner creates the N-1 schema,
loads compatibility fixtures, applies release N migrations, and starts the
retained N-1 image with Flyway disabled and its normal least-privilege runtime
roles. It waits for readiness, then drives N-1's stable application boundaries
with the fixed compatibility protocol: read an existing row, create or update
through the old representation, read the result back, and verify the persisted
old and shared invariants. The protocol exercises both reads and writes; a
direct JDBC smoke query cannot satisfy it.

The analyser derives the schema-qualified touched-relation set from every
manifest migration. Each owning feature commits a compatibility case mapping
each touched table to the stable N-1 API or worker action, fixture, and
assertions. The fixed protocol is parameterized by those cases. Stage 12 fails
when a touched table has no case, when a case is skipped, when N-1 cannot start,
or when any read, write, or invariant fails. This keeps coverage tied to the
release's actual migration set rather than a permanent smoke-table list.

The report records the requested and resolved image digests, manifest and
migration checksums, touched tables, executed case identifiers, and outcomes.
Containers and credentials are isolated to the test network and destroyed
afterward. Passing proves `ARC-OPS-006` for that release: the deploy system may
roll code back to the retained N-1 image without attempting a schema rollback.

## P2.11 Session Window Query Port

The platform module exports a reactive
`org.meldtech.platform.platform.api.SessionWindowQuery` port. Its operation
accepts the target environment and an injected `Instant` decision time and
returns `Mono<SessionWindowResult>`. The result contains exactly one state,
`OPEN`, `NONE`, or `UNKNOWN`, plus a non-sensitive reason code, source name,
source observation time, and optional next boundary. It never exposes candidate
or session details.

`OPEN` means the authoritative source confirms at least one session whose
protected window contains the decision time. `NONE` means that source was
successfully queried and confirms no protected window. `UNKNOWN` covers a
missing adapter, source error or timeout, empty publisher, malformed response,
unsupported environment, or observation older than the configured freshness
limit. Only `NONE` permits normal deployment. Both `OPEN` and `UNKNOWN` return
a deploy refusal; callers may not translate an exception or empty result to
`NONE`.

The port and result types live in the platform API boundary. A default platform
adapter always returns `UNKNOWN` with reason `SOURCE_NOT_CONFIGURED`, so the
greenfield system fails closed. `FEAT-EXAM-001` owns the future adapter backed
by authoritative session data and must replace that default explicitly. The
deploy-freeze policy consumes only this port, uses reactive timeouts, and has no
compile dependency on the exam feature's internals.

## P2.12 Audited Emergency Override

An override request supplies only an opaque evidence-record identifier. That
identifier must resolve through an `EmergencyOverrideEvidenceQuery` port to an
immutable, integrity-verified record containing an active incident reference,
target environment, release-manifest checksum, deployment-attempt identifier,
reason, issue and expiry times, and two approvals. A pipeline parameter may
carry the identifier but cannot create, amend, or substitute for the record.
Environment variables, free-form flags, repository files, and a single
`force=true` value are never override authorities.

One approval must be from a named Engineering Lead and one from a named
Platform Ops approver. They must be distinct authenticated people, identify
their role at approval time, carry an attestation timestamp and signature, and
match the same record digest. Neither may be the deployment requester. The
incident resolver must confirm the referenced incident exists and remains
active. Missing, unreachable, expired, mismatched, revoked, duplicated, or
unverifiable evidence refuses the deployment.

The policy first records the `SessionWindowQuery` result. For `OPEN` or
`UNKNOWN`, valid evidence may authorize only its exact environment, release,
and deployment attempt before expiry. Before returning permit, it emits a
durable audit event containing the override identifier and digest, incident
reference, named approvers and roles, requester, session-window state and
reason, release checksum, environment, attempt, decision time, and outcome. It
does not record credentials or incident narrative. Audit failure is a refusal,
and every denied override attempt is audited when the audit service is
available. Reuse for another attempt or release requires a new evidence record.

This is the sole exception path for `ARC-OPS-013`; it remains fail-closed when
either the session source or approval evidence source is unavailable. The
override cannot bypass manifest validation, stage 12, a `CONTRACT` rollback
refusal, or any other release gate.

## P2.13 Contract Rollback Refusal

Every rollback request is evaluated against the validated manifest of the
release currently deployed, before a deployment controller is called. A
missing, unreadable, checksum-mismatched, or unknown manifest fails closed. If
its sole classification is `CONTRACT`, the policy returns non-zero with stable
code `CONTRACT_ROLLBACK_FORBIDDEN` and this actionable message:

```text
Release <release> is CONTRACT and cannot be rolled back (ARC-OPS-008).
Keep the current schema, halt rollout, reference an incident, and ship a new
reviewed forward-fix migration. Do not edit an applied migration or run schema
undo.
```

The refusal applies to automatic and manual requests, including emergency
overrides. It is emitted as structured deployment evidence and an audit event
with requester, release, manifest checksum, environment, and decision; it also
records the refusal in deployment evidence.

For `EXPAND` or `MIGRATE`, the policy can authorize only a code rollback to the
manifest's exact `previousImageDigest`, and only when the matching stage-12
N-1 compatibility evidence is green. No classification authorizes schema
rollback. This makes `ARC-OPS-008` executable rather than procedural and gives
operators the required forward-fix path without weakening `ARC-OPS-006`.

## P2.14 Migration Lock-Duration Report

Stage 12 emits canonical JSON plus a generated human-readable summary. The
JSON is the retained section 19.9 evidence and has this required structure:

```text
schemaVersion, runId, measuredAt, overallVerdict
release { id, classification, manifestChecksum, migrationSetChecksum }
toolchain { migrationVerifyVersion, parserVersion, postgresImageDigest }
dataset { seed, scale, generatorVersion, profileVersion, profileChecksum,
          bundleChecksum }
sampling { intervalMs, maximumObservedGapMs, complete }
thresholdSource { path, checksum }
statements[] {
  ordinal, migrationPath, statementChecksum, parsedShape, wallClockMs,
  locks[] {
    relation, lockMode, measuredHoldMs, acquisitionWaitMs,
    threshold { policy, warnMs, failMs }, verdict
  }
}
failures[] { code, migrationPath, statementOrdinal, relation, message }
```

One statement entry exists even when it acquires no relation lock. Lock rows
are grouped by exact `(statement, relation, lock mode)` and contain the maximum
continuous granted hold from `P2.5`. Blocking modes carry the approved numeric
thresholds: exam-critical `100ms` warning and `250ms` failure, or non-critical
`2000ms` failure. Allowed online-compatible
`ShareUpdateExclusiveLock` carries policy `ONLINE_COMPATIBLE`, explicit null
numeric bounds, and verdict `INFO`; it is never silently omitted. Other
verdicts are `PASS`, `WARN`, or `FAIL`, with conservative boundary comparison.

Entries are sorted by migration path, statement ordinal, relation, and lock
mode. The report never contains SQL literal values, credentials, generated
personal-looking values, or database connection strings. Any incomplete
sampling, unknown relation, missing threshold, analyser violation, statement
failure, or lock above its failure bound makes `overallVerdict` `FAIL`.

Reproduction requires only committed sources and retained immutable inputs.
The recorded seed, scale, generator and profile versions, profile and bundle
checksums, PostgreSQL image digest, migration checksum, parser/tool versions,
sampling interval, and threshold-source checksum identify them all. A reader
can regenerate the exact dataset bundle from `seed` and verify its checksum
before rerunning measurement. The summary links to the JSON by checksum rather
than becoming a second source of truth.

## P2.15 Migration Telemetry and Alert

The migration Job and stage-12 runner publish these Micrometer meters with
base units and a closed, low-cardinality label vocabulary:

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `migration_duration_seconds` | Histogram | `module`, `classification` | End-to-end duration of one module's migration run |
| `migration_lock_held_seconds` | Histogram | `module`, `relation`, `lock_mode` | Maximum continuous granted hold intervals, with explicit buckets at 0.1 s, 0.25 s, and 2 s |
| `migration_outcome_total` | Counter | `classification`, `outcome` | Terminal outcomes: `SUCCESS`, `STATIC_REFUSAL`, `LOCK_THRESHOLD_FAILED`, `EXECUTION_FAILED`, `COMPATIBILITY_FAILED`, or `CANCELLED` |
| `migration_forbidden_operation_total` | Counter | `classification`, `rejection_code` | Statements refused by the closed allowlist |
| `deploy_freeze_refusal_total` | Counter | `reason` | Refusals for `SESSION_OPEN`, `SOURCE_UNKNOWN`, `OVERRIDE_MISSING`, `OVERRIDE_INVALID`, or `EVIDENCE_UNAVAILABLE` |

`module`, `classification`, lock mode, outcome, rejection code, and refusal
reason are validated enums. `relation` is a normalized schema-qualified name
from the committed schema inventory. Release IDs, migration paths, statement
text, incident IDs, approver identities, exception messages, and run IDs are
never metric labels; those details belong in the report, audit record, log, or
trace. Timers stop and counters increment on all success, refusal, exception,
cancellation, and timeout paths. The short-lived Job performs a bounded OTLP
flush before exit and reports export failure in its retained execution record.

`MigrationBlockingLockThresholdBreached` fires on any increase of
`migration_outcome_total{outcome="LOCK_THRESHOLD_FAILED"}`. Severity is **P1**
because an equivalent production lock can stall answer acceptance and audit
writes. First action: keep the release blocked, confirm the session freeze,
inspect the section 19.9 relation/mode evidence and current `pg_locks`, then
prepare a forward fix; never roll schema back. The 100 ms exam-critical warning
creates a **P2** notification from report verdict `WARN` so Platform Ops can
intervene before the blocking limit. `FEAT-OBS-001` registers the meters and
`FEAT-OPS-004` owns the alert routing and dashboard presentation.

## P2.16 CI Stage 12 Contract

The canonical entry point is `./gradlew ciStage12`; executable
`ci/stage-12` invokes that task through the repository's common Gradle-stage
wrapper. CI calls the script, and developers call either form from the
repository root. Both use the same classes, configuration, containers,
timeouts, fail-closed checks, and output paths. There are no CI-only policy
branches, local skips, or pre-existing database assumptions.

Required inputs are the release manifest, repository migration tree,
volumetric profile, approved threshold file and signatures, pinned PostgreSQL
17 image digest, migration-verifier dependency locks, and registry access to
the previous image digest. Inputs may be supplied as explicit Gradle
properties or conventional committed paths; credentials remain external.
Missing, mutable, unapproved, or checksum-mismatched inputs fail preflight.

The stage performs these steps in order:

1. Verify approval evidence, manifest schema and checksums, all four script
   headers, one release classification, module ownership, transaction mode,
   and the closed parsed-statement allowlist.
2. Start a clean container from the approved immutable PostgreSQL digest,
   apply migrations not in the release manifest to establish the prior schema,
   generate and load the canonical production-shaped dataset, verify its
   constraints and checksums, and analyze its tables.
3. Apply only the manifest migration set with the production-equivalent Flyway
   configuration, per-statement timeout selection, and lock sampler active.
   Abort on the first execution, sampling, threshold, history, or checksum
   failure.
4. Prove that no invalid user index remains, run the retained N-1 image's
   read/write cases for every touched table, emit telemetry, and finalize the
   lock-duration and compatibility reports.
5. Write a checksummed stage summary and exit zero only when every required
   check is present and green. Always destroy the isolated containers and
   redact credentials from diagnostics.

Artifacts are written beneath `build/reports/migration-stage-12/` as the
verified manifest, analyser result, dataset manifest, lock-duration JSON and
summary, compatibility result, and overall stage summary. CI retains that
directory for Change advisory; local runs leave the identical files for
inspection. Missing N-1 evidence, an empty required touched-table case, or a
telemetry/report write failure is a blocking failure, never `SKIPPED`.

The delivery pipeline declares stage 12 after successful stage 11 and before
stage 13, with stage 13 depending on its success. The Gradle lifecycle exposes
`ciStage12` independently and orders aggregate CI execution the same way.
Implementation task `P3.12` owns that wiring, while `P3.7` supplies the session
timeouts consumed here. Stage 12 does not mutate application source, use a
shared developer database, or start an application workload other than the
isolated retained-image compatibility subject.
