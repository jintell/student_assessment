-- cbt:phase EXPAND
-- cbt:module outbox
-- cbt:transactional true
-- cbt:justification FEAT-PLAT-004 create the partitioned transactional outbox

CREATE TABLE outbox.outbox_event (
    outbox_event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    aggregate_type varchar(128) NOT NULL,
    aggregate_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    correlation_id varchar(26) NOT NULL,
    traceparent varchar(55),
    tracestate varchar(512),
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count smallint NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_expires_at timestamp with time zone,
    claimed_by varchar(128),
    occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamp with time zone,
    last_error varchar(64),
    CONSTRAINT outbox_event_pk PRIMARY KEY (created_at, outbox_event_id),
    CONSTRAINT outbox_event_type_format CHECK (
        event_type ~ '^[a-z][a-z0-9]*\.[A-Z][A-Za-z0-9]*\.v[1-9][0-9]*$'
    ),
    CONSTRAINT outbox_event_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_event_correlation_id_format CHECK (
        correlation_id ~ '^[0-9A-HJKMNP-TV-Z]{26}$'
    ),
    CONSTRAINT outbox_event_state CHECK (
        state IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT outbox_event_attempt_count CHECK (attempt_count BETWEEN 0 AND 8),
    CONSTRAINT outbox_event_claim_shape CHECK (
        (state = 'CLAIMED' AND claim_expires_at IS NOT NULL AND claimed_by IS NOT NULL)
        OR
        (state <> 'CLAIMED' AND claim_expires_at IS NULL AND claimed_by IS NULL)
    ),
    CONSTRAINT outbox_event_published_shape CHECK (
        (state = 'PUBLISHED' AND published_at IS NOT NULL AND last_error IS NULL)
        OR
        (state <> 'PUBLISHED' AND published_at IS NULL)
    ),
    CONSTRAINT outbox_event_failed_shape CHECK (
        (state = 'FAILED' AND attempt_count = 8 AND last_error IS NOT NULL)
        OR
        (state <> 'FAILED' AND attempt_count < 8)
    )
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE outbox.outbox_event IS
    'Transactional integration-event outbox; audit is the durable evidence after retention';
