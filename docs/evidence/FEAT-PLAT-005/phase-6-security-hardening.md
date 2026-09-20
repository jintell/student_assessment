# Phase 6 Security and Hardening Evidence

## P6.1 - DDL Privilege Boundary

Status: PASS (2026-09-19)

`app_migrator` is the only application role with `CREATEROLE`, the owner of all
fifteen application schemas, and the only identity accepted by the
`--migrate-only` entrypoint. It is `NOINHERIT`, `NOSUPERUSER`, `NOCREATEDB`,
`NOREPLICATION`, and `NOBYPASSRLS`. The serving login roles `app_api`,
`app_worker`, and `app_pindist` have no direct object grants; their `api`,
`worker`, and `pindist` profiles select only the matching login and expose no
migrator credential or role-switch configuration.

The control is enforced at three layers:

| Layer | Evidence |
|---|---|
| Canonical authorization model | `src/main/resources/db/grants/grant-matrix.json` declares exactly one DDL-capable owner, `app_migrator` |
| Entrypoint boundary | `MigrationApplication` rejects every migration username except `app_migrator`; ordinary application startup does not run Flyway |
| Live PostgreSQL catalogue | `PersistenceSecurityGatesIntegrationTest.liveGrantsExactlyMatchTheCanonicalMatrix` fails on any unexpected role attribute, membership, ownership, or grant |

This extends the `FEAT-PLAT-002` P6.8 grant review for `ARC-PLAT-007` and leaves
cluster-owner bootstrap outside the application trust boundary.

## P6.2 - Migration Workload Identity

Status: PASS (2026-09-19)

The migration Job uses the dedicated `cbt-platform-migration` Kubernetes
service account declared by
`deploy/kubernetes/migration-service-account.yaml.template`. Its external
workload-identity binding is supplied at deployment time through
`CBT_MIGRATION_WORKLOAD_IDENTITY`; it is not any of the `api`, `worker`, or
`pindist` identities. The service account has no repository-defined Role or
ClusterRole binding, and both the service account and Job pod disable automatic
Kubernetes API token mounting.

The Job receives only the `app_migrator` database identity and its one password
file. Its database authority is the exact canonical grant matrix reviewed in
P6.1. It receives none of the serving workload credentials, while serving
profiles receive no migrator credential. Adding either a Kubernetes binding or
a database grant is therefore a reviewed manifest or grant-matrix change and
is covered by the existing workflow and live grant-drift gates.

## P6.3 - Migrator Credential Handling

Status: PASS (2026-09-19)

The committed Job template contains no migrator password. It mounts only the
`app-migrator-password` key from the externally populated
`cbt-platform-database` Secret into the read-only config-tree path. Spring
resolves `cbt.database.roles.app-migrator.password` from that path, while the
JDBC URL is also a `secretKeyRef`; neither value is supplied as a command-line
argument, literal environment value, manifest substitution, or Gradle input.
The non-secret username remains the literal `app_migrator`.

The trusted CI workflow does not request the migrator credential and all
checkout credentials are non-persistent. `ci/secret-scan` uses Gitleaks with
redacted JSON output and scans both all reachable Git history and the current
working tree. On 2026-09-19, `./gradlew --no-daemon --console=plain secretScan`
scanned 68 commits and the working tree and reported no leaks. Generated
reports are retained only below `build/reports/secret-scan` and are not release
inputs.

## P6.4 - Synthetic Dataset Provenance

Status: PASS (2026-09-19)

The dedicated
`docs/evidence/FEAT-PLAT-005/P6.4-dataset-provenance-attestation.md` binds the
signed P0.5 synthetic-only approval to the implemented generator and volumetric
profile. The generator accepts no database or network input and emits only
deterministic synthetic values. No production extract or personal data is
present or reachable from its command path.

## P6.5 - Pipeline-Only Invocation

Status: PASS (2026-09-19)

`migration-invocation-policy.yaml.template` installs a fail-closed Kubernetes
`ValidatingAdmissionPolicy`. It selects a Job when it uses the dedicated
migration service account or invokes the `--migrate-only` entrypoint, so omitting
the component label cannot bypass the policy. The admission request must
originate from the configured release-pipeline principal, use the dedicated
service account, and carry the canonical migration label. A direct operator,
application workload, or other controller therefore cannot create the Job.

Every admitted Job must carry non-empty pipeline run and invoking-actor
annotations, an `automatic` or `manual` pipeline trigger, and the validated
release-manifest checksum. The policy applies both `Deny` and `Audit` actions;
Kubernetes records the authenticated creating principal and the retained Job
records the initiating pipeline actor. A manual action is consequently a
manual pipeline run with attributable identity, never a direct Job launch.

## P6.6 - Two-Person Emergency Override

Status: PASS (2026-09-19)

`EmergencyOverridePolicy` accepts exactly two approvals: one Engineering Lead
and one Platform Ops approver, with two distinct named people, neither of whom
may be the requester. Both signatures must bind to the same integrity-verified
evidence digest and active incident. Missing, expired, mismatched, or
unavailable evidence fails closed.

Before a permit is returned, `EmergencyOverrideAuditTrail` must durably record
the incident reference, sorted approver identities, requester, release
checksum, environment, deployment attempt, decision time, session state, and
outcome; an audit failure converts the result to `AUDIT_FAILED`. The unit test
suite proves both the successful audited path and refusal when one individual
attempts to occupy both approval roles.

## P6.7 - DML Is Forbidden in Migration Scripts

Status: PASS (2026-09-19)

`DataModificationCheck` is part of the standalone migration analyser's closed
policy. JSqlParser classifies `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`,
and supported upsert statements as `DATA_MODIFICATION`; PostgreSQL `COPY`, which
the parser does not model, is rejected explicitly before parsing. Every case
fails with stable code `DATA_MODIFICATION_FORBIDDEN` and directs authors to the
reviewed, resumable backfill harness.

The `analyseReleaseMigrations` Gradle task resolves every migration path from
the release-manifest specification and applies the analyser. It is a dependency
of `:migration-verify:check`, which stage 12 already runs, so a row-modifying
migration blocks the release. Backfill code remains outside Flyway and runs
through the worker-role harness. The focused negative suite proves insert,
update, delete, merge, truncate, and copy are refused; the current declared
migration set passes the executable gate.

## P6.8 - ARC-RISK-017 Review

Status: COMPLETE, CONDITIONALLY CONFORMANT (2026-09-19)

`docs/evidence/FEAT-PLAT-005/P6.8-arc-risk-017-conformance.md` maps all four
section 22.2 mitigation limbs to concrete controls. It also preserves the
remaining Phase 7 live-test obligations and `FEAT-OPS-007` deployment wiring as
explicit conditions, so this security review does not overstate downstream
release evidence.
