# Per-Request Query Budget Guide

Query budgets bound database round trips on request paths. They are correctness
and capacity contracts, not averages: a single execution above its registered
maximum is a regression even when aggregate latency remains acceptable.

## Approved Budget Classes

Architecture section 15.2 defines these acceptance baselines:

| Route class | Maximum statements | Rationale |
|---|---:|---|
| Timer/state refresh | 1 | One indexed attempt read. |
| Question navigation | 1-2 | Presentation state plus question text from attempt tables. |
| Answer save | 6 | Lifecycle check, locked attempt, operation insert, answer upsert, audit insert, and audit chain-head upsert in one transaction. |
| Authorized workforce request | 2 + slice | Fixed PostgreSQL user-lifecycle and membership reads plus the owning slice's declared budget; effective permissions come from Redis. |
| Exam entry | Approximately 10 | Approved planning envelope for the heaviest request path, kept in one transaction. Each concrete route still needs an exact registered maximum. |

`config/observability/query-budgets.json` is the executable route catalogue.
Its current Phase 0 entry is `platform.getConformanceReference`: fixed
workforce overhead 2, slice budget 1, and `maxQueries` 3. Each new request
route must add one unique entry with its compiled `ROUTE_ID`, route type,
class, fixed overhead, slice budget, and exact
`maxQueries = fixedOverhead + sliceBudget`.

Do not register a range or an approximate maximum. For navigation choose the
exact 1 or 2 supported by that route. For exam entry, derive and review the
exact route figure rather than copying the planning value blindly.

## How `dbQueryCount` Is Measured

`RequestTelemetry.observe(...)` creates a request-local `RequestQueryContext`
in Reactor `Context`. The instrumented R2DBC `ConnectionFactory` increments
its atomic counter each time a statement publisher is executed. Counting
happens before delegating `Statement.execute()`, so attempted statements that
error or are cancelled still count.

The counter covers the whole observed reactive slice, including policy work,
and is emitted as non-negative `dbQueryCount` on request completion for
success, error, timeout, and cancellation. It is independent of trace
sampling. The count contains no SQL, parameters, connection identity, actor,
tenant, or correlation value.

`db_query_duration{slice,operation}` records each executed statement's elapsed
time with bounded labels. Use it with the count to distinguish too many round
trips from individually slow statements. Neither metric replaces the request
latency SLI.

## Authoring and Testing a Budget

1. Count the expected statements for every branch, including authorization,
   idempotency, audit, and failure paths.
2. Assign fixed platform overhead separately from the slice-owned statements.
3. Add the exact route entry to the JSON catalogue.
4. Exercise the maximum legitimate path against PostgreSQL and assert the
   observed count with `QueryBudgetGate.verifyQueryCount` through the owning
   test fixture.
5. Add a negative regression test that introduces one additional execution
   and proves the assertion fails.

Run the catalogue check with:

```bash
./gradlew queryBudgetGate
```

The gate verifies non-empty unique routes, compiled `ROUTE_ID` equality,
non-negative components, and coherent totals. It is blocking in CI stage 8.

## When a Route Exceeds Its Budget

Treat the failure as a design regression. Inspect the protected request log by
route, slice, and correlation identifier, then use `db_query_duration` and
database tracing to locate duplicate loads, N+1 access, retry, or newly added
policy/audit work. Do not log SQL or parameters while diagnosing.

Prefer eliminating redundant work, batching, using the existing transaction
state, or moving non-atomic work to an approved asynchronous boundary. Keep
authorization, tenant isolation, audit, durability, and transaction
correctness intact; a budget is never a reason to weaken a control.

If the extra statement is irreducible and required, obtain architecture and
performance approval, update the route budget and retained rationale, and
adjust capacity evidence before merging. Never silently raise a budget, omit
the route, exclude a failure branch, or make query counting depend on trace
sampling.
