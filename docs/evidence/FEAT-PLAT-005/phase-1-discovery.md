# FEAT-PLAT-005 Phase 1 Discovery Record

Date: 2026-09-14
Architecture baseline: `arch-v1.4`
Primary source: architecture v1.4 sections 9.8, 15.2, 17.6, 18.1,
18.2, 19.9, and 20.5 unless stated otherwise

This is the working artifact for migration tasks P1.1-P1.9. Publication of
its normative subsets is deferred to the Phase 10 tasks that own those files.

## P1.1 Expand/Migrate/Contract Phase Card

Architecture section 9.8 defines the model verbatim as follows:

```text
Release N   : EXPAND   add nullable column / new table / new index CONCURRENTLY. Old code unaffected.
Release N   : backfill in batches, throttled, resumable, off-peak.
Release N+1 : MIGRATE  new code writes both, reads new.
Release N+2 : CONTRACT drop old column / constraint, after N+1 is fully rolled out.
```

| Step | May contain | Must not contain |
|---|---|---|
| `EXPAND`, release N | A nullable column, a new table, or a new index created `CONCURRENTLY`; additions must leave old code unaffected | A rename or drop still read by N-1, an unsafe `NOT NULL`, a blocking change on an exam-critical table, a non-concurrent index build, or any contraction |
| Backfill, release N | Batched, throttled, resumable, off-peak data population through the `worker`-role harness | Table-level locking work, irreversible contraction, or a long-running operation in the migration Job |
| `MIGRATE`, release N+1 | New code that writes both representations and reads the new representation | Removal of the old representation while N or an N rollback target can still read it |
| `CONTRACT`, release N+2 | Removal of the old column or constraint only after N+1 is fully rolled out and observed | Combination with `EXPAND` or `MIGRATE` in the same release, or rollback of the schema change |

`CONTRACT` is always a separate release. It is never combined with an earlier
phase, even when the earlier release has completed successfully.

## P1.2 Forbidden-Operation Card

Architecture section 9.8 states verbatim:

> Forbidden in a single release: renaming or dropping a column still read by the previous version;
> adding a `NOT NULL` column without a default; a blocking `ALTER TABLE` on `answer`, `attempt` or
> `audit_event`; `CREATE INDEX` without `CONCURRENTLY`.

ADR-019 states the same decision verbatim:

> Forbidden in one release: renaming or dropping a column still read by the previous version,
> `NOT NULL` without a default, blocking `ALTER TABLE` on `answer`/`attempt`/`audit_event`,
> `CREATE INDEX` without `CONCURRENTLY`.

The enforceable, schema-qualified card is:

| Forbidden operation | Failure prevented |
|---|---|
| Rename or drop a column still read by the previous version | Breaks N-1 compatibility and makes code-only rollback unsafe |
| Add a `NOT NULL` column without a default | Existing rows or old-version writes cannot satisfy the new invariant |
| Run a blocking `ALTER TABLE` on `delivery.answer`, `delivery.attempt`, or `audit.audit_event` | A migration lock can stall answer acceptance or the required audit write |
| Run `CREATE INDEX` without `CONCURRENTLY` | A standard index build blocks writes to its table |

The schema-qualified names resolve `TASK-PLAT5-DEFECT-003`. They seed, but do
not close, the exam-critical registry: each owning feature must add any new
table placed on the exam-critical path. The permitted DDL shapes remain a
closed allowlist, so an unrecognized operation fails even when it is absent
from this explanatory list.

## P1.3 Release-Sequence Ownership Card

Architecture section 17.6 defines the sequence as follows:

```text
1  build + sign image, publish SBOM
2  run cbt-migrate Job (expand-phase only)              -> fail => abort, no pods replaced
3  canary: 1 cbt-api replica on the new image, 5% traffic
4  observe 10 min: exam-path p95, 5xx rate, feedback SLO burn, error-log rate
5  fail any gate => automatic rollback (scale canary to 0, restore previous ReplicaSet)
6  pass => rolling update of cbt-api (maxUnavailable 0, maxSurge 25%)
7  rolling update of cbt-worker (drain-aware: stop prefetch, finish in-flight)
8  post-deploy verification: exam-path smoke, /health/exam-readiness, ARC-VERIFY smoke subset
9  contract-phase migration only in a LATER release, after N+1 is fully rolled out
```

