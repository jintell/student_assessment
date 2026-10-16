# Runtime Roles: Living Deployment Contract

FEAT-PLAT-006 P10.7 is the entry point for Platform Ops and FEAT-OPS-007.
Update this document, the reviewed [role manifests](../contracts/runtime-roles/)
and [deployment values](../deploy/runtime/values.yaml) together when their
contract changes. Values are renderer inputs, not a deployed Helm release.
Source references below use architecture v1.4 at tag `arch-v1.4`, commit
`8ea8250ef7ef6877eaf2d89444d473f7a4933fbc`, file
`workspace/v3/be/architecture.md` in the sibling `student_assessment` repository.
Line numbers were checked against that tag on 2026-10-16.

## Bean and Route Matrix (§8.2)

| Profile / workload | Permitted entry points | Forbidden | Scaling | Source line |
|---|---|---|---|---|
| `api` / `cbt-api` | Ordinary workforce, candidate, operator and webhook endpoints | Schedulers, broker consumers, PIN distribution | CPU and exam-path p95 | 932 |
| `worker` / `cbt-worker` | Grading consumers, notification dispatch, schedulers, relay | Business HTTP | Grading and notification queue depth; exam-calendar pre-warming | 933 |
| `pindist` / `cbt-pindist` | Coordinator PIN-distribution read only | Every other business endpoint, consumers, schedulers; owns no schema | Fixed, no HPA | 934 |

Lines 925–928 require one artifact, identical code/migrations/configuration
surface and role-selected beans. Supporting security, health, persistence and
telemetry beans are shared where needed. Lines 936–939 require PostgreSQL
advisory-lock non-overlap and skipped losing contenders; the implemented lock
port uses stored registry keys and two bounded lanes.

The current manifests are verified entry-point inventories: API contains only
`platform.getConformanceReference`; worker and pindist have no production entry
points yet. Intended capabilities are not implemented business routes or sweeps.
Pindist currently exposes probes only; FEAT-EXAM-003 must supply its reserved
`GET /api/v1/exam-sessions/{id}/pin-distribution` route. The versioned template
follows §17.4 line 3139, resolving the shorter §8.2 line 934 spelling (OBS-002).
Management probes use a separate port excluded from ingress.

## Deployment Figures (§17.1)

Each row transcribes all its figures from the named source line unless noted.

| Workload | Replicas / HPA | CPU request / limit | Memory request / limit | Pool and execution | Source line |
|---|---|---|---|---|---|
| `cbt-api` | 12; HPA 6–30 | 2 / 4 vCPU | 3 / 4 Gi | 14 connections, 8 reserved exam-path, 6 general (14 − 8) | 3095 |
| `cbt-worker` | 6 baseline; 10 pre-warmed; HPA 3–24 | 4 / 4 vCPU | 2 / 3 Gi | 10 connections: 4 grading + 6 background; concurrency 4, prefetch 5 | 3096 |
| `cbt-pindist` | 2; no HPA | 1 / 1 vCPU | 1 / 1 Gi | 5 connections | 3097; pool from §15.2 line 2654 |
| `cbt-migrate` | 1 Job per release | 1 vCPU | 1 Gi | `--migrate-only`; completion before new pods | 3098 |

The migration Job is not a fourth runtime role. Worker background capacity is
partitioned into four business and two lock connections by the signed P0.6
registry/budget approval (`ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`).
It does not add to the ten-connection ceiling. See the
[envelope evidence](evidence/FEAT-PLAT-006/P8.5-connection-envelope.md) for all four
ARC-PERF-006 figures. HPA ceilings cannot increase without re-deriving that budget.

The baseline's CPU-only claim of worker Guaranteed QoS is not an implementation
guarantee: its memory request and limit differ. Values preserve the published
resource figures. Proposed JVM heap/direct-memory caps (API 2048/256 MiB,
worker 1536/256 MiB, pindist 512/128 MiB) come from P2.11 and the values file,
not §17.1 approval; workload validation remains required.

## Identity, Probes and Release

- Service accounts: `cbt-api`, `cbt-platform-worker`, and distinct `cbt-pindist`.
  The names are deployment implementation choices; a distinct pindist identity
  is mandated by §17.1 line 3097. No API/worker identity may assume it.
- All Services set `sessionAffinity: None`; ingress stickiness must also be off
  (§17.1 line 3095, line 3097 and §17.3). Worker has no business listener or
  ingress. API/pindist business port is 8080; management defaults to 9091 for
  all roles. These port numbers are implementation choices in
  `application.yaml` and deployment values, not architecture figures.
- Probe paths are `/health/live`, `/health/ready`, `/health/startup`. The pindist
  NetworkPolicy allows its business port only from the selected ingress and
  denies API/worker pod-to-pod access (§17.4 line 3139). Provider federation and
  KEK grants require separate provisioning and real-identity proof.
- One `CBT_PLATFORM_IMAGE_DIGEST` feeds all roles (§17.1 lines 3085–3087).
  Signed image/SBOM evidence remains blocked at P8.3 pending FEAT-OPS-007.
- preStop 30 s and termination grace 60 s come from §17.6 lines 3239 and 3241.
  Four five-second waiting phases, five seconds cleanup and five seconds margin
  are the implemented P2.7 allocation; follow the [shutdown contract](shutdown-contract.md).
- `cbt-migrate` creates the registry before workers start. Rollback is code-only;
  leave additive registry migrations in place. The proposed pindist rollout
  step 6b is recorded in [P8.6](evidence/FEAT-PLAT-006/P8.6-pindist-rollout-amendment.md),
  not asserted as approved orchestration.

## Inputs, Review and Outstanding Evidence

The [deployment runbook](runbooks/runtime-deployment-values.md) documents required
external image, identity, namespace, ingress and calibrated HPA target inputs.
The worker values currently wire grading queue depth; §8.2's notification-depth
signal remains a downstream notification/Platform Ops integration requirement.
Do not claim the values already wire both queue signals. During pre-warming,
the effective HPA minimum must hold ten workers; FEAT-GRD-003 owns that controller.

Follow [role placement](runtime-role-placement.md) for every manifest review.
`RuntimeRoleConfigurationTest`, `RuntimeManifestVerifierTest`,
`RuntimeRoleConformanceTests` and `RuntimeDeploymentValuesTest` check local
contracts. P7.17/P7.18/P8.4 remain blocked on staging provisioning: local values
do not prove deployed affinity or replica failover. ARC-VERIFY-007's mid-exam
limb is scheduled for Phase 3/6, **not green**. ARC-VERIFY-025/A4 and
ARC-VERIFY-033/A8 still require real identity/capacity evidence from their owners.
