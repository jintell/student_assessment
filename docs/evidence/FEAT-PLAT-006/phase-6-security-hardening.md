# Phase 6 Security and Hardening Execution Record

Date: 2026-10-15. Requested range: `P6.1-P6.10`, strict sequential execution.

## P6.1 — Blocked: Provider-Backed Workload Federation

The P0.8 request was acknowledged, but its signed record at
`ci/provisioning/FEAT-PLAT-006/P0.8-platform-ops-provisioning-request.json`
still reports `fulfillmentStatus: NOT_PROVISIONED`. Completion of that request
task proves ownership of the provisioning work, not completed federation.

Read-only inspection of the available `cbt-staging` Kubernetes context found:

- Node `desktop-control-plane` has provider ID
  `kind://docker/desktop/desktop-control-plane`.
- No ServiceAccount named `cbt-api`, `cbt-platform-worker`, or `cbt-pindist`
  exists in any namespace. The worker workload is called `cbt-worker`, but
  its ServiceAccount is `cbt-platform-worker` in the deployment values.
- Application deployments are limited to `cbt-platform` and
  `cbt-platform-postgres` in `cbt-staging-rollback`; neither declares a
  workload-specific ServiceAccount. Other deployments are cluster support.

Reproduction commands (no credential or Secret reads):

```sh
kubectl --context cbt-staging --request-timeout=15s get nodes \
  -o custom-columns=NAME:.metadata.name,PROVIDER_ID:.spec.providerID
kubectl --context cbt-staging --request-timeout=15s get serviceaccounts \
  --all-namespaces -o custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name
kubectl --context cbt-staging --request-timeout=15s get deployments \
  --all-namespaces \
  -o custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name,SERVICE_ACCOUNT:.spec.template.spec.serviceAccountName
```

The existing `deploy/kubernetes/pindist-service-account.yaml.template` contains
an inventory annotation referencing `CBT_PINDIST_WORKLOAD_IDENTITY`.
`docs/runtime-deployment-values.md` explicitly requires Platform Ops to supply
the provider binding, issuer, audience and projected token configuration.
Applying the template alone would not satisfy P6.1 or `REQ-SEC-014`.

## Resumption Requirements

Platform Ops must supply the target cluster and namespace, cloud provider and
account/project, the three distinct provider principal identifiers, and the
supported federation issuer/audience and binding configuration with access to
apply and verify it. Retain provider-backed evidence that the `cbt-pindist`
ServiceAccount maps to its own principal, distinct from API and worker.
No cloud provider, principal, or binding is inferred from the local context.

The retrieval KEK grant, denial attestation `ARC-VERIFY-025`, and condition A4
remain owned by `FEAT-EXAM-002`. No fulfillment or security attestation is
claimed, and no signed request was modified.

## P6.1 Closure Checklist

Reinspection on 2026-10-15 confirmed the same local cluster, missing runtime
ServiceAccounts and application deployments without workload-specific accounts.
The P0.8 request verifier passed; that verifies the signed request, not its
fulfillment. P6.1 remains blocked, not completed by this record.

Platform Ops must provide a separate, dated fulfillment record containing:

1. The approved provider/account or project, target cluster and namespace.
2. The live mappings for `cbt-api`, `cbt-platform-worker` and `cbt-pindist` to
   their three distinct provider principal IDs, with deployed workload references.
3. The provider trust binding for exactly
   `system:serviceaccount:<namespace>:cbt-pindist`, its issuer and audience,
   and the applied provider-specific token/identity configuration. API, worker
   and wildcard subjects must not be able to assume that principal.
4. Redacted provider identity lookup/token-exchange results from each workload
   showing the expected caller principal, plus negative federation checks for
   API and worker attempting the pindist identity. Do not retain tokens or secrets.
5. The Platform Ops owner, verification time and immutable evidence references.

Only after those checks pass should P6.1 be marked complete. Preserve the signed
P0.8 request as historical evidence; do not rewrite it to claim fulfillment.
These checks establish workload identity isolation, not the separate retrieval
KEK authorization or A4 attestation.