| Step | FEAT-PLAT-005 relationship | Delivery owner |
|---|---|---|
| 1 | None beyond consuming the signed release manifest | `FEAT-OPS-007` |
| 2 | **Owns** migration classification, execution preconditions, and migration success/failure contract | `FEAT-PLAT-005` |
| 3 | None | `FEAT-OPS-007` |
| 4 | None | `FEAT-OPS-007` |
| 5 | **Gates** rollback safety by proving N-1 against N's schema | `FEAT-OPS-007` executes rollback |
| 6 | **Gates** rollout by requiring successful stage 12 migration verification | `FEAT-OPS-007` executes rollout |
| 7 | None; drain behavior belongs to the runtime-role feature | `FEAT-OPS-007` orchestrates |
| 8 | **Gates** the migration and compatibility portions of post-deploy verification | `FEAT-OPS-007` executes verification |
| 9 | **Owns** the separate `CONTRACT` classification and never-rollback rule | `FEAT-PLAT-005` |

Steps 1, 3, 4, 5, 6, 7, and 8 remain deployment/canary orchestration owned by
`FEAT-OPS-007`; this feature contributes gates at steps 5, 6, and 8 without
assuming that orchestration. The phrase "expand-phase only" in step 2 conflicts
with the later `CONTRACT` release in step 9; `TASK-PLAT5-DEFECT-005` records the
resolution as one declared classification per release.

## P1.4 Rollback-Dependency Card

The six rollback triggers, actions, and bounds from architecture section 18.2
are transcribed below. The final column marks this feature's dependency.

| Trigger | Action | Bound | FEAT-PLAT-005 dependency |
|---|---|---|---|
| Canary gate breach | Automatic: canary to 0, previous ReplicaSet restored | <2 min | None specific |
| Post-deploy verification failure | Automatic rollback | <5 min | None specific |
| Defect found after full rollout | Manual `kubectl rollout undo`; safe because *N-1* runs against *N*'s schema (`ARC-OPS-006`) | <10 min | **Safety depends on the N-1-against-N compatibility gate** |
| Bad expand migration (additive but harmful, e.g. a slow index) | Forward fix, not rollback - drop the index concurrently | Immediate | **Safety depends on additive `EXPAND` classification and concurrent index handling** |
| Data corruption | PITR to just before the event, coordinated with a communications plan; **never** an ad-hoc `UPDATE` | Per section 9.6 RTO | None specific |
| Configuration error | Config-only rollback with a restart; no image change | <5 min | None specific |

The migration-dependent rows do not authorize schema rollback. The first uses
code-only rollback because the previous image remains compatible with the new
schema. The second repairs an additive migration by a forward operation; it
never reverses migration history under load.

## P1.5 Operations Rule Card

| Rule | Verbatim rule | Mechanical enforcement | Owning tasks |
|---|---|---|---|
| `ARC-OPS-006` | "rollback is code-only, never schema." | Resolve the previous release's retained image digest from the release manifest, run it against N's migrated schema, and fail stage 12 unless its touched-table read/write suite passes | Design `P2.10`; runner `P4.11`; compatibility test `P7.12`; rollback rehearsal `P7.17` |
| `ARC-OPS-008` | "a contract-phase migration is never rolled back." | Require one machine-readable release classification; reject mixed `EXPAND`/`CONTRACT` manifests and refuse rollback when the manifest declares `CONTRACT`, returning a forward-fix instruction | Design `P2.13`; classification check `P4.6`; refusal `P4.15`; tests `P7.5` and `P7.16` |
| `ARC-OPS-013` | "a deploy or upgrade is blocked automatically while any exam session is open, unless it is an emergency fix with an incident record." | Query the three-state `SessionWindowQuery` port and refuse both `OPEN` and `UNKNOWN`; an override requires an incident reference, two named approvals, and an audit record | Port/override designs `P2.11`-`P2.12`; checks `P4.12`-`P4.14`; tests `P7.14`-`P7.15` |

