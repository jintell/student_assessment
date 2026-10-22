# ADR-P722-001 — Temporary local monitoring verification

Status: PROPOSED — formal owner approvals pending
Date: 2026-10-19
Scope: FEAT-PLAT-004 P7.22, interim local Outcome 5 verification

## Context

The project is pre-production and has no funded staging environment for this
acceptance exercise. Maintaining permanent cloud infrastructure solely for
P7.22 is not justified. The existing local Kubernetes installation can verify
the monitoring integration at no additional cloud cost. A context alias named
`cbt-staging` points to the same Docker Desktop cluster; that name does not make
the installation a representative staging environment.

## Proposed decision

Use an isolated namespace in the existing local kind-based Docker Desktop
cluster for the interim database → secured sampler → Prometheus → Alertmanager
→ local receiver/Grafana drill. Preserve the existing strict >5000 pending rows
OR >300 seconds rule, severity p2 and no hold-down. Verify each branch, normal
relay/broker recovery and collection-loss semantics with real elapsed time.

The local-only artifact assembles production sampler, runtime and relay adapters.
It includes an authenticated relay-hold control and test receiver excluded from
the production artifact. Local timings and namespace-scoped enablement are drill
parameters, not approval of production defaults or production P8.5 assembly.
Use ephemeral generated credentials, ClusterIP services and loopback-only port
forwards. Keep all existing CI gates mandatory. Do not change public ingress,
production security, event state to clear an alarm, or the original acceptance
criteria. Retain evidence and remove the isolated resources after verification.

## Limitation and release gate

Local verification cannot establish cloud identity/TLS, real operator routing,
production retention-volume performance, deployment availability or staging
recovery. P7.22 remains unchecked under its original acceptance criteria.
P7.22-STG is mandatory before first production release and should be scheduled
before external beta using a short-lived representative staging environment.
P8.5/P8.6 and other existing release gates remain independently applicable.

The [exception request](../evidence/FEAT-PLAT-004/P7.22-staging-deferral.md)
records residual risk and owner handoffs. An approved deferral accepts specified
risk; it cannot turn unexecuted staging checks into PASS.

## Approval request

| Required role | Approval / identity / date |
|---|---|
| Solution Architect | PENDING |
| Engineering Lead | PENDING |
| Product Owner | PENDING |
| Security / Platform Ops | PENDING — review residual deployment and routing risk |

The user's instruction authorizes performing the local drill. It does not supply
the named governance approvals above. This proposed ADR is the concrete approval
packet; no signatures, ratification or external acknowledgement are fabricated.

## Evidence

See [local monitoring verification](../evidence/FEAT-PLAT-004/P7.22-local-monitoring-verification.md)
for the executed environment, timings, receiver receipts and dashboard queries.
