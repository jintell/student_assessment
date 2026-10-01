# Phase 6 Security and Hardening Evidence

## P6.6 - Security-Signed Outbox Role Review

Status: PASS (2026-10-01).

Francis Okechukwu signed the approved `DOR-FEAT-PLAT-004-P0.5` role-amendment
record. Its detached signature validates to fingerprint
`FAD9BBAEE325B94064A7B30BE12A4FD9D0FC2124`, the fingerprint registered for
the Security approver. The signed denial matrix excludes relay insert/delete
and business-schema access, all maintenance row reads and arbitrary DDL, and
membership from `app_api` and `app_pindist`.

`ci/verify-outbox-role-review` verifies the existing signature in an isolated
GPG home and then compares the current grant matrix with that signed baseline.
The relay has only `USAGE` on `outbox` plus `SELECT`/`UPDATE` on
`outbox.outbox_event`. Maintenance has only schema `USAGE` and `EXECUTE` on
the two guarded partition routines. `app_worker` is their only member.

Evidence command:

```bash
./gradlew verifyOutboxRoleReview
```

## P6.7 - Request and Isolated Role Separation

Status: PASS (2026-10-01).

`OutboxRoleIsolationTest` starts application contexts under both `api` and
`pindist`. Neither context contains the broker topology, broker credential or
TLS capability, publisher/store/claim components, or relay execution types.
This proves the profile boundary at bean-instantiation time.

`PersistenceSecurityGatesIntegrationTest.requestAndPinDistributionRolesCannotAssumeTheOutboxRelayRole`
queries PostgreSQL's effective membership function and catalogue. Neither
`app_api` nor `app_pindist` can assume `app_outbox_relay`; `app_worker` is its
sole member.

Evidence commands:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.outbox.OutboxRoleIsolationTest'
./gradlew integrationTest --tests \
  'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.requestAndPinDistributionRolesCannotAssumeTheOutboxRelayRole'
```

## P6.8 - Relay Error Hygiene

Status: PASS (2026-10-01).

The persistence boundary accepts `PublicationFailureReason`, not a throwable or
free-text message, and binds only the enum name into `last_error`. The original
row retains its separate correlation identifier. Relay failure logs contain
only the stable event id, numeric attempt, correlation id, and classified
reason.

`RelayPublishStepTest.brokerDiagnosticsCannotReachPersistenceOrLogs` supplies
a broker exception containing password-, driver-, payload-, and
credential-shaped text. The persisted category, terminal alert, and captured
log contain only allowlisted values and none of the hostile source text.

Evidence command:

```bash
./gradlew test --tests \
  'org.meldtech.platform.platform.infra.outbox.RelayPublishStepTest.brokerDiagnosticsCannotReachPersistenceOrLogs'
```

## P6.9 - History-Aware Secret Scan

Status: PASS (2026-10-01).

The pinned Gitleaks 8.30.1 gate scanned all 123 reachable commits and then the
complete working tree, including untracked files. Both scopes reported no
leaks. This covers source, migrations, registered event contracts, test
fixtures, broker templates, deployment templates, and the Phase 6 evidence.

Broker connection metadata remains externally supplied and both broker
credentials resolve only from the mandatory worker config-tree mount. No
credential, broker URI containing credentials, token, or secret value is
committed.

Evidence command:

```bash
./gradlew secretScan
```
