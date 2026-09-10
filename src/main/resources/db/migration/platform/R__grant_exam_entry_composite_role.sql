-- Grant refresh: ${flyway:timestamp}
DO $$
BEGIN
    IF to_regclass('examaccess.exam_access_pin') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE examaccess.exam_access_pin TO app_txn_examentry';
    END IF;
    IF to_regclass('examaccess.pin_validation_guard') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE examaccess.pin_validation_guard TO app_txn_examentry';
    END IF;
    IF to_regclass('examaccess.candidate_principal') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE examaccess.candidate_principal TO app_txn_examentry';
    END IF;
    IF to_regclass('examaccess.candidate_identity_verification') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON TABLE examaccess.candidate_identity_verification TO app_txn_examentry';
    END IF;
    IF to_regclass('delivery.attempt') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT, INSERT ON TABLE delivery.attempt TO app_txn_examentry';
    END IF;
    IF to_regclass('delivery.attempt_question_evidence') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE delivery.attempt_question_evidence TO app_txn_examentry';
    END IF;
    IF to_regclass('delivery.attempt_presentation') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE delivery.attempt_presentation TO app_txn_examentry';
    END IF;
    IF to_regclass('people.candidate') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE people.candidate TO app_txn_examentry';
    END IF;
    IF to_regclass('people.candidate_assignment') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE people.candidate_assignment TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.exam_session') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.exam_session TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.session_candidate') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.session_candidate TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.assessment') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.assessment TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.assessment_config') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.assessment_config TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.section') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.section TO app_txn_examentry';
    END IF;
    IF to_regclass('authoring.section_item') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE authoring.section_item TO app_txn_examentry';
    END IF;
    IF to_regclass('tenancy.tenant') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON TABLE tenancy.tenant TO app_txn_examentry';
    END IF;
    IF to_regclass('audit.audit_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE audit.audit_event TO app_txn_examentry';
    END IF;
    IF to_regclass('outbox.outbox_event') IS NOT NULL THEN
        EXECUTE 'GRANT INSERT ON TABLE outbox.outbox_event TO app_txn_examentry';
    END IF;
END
$$;