## P6.2 — PIN-Retrieval KEK Policy Precondition

The handoff record at
`docs/evidence/FEAT-PLAT-006/P6.2-pin-retrieval-kek-policy-precondition.md`
requires the dedicated retrieval KEK to grant `Encrypt` and `Decrypt` only to
the exact provider principal federated from the `cbt-pindist` ServiceAccount.
It requires explicit denial of both operations to the `cbt-api` and
`cbt-platform-worker` principals, prohibits wildcard identity grants and keeps
key administration separate from cryptographic use.

The record assigns policy implementation, the real-identity denial/permitted
attempts, `ARC-VERIFY-025` and condition A4 to `FEAT-EXAM-002`. It explicitly
does not claim a key or grant exists, that P6.1 is fulfilled, or that A4 and
`REQ-SEC-014` are discharged.

## Initial Execution Summary

- Completed tasks: none in the requested range.
- Blocked task: P6.1, missing real workload federation inputs and provisioning.
- Remaining tasks: P6.2-P6.10, not entered because execution is sequential.
- Changes: this evidence record and the Phase 6 blocker note in the task list.
- Risk: treating a local ServiceAccount or inventory annotation as real cloud
  federation would falsely claim the required identity isolation.
- Recommended next chunk: resume P6.1-P6.10 after provisioning inputs exist.
- Verification: `./gradlew compileJava compileTestJava test --console=plain`
  passed with up-to-date outputs. `./gradlew test --rerun-tasks --console=plain`
  then rebuilt production/test code and passed both the application and
  `migration-verify` test suites (10 tasks executed, 1m 12s). No failing test
  required a fix. `git diff --check` passed. Dedicated integration/conformance
  suites and a full build were not run for this documentation-only blocker
  record. No cloud, live network-denial, or ingress proof is claimed.

## P6.3 — Blocked: Live Network Denial Proof

Read-only inspection of the available `cbt-staging` context on 2026-10-15
found no `NetworkPolicy` in any namespace. It also found no deployed
`cbt-api`, `cbt-worker` or `cbt-pindist` pod and no corresponding role-specific
ServiceAccount. The only application pods are `cbt-platform` and PostgreSQL in
`cbt-staging-rollback`; both use the namespace's `default` ServiceAccount.

The checked-in policy template correctly allows TCP 8080 only from the selected
ingress-controller namespace and pod labels. Template inspection is not the
P6.3 acceptance evidence: the task requires connection attempts from API and
worker identities to fail at the network layer in the target cluster.

Resume P6.3 only after Platform Ops supplies a target cluster with an enforcing
CNI, deploys the three role-specific workloads and ServiceAccounts, renders and
applies the policy with controlled ingress selectors, and confirms no additive
policy broadens access. Run attempts from API and worker pods to the pindist pod
IP or cluster-internal Service on TCP 8080. Retain timeout or network-denial
results separately from any HTTP response; an HTTP `401`, `403` or `404` proves
application behavior and does not satisfy this task.

## Execution Summary for P6.2–P6.10

- Completed task: P6.2; the key-policy precondition and ownership boundary are
  recorded without claiming a live grant or discharging A4.
- Blocked task: P6.3; target workloads, ServiceAccounts and NetworkPolicy are
  absent from the accessible cluster.
- Remaining tasks: P6.3-P6.10. P6.4-P6.10 were not entered under strict
  sequential execution.
- Verification: the P6.2 document contains the required exclusive allow,
  explicit API/worker denies and FEAT-EXAM-002 ownership statements;
  `git diff --check` passed before live cluster validation. The P6.3 evidence
  comes from read-only Kubernetes API queries. A fresh
  `./gradlew test --rerun-tasks --console=plain` run passed 708 tests with no
  failures, errors or skips (10 tasks executed in 1m 10s). No live denial is
  claimed and no failing test required a fix.
- Recommended next chunk: resume P6.3-P6.10 after the target runtime workloads
  and enforcing network policy are provisioned.

## P6.4 — Runtime Login-Role Membership Assertion