Each rule has an executable refusal or compatibility proof. None relies on a
release author remembering the convention.

## P1.6 Verification-Ownership Table

| CI stage or artifact | FEAT-PLAT-005 responsibility | Ownership relationship | Implementing tasks |
|---|---|---|---|
| Stage 12, Migration verification | Apply migrations to the production-shaped dataset; measure and threshold lock holds; reject forbidden DDL; run N-1-against-N compatibility | **Owned**; blocking before stage 13 | `P3.12`-`P3.13`, `P7.11`-`P7.13`, `P7.20` |
| Stage 19, Production approval | Supply a fail-closed session-window precondition and auditable emergency-override decision to the human approval gate | **Contributed**; `FEAT-OPS-007` owns the gate | `P4.12`-`P4.14`, `P7.14`-`P7.15` |
| Stage 20, Deploy to production | Return a non-zero refusal for `OPEN` or `UNKNOWN` before the section 17.6 deployment sequence can start | **Contributed**; `FEAT-OPS-007` owns deployment | `P4.12`-`P4.14`, `P7.14`-`P7.15` |
| Section 19.9 Migration lock-duration report | Retain the per-release report produced by stage 12 for Change advisory | **Owned artifact** | Schema `P2.14`, emitter `P4.16`, production-shaped run `P7.11`, registration `P7.19` |

No new `ARC-VERIFY` identifier is created. `TASK-PLAT5-DEFECT-002` remains
recorded because architecture section 19.9 names the Migration lock-duration
report, and stage 12 produces it, but the ratified architecture assigns no
`ARC-VERIFY-###` identifier to migration verification. The gap is an input to
the next architecture baseline; it does not weaken or rename the blocking gate.

## P1.7 Volumetric Input Table

This table is the source of truth for the declarative synthetic-data profile.
The values are scenario inputs from architecture section 15.2, not production
records or a production-derived sample.

| Input | Profile value | Generator/load interpretation |
|---|---:|---|
| Candidates in one session | 5,000 | Generate a full session at the architecture's entry and close burst size |
| Concurrent candidates platform-wide | 50,000 | Generate enough sessions and tenants to represent the platform-wide concurrency target |
| Questions presented per candidate | ~60 | Populate the question, presentation, and evidence cardinalities used by exam reads |
| Answer saves per candidate | ~80, including revisions | Preserve repeated saves/revisions rather than assuming one write per question |
| Requests per candidate over 60 minutes | ~274 | 72 navigation + 80 answer saves + 120 timer/state refreshes + 2 entry/submission requests |
| Platform steady-state request rate | ~3,800 requests/s | `50,000 * 274 / 3,600`; used to drive concurrent activity while migrations run |
| Session-close burst | 5,000 submissions in ~120 seconds | Drive about 40 submissions/s plus the auto-submit sweep during lock measurement |

The future per-table profile derives row counts from these inputs, uses a fixed
seed, and records zero for tables not yet implemented. It must not substitute
an empty schema for the concurrency and revision shapes listed here.

## P1.8 Migration-Job Envelope Input

| Input | Required value | Scope | Consumers |
|---|---:|---|---|
| `migration_job` | 10 connections | Transient allocation present during every release migration; included at baseline and at simultaneous HPA ceilings | `FEAT-OPS-004`, `FEAT-OPS-005`, CI stage 4a, and `ARC-VERIFY-033` |

Using the section 15.2 definitions, the term changes `allocated_with_headroom`
to `committed`:

| Envelope point | `allocated` | `allocated_with_headroom` | `migration_job` | `committed` | `headroom_remaining` |
|---|---:|---:|---:|---:|---:|
| Baseline | 268 | 308 | 10 | 318 | 432 |
| Simultaneous HPA ceilings | 700 | 740 | 10 | 750 | 0 |

