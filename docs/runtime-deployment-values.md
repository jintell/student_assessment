# Runtime Deployment Values

`deploy/runtime/values.yaml` is the input contract for Platform Ops' deployment
renderer, not a Helm chart or a claim of a deployed environment. It transcribes
architecture sections 8.2, 15.2 and 17.1. All workloads consume the single
top-level `image.reference`, supplied as `CBT_PLATFORM_IMAGE_DIGEST` in
`repository@sha256:...` form. There is no per-role image selector. The role is
selected at process startup by `SPRING_PROFILES_ACTIVE`.

API uses 12 replicas, HPA 6-30, CPU plus exam-path p95; worker uses six replicas,
ten pre-warmed for scheduled close, HPA 3-24, and queue depth; PIN distribution
uses two replicas and has no HPA. Pre-warming raises the effective worker
minimum to ten during the close window; assigning replicas alone is insufficient
when an HPA can scale back down. FEAT-GRD-003 owns the close-window controller.
HPA scale decisions must consume both API signals and choose the larger replica
recommendation. The named latency and queue metrics require an installed
custom/external metrics adapter with the appropriate route/queue selectors.

Platform Ops must supply positive, calibrated scaling targets:

- `CBT_API_HPA_CPU_TARGET_PERCENT` (1-100).
- `CBT_API_HPA_EXAM_PATH_P95_TARGET_SECONDS` (seconds).
- `CBT_WORKER_HPA_QUEUE_DEPTH_TARGET` (messages per worker replica).

No target numbers are invented: the baseline specifies the signal and bounds,
not their thresholds. Renderers must reject unresolved placeholders. Resource
requests and limits are copied exactly from section 17.1. The worker's unequal
memory request and limit do not imply Kubernetes Guaranteed QoS, despite the
architecture table's CPU-only wording. Grading prefetch five is a grading
consumer requirement; it does not replace the existing outbox consumer settings.

The explicit heap/direct-memory caps follow the P2.11 PLAN-RECOMMENDATION and
require workload validation; they are not additional ratified capacity figures.
No secrets, KMS grants, private keys or issuer endpoints are supplied here.
Database connection inputs remain in `docs/runtime-connection-pools.md`.
All Services use `sessionAffinity: None`; ingress must also have cookie/session
stickiness disabled. Exact rendered manifest verification is P7.17.

Role isolation, route restrictions, health ports and startup assertions are
implemented in Phase 4. These values do not prove that the current application's
bean set is already isolated. FEAT-OPS-007 owns deployment orchestration;
FEAT-EXAM-002 owns the retrieval-key grant and real-identity denial evidence.

## PIN-Distribution Identity

`deploy/kubernetes/pindist-service-account.yaml.template` supplies the distinct
`cbt-pindist` ServiceAccount. The deployment must set `serviceAccountName` from
the values contract. The worker retains its existing `cbt-platform-worker`
ServiceAccount; API uses `cbt-api`. Neither may assume the pindist identity.

Platform Ops supplies `CBT_APPLICATION_NAMESPACE` and
`CBT_PINDIST_WORKLOAD_IDENTITY`, then configures its provider's federation
binding for exactly `system:serviceaccount:<namespace>:cbt-pindist` with the
provider-required issuer and audience. The custom workload-identity annotation
is an inventory reference, not a cloud-provider binding by itself. A provider
overlay must supply the native annotation/webhook and explicit projected token
with its audience. Automatic Kubernetes API token mounting is disabled; no
Kubernetes RoleBinding or cloud secret is granted here.

FEAT-EXAM-002 binds its retrieval KEK policy to this separately provisioned
identity and proves API/worker denial with real provider credentials. P3.7
delivers the identity mechanism/values, not that grant or the A4 attestation.

## PIN-Distribution Network Boundary

`deploy/kubernetes/pindist-network-policy.yaml.template` isolates inbound
traffic to pods labelled `app.kubernetes.io/name=cbt-platform` and
`app.kubernetes.io/component=pindist`. Platform Ops must apply both labels to
the workload. Only TCP 8080 from the named ingress namespace AND the selected
controller pods is allowed. API and worker sources have no matching allow rule.
There is no peer-to-peer validation-runtime route to the KEK holder.

`CBT_INGRESS_NAMESPACE` must identify a separately controlled namespace, not
`CBT_APPLICATION_NAMESPACE`. `CBT_INGRESS_CONTROLLER_NAME` must match the actual
controller pods. These are required deployment inputs, not permissive defaults.
Use a NetworkPolicy-enforcing CNI; do not add overlapping policies that broaden
this allowlist (Kubernetes policies are additive), run these workloads with
host networking, or grant application identities ingress-namespace mutation.
Management port 9090 is not exposed to ingress; node-local probes remain a
Platform Ops concern. Monitoring overlays must not reopen the business port.

Ingress still enforces the single route and authentication separately. This
policy limits source pods/ports, not HTTP paths, and cannot authenticate an
original caller proxied through ingress. The P6.3/P7.13 live network and route
proofs remain later work; this task supplies and tests the policy values.

## Shutdown Budget

Apply `common` to all three pod specifications, merging its environment with
the role environment. The exec hook first touches `/run/cbt/draining` then
waits 30 seconds. Mount the pod-local memory-backed `drain-state` emptyDir at
`/run/cbt`; the pod security context makes it writable by UID/GID 10001. The
shared image must provide `/bin/sh`, `touch` and `sleep`. Readiness consumes the
marker in P4.14-P4.17; these deployment values do not implement that reader.

P2.7 sets `SERVER_SHUTDOWN=graceful` and
`SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=5s`, not a 25-second timeout for
each phase. The 60-second pod termination grace includes preStop:

| Component | Seconds |
| --- | ---: |
| preStop | 30 |
| Four waiting phases, five seconds each | 20 |
| All other cleanup combined | 5 |
| Safety margin | 5 |
| Total | 60 |

The waiting phases are sweep intake/batch, WebFlux, broker consumers, then
lock release/pool disposal. Other cleanup includes both web contexts, Reactor,
telemetry and destroy callbacks; five seconds is their combined allowance.
Phase implementations and underlying client deadlines remain P4.14-P4.17;
P7.16 measures the actual bound. A per-phase Spring timeout is not by itself
a total process-exit guarantee. New waiting phases require a revised budget.