`PersistenceSecurityGatesIntegrationTest.runtimeLoginRolesCannotAssumeOneAnother`
queries `pg_roles` through `pg_has_role(..., 'MEMBER')` for every ordered pair
of `app_api`, `app_worker` and `app_pindist`, excluding self-pairs. The assertion
fails if any direct or inherited cross-membership exists, including either path
from `app_pindist` into the API or worker role. The test is part of the
`integrationTest` suite used by blocking CI stage 8.

Focused verification passed against the migrated PostgreSQL Testcontainer:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.runtimeLoginRolesCannotAssumeOneAnother' \
  --rerun-tasks --console=plain
BUILD SUCCESSFUL
```

## P6.5 — Request-Independent Role Selection

`RuntimeRoleRequestIsolationTest` starts a reactive application context with
the API profile and exposes a test-only probe backed by the singleton
`RuntimeRole` bean. One request simultaneously supplies conflicting role/profile
values through headers, query parameters, a cookie and a JSON body. The handler
still observes `RuntimeRole.API`, proving request data cannot alter the process
role fixed during application-context bootstrap.

Focused verification passed:

```text
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.runtime.RuntimeRoleRequestIsolationTest' \
  --rerun-tasks --console=plain
BUILD SUCCESSFUL
```

## Final Execution Summary for P6.3–P6.10

- Completed: P6.4, P6.5, P6.7 and P6.10. P6.2 was already complete when this
  range began.
- Blocked and left open: P6.3 (no target NetworkPolicy/workloads), P6.6 (no
  deployed external ingress surface), P6.8 (no live workload-identity external
  secret delivery) and P6.9 (the audit port rejects tenantless platform actors).
- User-directed execution rule: blocked tasks were recorded and skipped so
  independent later tasks could proceed; no blocked task was marked complete.
- Security outcome: runtime login-role cross-membership is CI-blocked; request
  data cannot change the boot role; probes are status-only; history and working
  tree contain no detected secret; sweep logs omit exception diagnostics and use
  the shared secret-field policy.
- Full verification:
  `./gradlew test integrationTest conformanceTest --rerun-tasks --console=plain`
  passed 1,437 tests with no failures, errors or skips (16 tasks executed in
  3m 45s). `./gradlew secretScan --rerun-tasks --console=plain` passed history
  and working-tree scans. `spotlessCheck` passed.
- Remaining prerequisites: provision the real staging workloads, identities,
  enforcing CNI and ingress; then add a FEAT-AUD-001 platform-scope transactional
  audit contract before resuming P6.3, P6.6, P6.8 and P6.9.

## P6.6 — Blocked: External Probe Exposure Proof

The accessible `cbt-staging` context contains no Kubernetes `Ingress` and no
Service for API, worker or pindist. Its only application Service is the
rollback PostgreSQL Service. Consequently, there is no deployed external route
against which to prove that `/health/live` and `/health/ready` are unreachable
or that every pindist ingress path except the reserved business template
returns `404`.

The checked-in values keep worker management traffic on port 9091 with
`management.ingress: false`, and the pindist NetworkPolicy exposes only business
port 8080 from the controlled ingress peer. Those static controls are useful,
but they do not satisfy P6.6's external-caller acceptance condition.

Resume P6.6 after deployment by enumerating the rendered and live Ingress,
Gateway/HTTPRoute, Service and load-balancer configuration for all three roles,
then attempt both probe paths from outside the cluster. Retain status and route
evidence without publishing internal topology. P6.6 remains open.

## P6.7 — Probe Response Disclosure Review

The shared application configuration exposes only the health endpoint, disables
endpoint discovery and all other management endpoints by default, and maps the
two orchestrator probes to `/health/live` and `/health/ready`. Both
`management.endpoint.health.show-details` and `show-components` are `never`, so
Spring Boot serializes only the aggregate status for unauthenticated callers;
component names, dependency topology, versions and credential state are not
included. The live and ready groups list only bounded health contributors.

`WorkerHttpSurfaceValidator` fails startup if either disclosure setting changes,
and `WorkerHttpSurfaceValidatorTest` now verifies the values loaded from the
real `application.yaml` along with the exact live and ready contributor groups.
Focused verification passed:

```text
./gradlew test \
  --tests 'org.meldtech.platform.platform.infra.runtime.WorkerHttpSurfaceValidatorTest' \
  --rerun-tasks --console=plain