The ceiling includes 40 funded headroom connections before the migration Job;
the final zero is surplus beyond the fully funded envelope, not an omission of
reserved headroom.

Architecture condition `A8` requires `ARC-VERIFY-033` to drive `api` to 30 and
`worker` to 24 simultaneously **with the migration Job running**, while proving
zero connection-acquire failures, no starvation of the exam-reserved pool,
exactly-one scheduler singletons, and a green `ARC-PERF-006` evaluation against
the deployed manifest. A test that omits the 10-connection Job does not
discharge `A8`.

`P3.9` must publish this exact input in the Job/pool configuration, and `P8.6`
must hand it to the envelope and `A8` evidence owned by `FEAT-OPS-004` and
`FEAT-OPS-005`.

## P1.9 Invalid-Index Hazard Card

PostgreSQL concurrent index operations span multiple transactions. That is why
they preserve writes, and also why an error cannot atomically remove every
intermediate catalog object.

| Operation | How an invalid index can remain |
|---|---|
| `CREATE INDEX CONCURRENTLY` | PostgreSQL registers the index as invalid before its scans. A deadlock, uniqueness violation, expression/predicate evaluation error, cancellation, backend termination, or process interruption before validation can leave that index behind with `indisvalid = false`. |
| `REINDEX ... CONCURRENTLY` | PostgreSQL builds a transient replacement through multiple transactions. Failure can leave an invalid `_ccnew` replacement; a completed rebuild whose old copy could not be dropped can leave an invalid `_ccold` index. |

The gate detects invalid user indexes from the catalogs rather than parsing
`psql` display text:

```sql
SELECT index_namespace.nspname AS index_schema,
       index_relation.relname AS index_name,
       table_namespace.nspname AS table_schema,
       table_relation.relname AS table_name,
       index_state.indisready,
       index_state.indisvalid
FROM pg_catalog.pg_index AS index_state
JOIN pg_catalog.pg_class AS index_relation
  ON index_relation.oid = index_state.indexrelid
JOIN pg_catalog.pg_namespace AS index_namespace
  ON index_namespace.oid = index_relation.relnamespace
JOIN pg_catalog.pg_class AS table_relation
  ON table_relation.oid = index_state.indrelid
JOIN pg_catalog.pg_namespace AS table_namespace
  ON table_namespace.oid = table_relation.relnamespace
WHERE NOT index_state.indisvalid
  AND index_namespace.nspname NOT IN ('pg_catalog', 'pg_toast');
```

An invalid index is unsafe for queries, still consumes storage and update
overhead, and a failed unique build may continue enforcing uniqueness. Leaving
it in place also makes a same-name retry fail, while `IF NOT EXISTS` could
silently accept the wrong object. The recovery order is therefore:

1. Stop and report the schema-qualified invalid index.
2. Run `DROP INDEX CONCURRENTLY IF EXISTS <schema>.<index>` outside a transaction.
3. Verify that the catalog query returns no matching invalid index.
4. Repair the failed non-transactional Flyway history entry as designed in
   `P2.7`, then retry `CREATE INDEX CONCURRENTLY`.

`CONCURRENTLY` is mandatory on the drop because a regular `DROP INDEX` takes
an `ACCESS EXCLUSIVE` lock on the parent table and can block reads and writes,
recreating the availability hazard this feature exists to prevent. The
reconciliation must also handle `_ccnew` and `_ccold` artifacts before a
concurrent reindex retry.

PostgreSQL 17 references: [CREATE INDEX](https://www.postgresql.org/docs/17/sql-createindex.html),
[REINDEX](https://www.postgresql.org/docs/17/sql-reindex.html),
[`pg_index`](https://www.postgresql.org/docs/17/catalog-pg-index.html), and
[DROP INDEX](https://www.postgresql.org/docs/17/sql-dropindex.html).
