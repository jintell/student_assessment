-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-006 reserve stable scheduler identities in the approved platform registry

CREATE TABLE platform.sweep_registry (
    sweep_name varchar(96) PRIMARY KEY,
    lock_key bigint NOT NULL UNIQUE,
    owning_module varchar(32) NOT NULL,
    owning_feature varchar(32) NOT NULL,
    default_interval_ms bigint NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    system_actor varchar(40) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sweep_registry_name_format CHECK (
        sweep_name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
    ),
    CONSTRAINT sweep_registry_feature_format CHECK (
        owning_feature ~ '^FEAT-[A-Z]+-[0-9]{3}$'
    ),
    CONSTRAINT sweep_registry_interval_positive CHECK (default_interval_ms > 0),
    CONSTRAINT sweep_registry_timestamp_order CHECK (updated_at >= created_at)
);

COMMENT ON TABLE platform.sweep_registry IS
    'Non-tenant-scoped scheduler registry; stable lock keys are reserved by migrations, never derived at tick time';
