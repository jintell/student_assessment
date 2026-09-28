# Redis Secret Resolution

Production Redis credentials are supplied by the deployment platform's external secret manager and
mounted as a Spring config tree. The default mount is `/run/secrets/redis/`;
`CBT_REDIS_SECRETS_PATH` may select another mount without carrying secret material itself.

The mount must contain a file named `cbt.redis.password`. The `production` Spring profile maps that
property to `spring.data.redis.password` without a committed default. `CBT_REDIS_HOST`,
`CBT_REDIS_PORT`, and `CBT_REDIS_USERNAME` supply non-secret connection metadata; TLS is mandatory in
that profile.

Local development uses the loopback-only, ephemeral Redis service in `compose.yaml` without a
credential. That service is not a production configuration and must not be exposed or promoted.

Production provisioning and the workload-specific secret mount remain blocked by
`GAP-FEAT-PLAT-003-P0.5` until the Engineering Lead assigns an implementation feature. An unmanaged
production Redis instance is not permitted.
