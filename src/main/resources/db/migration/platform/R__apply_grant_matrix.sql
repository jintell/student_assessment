-- GENERATED from db/grants/grant-matrix.json; do not edit.
-- source-sha256: 9b489d10e1725155edaa6fd6d16152d499e78ccef3859e3a6efff5e888dd8082
-- Grant refresh: ${grantRefresh}

ALTER ROLE app_academic WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_api WITH LOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_audit_partition_maintenance WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_audit_retention WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_audit_sealer WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_authoring WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_correction WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_delivery WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_examaccess WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_grading WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_iam WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_notification WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_outbox_maintenance WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_outbox_relay WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_people WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_pindist WITH LOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_questionbank WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_readonly_ops WITH LOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_result WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_tenancy WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_txn_examentry WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_worker WITH LOGIN NOCREATEROLE NOINHERIT;

GRANT app_academic TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_authoring TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_correction TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_delivery TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_examaccess TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_grading TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_iam TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_notification TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_people TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_questionbank TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_result TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_tenancy TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_txn_examentry TO app_api WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_examaccess TO app_pindist WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_academic TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_authoring TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_correction TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_delivery TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_examaccess TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_grading TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_iam TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_notification TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_outbox_maintenance TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_outbox_relay TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_people TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_questionbank TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_result TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_tenancy TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA academic TO app_academic;

GRANT USAGE ON SCHEMA academic TO app_academic;

GRANT USAGE ON SCHEMA audit TO app_academic;

GRANT USAGE ON SCHEMA outbox TO app_academic;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_academic';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_academic';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_academic';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('audit.provision_audit_epoch_heads(uuid, text, date, integer, smallint)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION audit.provision_audit_epoch_heads(uuid, text, date, integer, smallint) TO app_audit_partition_maintenance';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('audit.provision_audit_month(date)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION audit.provision_audit_month(date) TO app_audit_partition_maintenance';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('audit.provision_audit_root_head(uuid)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION audit.provision_audit_root_head(uuid) TO app_audit_partition_maintenance';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('audit.provision_future_epoch_heads(date)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION audit.provision_future_epoch_heads(date) TO app_audit_partition_maintenance';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA audit TO app_audit_partition_maintenance;

GRANT USAGE ON SCHEMA audit TO app_audit_retention;

GRANT USAGE ON SCHEMA audit TO app_audit_sealer;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_checkpoint') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_chain_checkpoint TO app_audit_sealer';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE audit.audit_chain_head TO app_audit_sealer';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_root_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_root_head TO app_audit_sealer';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_seal') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT ON TABLE audit.audit_chain_seal TO app_audit_sealer';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA authoring TO app_authoring;

GRANT USAGE ON SCHEMA audit TO app_authoring;

GRANT USAGE ON SCHEMA authoring TO app_authoring;

GRANT USAGE ON SCHEMA outbox TO app_authoring;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_authoring';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_authoring';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_authoring';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA correction TO app_correction;

GRANT USAGE ON SCHEMA audit TO app_correction;

GRANT USAGE ON SCHEMA correction TO app_correction;

GRANT USAGE ON SCHEMA outbox TO app_correction;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_correction';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_correction';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_correction';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA delivery TO app_delivery;

GRANT USAGE ON SCHEMA audit TO app_delivery;

GRANT USAGE ON SCHEMA delivery TO app_delivery;

GRANT USAGE ON SCHEMA outbox TO app_delivery;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_delivery';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_delivery';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_delivery';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA examaccess TO app_examaccess;

GRANT USAGE ON SCHEMA audit TO app_examaccess;

GRANT USAGE ON SCHEMA examaccess TO app_examaccess;

GRANT USAGE ON SCHEMA outbox TO app_examaccess;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_examaccess';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_examaccess';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_examaccess';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA grading TO app_grading;

GRANT USAGE ON SCHEMA audit TO app_grading;

GRANT USAGE ON SCHEMA grading TO app_grading;

GRANT USAGE ON SCHEMA outbox TO app_grading;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_grading';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_grading';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_grading';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA iam TO app_iam;

GRANT USAGE ON SCHEMA audit TO app_iam;

GRANT USAGE ON SCHEMA iam TO app_iam;

GRANT USAGE ON SCHEMA outbox TO app_iam;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_iam';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_iam';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_iam';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA notification TO app_notification;

GRANT USAGE ON SCHEMA audit TO app_notification;

GRANT USAGE ON SCHEMA notification TO app_notification;

