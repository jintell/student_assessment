-- cbt:phase EXPAND
-- cbt:module audit
-- cbt:transactional true
-- cbt:justification FEAT-AUD-001 create the single compare-and-swap root head per tenant

CREATE TABLE audit.audit_chain_root_head (
    tenant_id uuid PRIMARY KEY,
    root_seq bigint NOT NULL DEFAULT 0,
    root_head_hash bytea NOT NULL DEFAULT decode(repeat('00', 32), 'hex'),
    sealed_at timestamp with time zone,
    CONSTRAINT audit_chain_root_head_seq_non_negative CHECK (root_seq >= 0),
    CONSTRAINT audit_chain_root_head_hash_sha256 CHECK (
        octet_length(root_head_hash) = 32
    ),
    CONSTRAINT audit_chain_root_head_initial_shape CHECK (
        (root_seq = 0 AND root_head_hash = decode(repeat('00', 32), 'hex') AND sealed_at IS NULL)
        OR
        (root_seq > 0 AND sealed_at IS NOT NULL)
    )
);

CREATE FUNCTION audit.provision_audit_root_head(target_tenant_id uuid)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    INSERT INTO audit.audit_chain_root_head (tenant_id)
    VALUES (target_tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;
$$;

REVOKE ALL ON FUNCTION audit.provision_audit_root_head(uuid) FROM PUBLIC;
