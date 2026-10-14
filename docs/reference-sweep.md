# Test-Only Reference Sweep

Canonical P4.26 exemplar for the later P10.1 sweep-author guide:

- [Sweep body](../src/integrationTest/java/org/meldtech/platform/runtimefixture/ReferenceSweep.java)
- [PostgreSQL lifecycle tests](../src/integrationTest/java/org/meldtech/platform/platform/infra/runtime/ReferenceSweepIntegrationTest.java)
- [Secured pool wiring](../src/integrationTest/java/org/meldtech/platform/platform/infra/persistence/ReferenceSweepPools.java)

The body depends on `platform.api` and shared actor contracts, not runtime
implementation classes. It declares the worker role and bounded metadata but
has no component annotation. The integration test explicitly registers it and
adds a test-only worker manifest entry. No deployed profile, production manifest,
registry migration or application artifact includes this sweep.

## Batch Contract

One subscription processes at most one row in one `SweepTransactions` platform
transaction. The runner installs the declared system actor; the transaction
adapter installs the module role and platform scope before business SQL. The
fixture reuses the approved delivery auto-submit identity without adding a new
actor. Its RLS-protected table and enabled registry row exist only for each test.
They are not an implementation of delivery auto-submit behavior.

The body checks the stop signal before opening a transaction, persists progress
in the work table and returns whether another bounded batch should be attempted.
It owns neither an interval source nor an advisory-lock call. There is no
in-memory cursor. Reprocessing after rollback selects the still-pending work.
The `beforeCommit` hook exists only to control interruption in tests; it pauses
inside the transaction and is not a production extension point.

## Executable Evidence

The tests use the actual registry loader, runner, session lock adapter, worker
login, security wrappers and dedicated worker/coordination pools against the
shared PostgreSQL Testcontainer. Only scheduling time is virtual.

1. During graceful drain, an in-flight batch commits exactly one row and no new
   batch starts. A competing connection cannot acquire the lock until the final
   lock-release phase, after which all acquired connections are returned.
2. When a batch exceeds its deadline during drain, its update rolls back and
   cleanup completes before lock release. A fresh runner completes both pending
   rows from durable state, and the failed tick records `TIMEOUT`.
3. Direct invocation without the runner's security context fails; a stop request
   does no work; removing the registry row fails bootstrap validation.
4. The reference class is absent from the production output and all committed
   role manifests. Its explicitly overlaid test manifest passes the real verifier.

Run with Docker available:

```sh
./gradlew integrationTest --tests '*ReferenceSweepIntegrationTest'
```

P10.1 remains a separate documentation task. Its author guide should link to
this executable exemplar and explain feature-owned registry, manifest, identity
and bounded-batch declarations rather than copy the fixture's test SQL or hooks.
