# Runtime Memory Control

Every role requires an explicit `-Xmx` and `-XX:MaxDirectMemorySize`, with
native headroom below its container limit. The approved deployment values are
in `deploy/runtime/values.yaml`. Percentage-based heap overrides are rejected.

The container must expose its own cgroup at `/sys/fs/cgroup`: v2 `memory.max`
and `memory.current`, or v1 `memory/memory.limit_in_bytes` and
`memory/memory.usage_in_bytes`. `cbt.runtime.cgroup-root` can select another
absolute mount of that same process cgroup. Missing, unlimited or invalid
limits fail startup. There is no host-memory fallback or monitoring-disable
flag. Local launches also need explicit JVM caps and a bounded cgroup; test
fixtures supply isolated cgroup files rather than changing production policy.

One bounded operational sampler reads heap and container usage every second,
off request event loops. Heap usage at or above 85% for 60 continuous seconds,
or container usage at or above 90% for 10 continuous seconds, latches a restart.
A lower sample resets only that pressure window. Failed measurements also
fail closed. No forced collection or heap dump is used.

The latch refuses new admission, changes readiness to refusing traffic, and
fails `/health/live`. Kubernetes must configure that liveness endpoint and
the existing preStop/SIGTERM drain policy to restart the container. The process
does not clear its latch after memory recovers. Outside an orchestrator, an
operator must restart it. `runtime.memory.restart` counts the bounded reasons
`HEAP`, `CONTAINER`, and `MEASUREMENT_FAILED`; an ERROR log records the same
reason without memory contents or request identifiers. P9.2 consumes this
event for restart alerts. Deployed restart timing and native-headroom load
tests remain P7.16/capacity verification, not a unit-test claim.
