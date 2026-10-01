-- cbt:phase EXPAND
-- cbt:module platform
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 seed bounded outbox relay configuration

CREATE TABLE platform.platform_config (
    config_key varchar(128) PRIMARY KEY,
    value_type varchar(16) NOT NULL,
    integer_value integer NOT NULL,
    minimum_integer integer NOT NULL,
    maximum_integer integer NOT NULL,
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_config_key_format CHECK (
        config_key ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'
    ),
    CONSTRAINT platform_config_integer_type CHECK (value_type = 'INTEGER'),
    CONSTRAINT platform_config_integer_bounds CHECK (
        minimum_integer <= integer_value
        AND integer_value <= maximum_integer
    ),
    CONSTRAINT platform_config_revision_positive CHECK (revision > 0)
);

COMMENT ON TABLE platform.platform_config IS
    'Revisioned runtime configuration; loaders reject an invalid snapshot atomically';

INSERT INTO platform.platform_config (
    config_key,
    value_type,
    integer_value,
    minimum_integer,
    maximum_integer
)
VALUES
    ('outbox.relay.batch-size', 'INTEGER', 200, 1, 1000),
    ('outbox.relay.tick-interval-ms', 'INTEGER', 200, 50, 5000);
