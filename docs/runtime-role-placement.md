# Runtime Role Placement

FEAT-PLAT-006 P10.2 governs new executable entry points. Select exactly one
active runtime profile (`api`, `worker` or `pindist`); environment profiles may
coexist. Missing or multiple active roles fail startup. `--migrate-only` is a
separate entrypoint, not a fourth role.

| Entry point | Placement | Boundary |
|---|---|---|
| Ordinary business HTTP endpoint | `api` | No broker consumers, sweeps or PIN-distribution route |
| Broker consumer or scheduled sweep | `worker` | No business HTTP; health uses the management port |
| Coordinator PIN-distribution read | `pindist` | Only `GET /api/v1/exam-sessions/{id}/pin-distribution`; distinct identity |
| Shared security, persistence or telemetry adapter | As required by its consumers | Supporting beans are not business entry points |

The PIN-distribution endpoint remains reserved until FEAT-EXAM-003 is delivered.
Do not add a placeholder endpoint to satisfy the manifest. Its key grant belongs
to FEAT-EXAM-002; role selection alone does not grant access to the key.

## Declare the Boundary

Place exactly one direct `@RoleScoped("api")`, `@RoleScoped("worker")` or
`@RoleScoped("pindist")` on the component type, or on the uniquely named `@Bean`
factory method, never both. The annotation accepts a closed set of profile-name
strings through `RoleScopedCondition`; it is not the earlier design sketch's
`@Profile` alias. Bare `@Profile`, an enclosing configuration annotation, custom
composed annotations or inherited declarations do not satisfy the contract.
Do not use expressions, placeholders, duplicates or environment-profile names.

Keep endpoints in their owning slices and consumers/sweeps in the feature's
`infra` package. Domain types must not carry Spring role annotations. Depend
inward through published APIs. A sweep uses the common runner and `SweepLock`
port; an independent `Flux.interval`, `@Scheduled`, timer or advisory-lock
implementation defeats the singleton rule and is rejected by conformance.

## Review the Golden Manifest

The three JSON files in `contracts/runtime-roles/` inventory executable entry
points, not every Spring support bean. A manifest change is a reviewed act,
not a test fixture update. Explain why the role, permissions and route belong
there before accepting an added or removed entry.

1. Update only the relevant role's `entryPoints`, preserving the schema version,
   category restrictions and business-route policy. Use the real user-class name,
   kind, and route ID/method/template, consumer queue or sweep name as applicable.
   Factory entries include `factoryType` and `factoryMethod`; never record proxy
   class names. Use existing entries and `RuntimeRoleManifest` as the schema.
2. Keep entries unique and deterministically ordered. Do not relax a forbidden
   category, add management routes as business routes or copy test-only sweeps.
3. Run `./gradlew conformanceTest test --tests '*Runtime*'` for the role rules
   and role/context suites, then the owning feature's focused tests. Run sweep
   PostgreSQL integration tests when adding a sweep.
4. Inspect missing/extra identities and metadata differences. The verifier checks
   both candidate definitions before construction and instantiated routes/beans
   before lifecycle startup; fix the source of unexpected entries before review.

Retain negative coverage for missing/invalid declarations, wrong-role entry
points and manifest drift. See [role-misconfiguration diagnosis](runbooks/runtime-role-misconfiguration.md)
and [sweep authoring](sweep-authoring.md).
