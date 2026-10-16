# Runtime-Role Misconfiguration

Use this runbook when a runtime pod fails during Spring context startup. Do not
add a default profile or disable a validator to restore service.

## Failure Map

| Symptom | Boot failure | Likely cause |
|---|---|---|
| No role starts | `RUNTIME_ROLE_INVALID: exactly one active role is required: api, worker, pindist` | `SPRING_PROFILES_ACTIVE` is unset or contains none of the three roles. |
| Two role surfaces attempt to start | Same `RUNTIME_ROLE_INVALID` failure | More than one of `api`, `worker`, and `pindist` is active. |
| Telemetry initialization stops | `RUNTIME_TELEMETRY_ROLE_MISMATCH` | `CBT_OBSERVABILITY_ROLE` disagrees with the active runtime profile. |
| Bean inventory differs from the golden file | `RUNTIME_MANIFEST_INVALID: entry points missing=..., extra=..., duplicate=...` or `registered entry-point metadata differs from manifest` | A route, consumer, or sweep was added/removed without the matching role declaration and reviewed manifest update. |
| PIN-distribution pod rejects its route set | `RUNTIME_MANIFEST_INVALID: pindist business route allowlist violated` | The reserved route is missing before delivery status permits it, or any extra business route is registered. |
| Worker pod exposes business HTTP | `RUNTIME_MANIFEST_INVALID: worker HTTP surface: ...` | Business routes are present or the separate management-port settings drifted. |

## Diagnose

1. Read the pod's effective environment and Deployment revision. Confirm
   exactly one runtime profile and the same lowercase observability role.
2. Compare the image digest with the release record. All three workloads must
   use one digest; a role-specific image is invalid.
3. Compare the failing role with `contracts/runtime-roles/<role>.json` and the
   entry-point type named by the boot failure.
4. For `pindist`, inspect the functional route inventory separately from
   management-port health probes. The only permitted business template after
   `FEAT-EXAM-003` delivery is
   `GET /api/v1/exam-sessions/{id}/pin-distribution`.
5. For `worker`, require `server.port=-1`, management port `9091`, and health as
   the only exposed management endpoint.

## Recover

- Configuration drift: restore the reviewed role values and roll the affected
  workload. Do not change the shared image.
- Image/manifest drift: roll back all workloads to the previous immutable
  digest, then correct the code, `@RoleScoped` declaration, tests, and golden
  manifest together.
- Unexpected `pindist` route: keep the pod unready, remove the route from that
  role, and treat an exposed non-allowlisted route as a security incident.

Verify the replacement pod reaches ready, its manifest check passes, telemetry
reports the intended role, and no forbidden route, scheduler, or consumer is
present.
