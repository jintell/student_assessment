# Shared-Kernel Adoption Seam

Status: **CLOSED**

This record connects the three Phase 0 task lists that introduced, consumed,
and retired temporary request-context carriers:

- [`FEAT-PLAT-001`](../tasks/foundation/baseline/tasks.md) created the reactive
  propagation mechanism and temporary tenant/actor carriers at `P4.6`-`P4.9`.
- [`FEAT-PLAT-002`](../tasks/foundation/persistence/tasks.md) built transaction
  security-context initialization against that temporary tenant contract.
- [`FEAT-PLAT-003`](../tasks/foundation/kernel/tasks.md) supplied the definitive
  kernel types and closed both adoption seams at `P4.15`-`P4.17`.

The earlier task-list references to future adoption are historical sequencing
notes. They do not describe a currently supported compatibility layer.

## Closure Map

| Consumer | Original seam | Definitive contract | Closure task and evidence |
|---|---|---|---|
| `FEAT-PLAT-001` | Temporary `RequestTenantId` and `RequestActor` values carried by the WebFilter and logging bridge | `shared.kernel.identity.TenantId`, `shared.kernel.context.ActorContext`, and the real `RequestContextPropagation` payload | `FEAT-PLAT-003` `P4.15`-`P4.16` deleted the placeholders; `P7.17` reran R1-R8 |
| `FEAT-PLAT-002` | `SecurityContextInitializer` awaited the definitive tenant value | `shared.kernel.identity.TenantId` throughout transaction-local role and tenant installation | `FEAT-PLAT-003` `P4.17`; `P7.18` reran the four-path adversarial pooled-connection suite |

## Supported State

No compatibility alias, overload, adapter, or placeholder type remains. New
and existing slice code uses `TenantId` and `ActorContext` directly. The
request WebFilter resolves these values into Reactor context, the logging
bridge propagates them across scheduler hops, tenant-scoped query signatures
accept the real `TenantId`, and database security-context initialization uses
the same tenant value.

A source-wide search over production, unit, conformance, and integration test
sources contains no `RequestTenantId` or `RequestActor` reference. Compilation
and the conformance suite pass with the definitive types. The retained
pooled-connection adoption report is
[`P7.18-arc-verify-024-adoption-regression.json`](evidence/FEAT-PLAT-003/P7.18-arc-verify-024-adoption-regression.json).

Reintroducing a placeholder carrier would reopen the seam, create competing
identity contracts, and require fresh R1-R8 plus pooled-connection isolation
evidence. It is not a backward-compatibility option.