BUILD SUCCESSFUL
```

## P6.8 — Blocked: Live External-Secret Delivery Confirmation

Static review found no secret value in runtime configuration. Database, broker
and Redis credentials are consumed through config-tree paths under
`/run/secrets`; observability TLS and candidate-hash settings contain references,
not credential material. The worker deployment mounts its broker credentials
through the Secrets Store CSI driver with a provider-class reference.

The pinned history-aware scanner passed both repository history and the working
tree on 2026-10-15:

```text
./gradlew secretScan --rerun-tasks --console=plain
175 commits scanned; no leaks found
working tree scanned; no leaks found
BUILD SUCCESSFUL
```

P6.8 remains open because the accessible cluster has no API, worker or pindist
runtime workload and P6.1 federation is unfulfilled. There is therefore no live
evidence that every referenced secret is delivered from the external secret
manager through the intended workload identity, rather than another deployment
mechanism. Resume by inspecting redacted pod volume/projected-token metadata and
provider audit records for all three roles. Never retain secret contents.

## P6.9 — Blocked: Platform-Scoped Audit Emission

`platform.sweep_registry` is intentionally non-tenant-scoped. Its operator
mutation therefore requires a tenantless workforce actor and a platform
transaction. The shared `AuditEmitter` supplied by `FEAT-AUD-001` rejects both
platform-workforce and platform-system actors before storage with `Tenant audit
emission requires a tenant actor context`; its retained tests explicitly enforce
that behavior. Passing a fabricated tenant would misattribute a platform change
and violate the audit contract.

There is also no existing operator mutation command or runtime grant for this
table: `app_worker` has `SELECT` only and mutation is denied. Implementing a
command before a platform-scope audit port exists would create the unaudited
path P6.9 is meant to prevent. P6.9 remains open.

Resume after `FEAT-AUD-001` publishes a supported platform-scope emission
contract, retention/chain semantics and transactional adapter. Then add the
operator command so the registry update and event
`platform.SWEEP_REGISTRY_CHANGED.v1` use the same caller-owned R2DBC connection,
with rollback tests for both update and audit failures. The event payload should
contain only sweep name, changed setting, previous value and new value; it must
not contain tenant data, credentials or driver error text.

## P6.10 — Sweep Error Hygiene

Sweep telemetry and log field names now pass through `SweepLogPolicy`, which
uses the shared `SecretFieldPattern` from `FEAT-PLAT-003`; no second secret-name
pattern was introduced. The bounded public fields are `sweep`, `sweep_name`,
`outcome` and `action`. Sweep names remain canonical registry identifiers, and
an unexpected value is replaced with `invalid` before logging.

Sweep tick, session-lock release and shutdown-drain failures no longer attach
exception objects to operational logs. This prevents R2DBC driver diagnostics,
SQL text, payload material and tenant identifiers carried in exception messages
or stack data from reaching the log. Logs retain only the stable event name,
canonical sweep name and bounded action. Tick metrics retain only the validated
sweep name and the `Outcome` enum; they never receive a payload or tenant tag.

`SweepRunnerTest.sweepFailuresDoNotLogPayloadDriverTextOrTenantIdentifiers`
injects a failure containing driver text, a credential marker and a tenant-like
UUID, then verifies that none appears and that no throwable proxy is attached.
`SweepLogPolicyTest` verifies every field against `SecretFieldPattern` and the
unsafe-name fallback. Existing telemetry tests cover every bounded tick outcome.

Focused unit tests and formatting verification passed:

```text
./gradlew test --tests 'org.meldtech.platform.platform.infra.runtime.RuntimeRoleRequestIsolationTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.WorkerHttpSurfaceValidatorTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepRunnerTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepLogPolicyTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepTelemetryTest' \
  --tests 'org.meldtech.platform.platform.infra.runtime.SweepLockShutdownTest' \
  spotlessCheck --rerun-tasks --console=plain
BUILD SUCCESSFUL
```
