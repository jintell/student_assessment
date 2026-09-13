# Phase 6 Security and Hardening Evidence

## P6.1 - Application-Layer Isolation Is Insufficient Alone

`PersistenceFoundationIntegrationTest.missingTenantPredicateLeaksForeignRowsWhenRlsIsDisabled`
runs only against the repository's ephemeral PostgreSQL 17 Testcontainer. Inside a rollback-only
transaction it inserts rows for two tenants, disables RLS on the conformance probe, assumes
`app_migrator`, and demonstrates that an unfiltered query sees both rows. This is the failure mode
prevented at the application layer by the R5 tenant-parameter signature rule.

The transaction is always rolled back in a `finally` block. A post-rollback catalogue assertion proves
that both `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` remain active on the probe table.

## P6.2 - Forced RLS Is the Database Backstop

`PersistenceFoundationIntegrationTest.forcedRlsHidesForeignRowWhenTenantPredicateIsOmitted` installs
tenant A's transaction-local context and queries directly for tenant B's probe identifier without a
`tenant_id` predicate. PostgreSQL returns a count of zero. The test exercises the real forced policy and
therefore supplies the `ARC-VERIFY-005` backstop evidence independently of the R5 signature rule.

## P6.3 - Blocked by Authorization Evaluator

Status: blocked; task remains open.

`FEAT-IAM-003` has not delivered the object-level tenant evaluator or a tenant-owned HTTP resource to this
repository. The current conformance reference `Policy` intentionally returns `DENY`, and its endpoint test
correctly asserts `403`. That surface cannot prove the required cross-tenant `404` status and response body.
Implementing a substitute evaluator in this persistence feature would cross the ownership boundary named by
P6.3, so execution stops here pending `FEAT-IAM-003`.
