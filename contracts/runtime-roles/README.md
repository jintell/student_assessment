# Runtime Role Manifests

`api.json`, `worker.json` and `pindist.json` are reviewed executable-entry-point
inventories consumed by startup verification and per-role context tests.
They are not a generated list of every Spring bean. Keep the real user type and
route/consumer/sweep metadata exact; missing, extra or changed entries fail boot.

Follow [role placement](../../docs/runtime-role-placement.md) before editing.
A manifest change is a reviewed act, not a test fixture update.
[Runtime roles](../../docs/runtime-roles.md) supplies the living bean/deployment
matrix, architecture source lines and remaining staging obligations.
Current empty worker/pindist inventories do not implement their intended future
capabilities. Test-only reference entries must not be committed here.