GRANT USAGE ON SCHEMA outbox TO app_notification;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_notification';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_notification';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_notification';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('outbox.attach_week_partition(date)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION outbox.attach_week_partition(date) TO app_outbox_maintenance';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regprocedure('outbox.detach_expired_partition(name)') IS NOT NULL THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION outbox.detach_expired_partition(name) TO app_outbox_maintenance';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA outbox TO app_outbox_maintenance;

GRANT USAGE ON SCHEMA outbox TO app_outbox_relay;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE outbox.outbox_event TO app_outbox_relay';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA people TO app_people;

GRANT USAGE ON SCHEMA audit TO app_people;

GRANT USAGE ON SCHEMA outbox TO app_people;

GRANT USAGE ON SCHEMA people TO app_people;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_people';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_people';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_people';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA questionbank TO app_questionbank;

GRANT USAGE ON SCHEMA audit TO app_questionbank;

GRANT USAGE ON SCHEMA outbox TO app_questionbank;

GRANT USAGE ON SCHEMA questionbank TO app_questionbank;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_questionbank';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_questionbank';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_questionbank';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA platform TO app_readonly_ops;

DO $$
BEGIN
    IF to_regclass('platform.database_diagnostics') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE platform.database_diagnostics TO app_readonly_ops';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA result TO app_result;

GRANT USAGE ON SCHEMA audit TO app_result;

GRANT USAGE ON SCHEMA outbox TO app_result;

GRANT USAGE ON SCHEMA result TO app_result;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_result';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_result';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_result';
    END IF;
END
$$;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA tenancy TO app_tenancy;

GRANT USAGE ON SCHEMA audit TO app_tenancy;

GRANT USAGE ON SCHEMA outbox TO app_tenancy;

GRANT USAGE ON SCHEMA tenancy TO app_tenancy;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_tenancy';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_tenancy';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_tenancy';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA audit TO app_txn_examentry;

GRANT USAGE ON SCHEMA authoring TO app_txn_examentry;

GRANT USAGE ON SCHEMA delivery TO app_txn_examentry;

GRANT USAGE ON SCHEMA examaccess TO app_txn_examentry;

GRANT USAGE ON SCHEMA outbox TO app_txn_examentry;

GRANT USAGE ON SCHEMA people TO app_txn_examentry;

GRANT USAGE ON SCHEMA tenancy TO app_txn_examentry;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, UPDATE ON TABLE audit.audit_chain_head TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.assessment') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.assessment TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.assessment_config') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.assessment_config TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.exam_session') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.exam_session TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.section') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.section TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.section_item') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.section_item TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('authoring.session_candidate') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.session_candidate TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('delivery.attempt') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT ON TABLE delivery.attempt TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('delivery.attempt_presentation') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE delivery.attempt_presentation TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('delivery.attempt_question_evidence') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE delivery.attempt_question_evidence TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('examaccess.candidate_identity_verification') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT, UPDATE ON TABLE examaccess.candidate_identity_verification TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('examaccess.candidate_principal') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT, UPDATE ON TABLE examaccess.candidate_principal TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('examaccess.exam_access_pin') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT, UPDATE ON TABLE examaccess.exam_access_pin TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('examaccess.pin_validation_guard') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT, SELECT, UPDATE ON TABLE examaccess.pin_validation_guard TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('people.candidate') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE people.candidate TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('people.candidate_assignment') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE people.candidate_assignment TO app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('tenancy.tenant') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE tenancy.tenant TO app_txn_examentry';
    END IF;
END
$$;

ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA audit GRANT INSERT ON TABLES TO app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry;

ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA outbox GRANT INSERT ON TABLES TO app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_checkpoint') IS NOT NULL THEN
        EXECUTE 'REVOKE INSERT ON TABLE audit.audit_chain_checkpoint FROM app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_head') IS NOT NULL THEN
        EXECUTE 'REVOKE INSERT ON TABLE audit.audit_chain_head FROM app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_root_head') IS NOT NULL THEN
        EXECUTE 'REVOKE INSERT ON TABLE audit.audit_chain_root_head FROM app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry';
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('audit.audit_chain_seal') IS NOT NULL THEN
        EXECUTE 'REVOKE INSERT ON TABLE audit.audit_chain_seal FROM app_academic, app_authoring, app_correction, app_delivery, app_examaccess, app_grading, app_iam, app_notification, app_people, app_questionbank, app_result, app_tenancy, app_txn_examentry';
    END IF;
END
$$;
