-- GENERATED from db/grants/grant-matrix.json; do not edit.
-- source-sha256: 986c214eea47ba9e5f5928e33036a4c31ebbbef6a7b3e307ea25bbde913fb1b4
-- Grant refresh: ${grantRefresh}

ALTER ROLE app_academic WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_api WITH LOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_authoring WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_correction WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_delivery WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_examaccess WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_grading WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_iam WITH NOLOGIN NOCREATEROLE NOINHERIT;

ALTER ROLE app_notification WITH NOLOGIN NOCREATEROLE NOINHERIT;

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

GRANT app_people TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_questionbank TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_result TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT app_tenancy TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA academic TO app_academic;

GRANT USAGE ON SCHEMA academic TO app_academic;

GRANT USAGE ON SCHEMA audit TO app_academic;

GRANT USAGE ON SCHEMA outbox TO app_academic;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA authoring TO app_authoring;

GRANT USAGE ON SCHEMA audit TO app_authoring;

GRANT USAGE ON SCHEMA authoring TO app_authoring;

GRANT USAGE ON SCHEMA outbox TO app_authoring;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA correction TO app_correction;

GRANT USAGE ON SCHEMA audit TO app_correction;

GRANT USAGE ON SCHEMA correction TO app_correction;

GRANT USAGE ON SCHEMA outbox TO app_correction;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA delivery TO app_delivery;

GRANT USAGE ON SCHEMA audit TO app_delivery;

GRANT USAGE ON SCHEMA delivery TO app_delivery;

GRANT USAGE ON SCHEMA outbox TO app_delivery;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA examaccess TO app_examaccess;

GRANT USAGE ON SCHEMA audit TO app_examaccess;

GRANT USAGE ON SCHEMA examaccess TO app_examaccess;

GRANT USAGE ON SCHEMA outbox TO app_examaccess;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA grading TO app_grading;

GRANT USAGE ON SCHEMA audit TO app_grading;

GRANT USAGE ON SCHEMA grading TO app_grading;

GRANT USAGE ON SCHEMA outbox TO app_grading;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA iam TO app_iam;

GRANT USAGE ON SCHEMA audit TO app_iam;

GRANT USAGE ON SCHEMA iam TO app_iam;

GRANT USAGE ON SCHEMA outbox TO app_iam;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA notification TO app_notification;

GRANT USAGE ON SCHEMA audit TO app_notification;

GRANT USAGE ON SCHEMA notification TO app_notification;

GRANT USAGE ON SCHEMA outbox TO app_notification;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA people TO app_people;

GRANT USAGE ON SCHEMA audit TO app_people;

GRANT USAGE ON SCHEMA outbox TO app_people;

GRANT USAGE ON SCHEMA people TO app_people;

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA questionbank TO app_questionbank;

GRANT USAGE ON SCHEMA audit TO app_questionbank;

GRANT USAGE ON SCHEMA outbox TO app_questionbank;

GRANT USAGE ON SCHEMA questionbank TO app_questionbank;

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

GRANT DELETE, INSERT, SELECT, UPDATE ON ALL TABLES IN SCHEMA tenancy TO app_tenancy;

GRANT USAGE ON SCHEMA audit TO app_tenancy;

GRANT USAGE ON SCHEMA outbox TO app_tenancy;

GRANT USAGE ON SCHEMA tenancy TO app_tenancy;

GRANT USAGE ON SCHEMA audit TO app_txn_examentry;

GRANT USAGE ON SCHEMA authoring TO app_txn_examentry;

GRANT USAGE ON SCHEMA delivery TO app_txn_examentry;

GRANT USAGE ON SCHEMA examaccess TO app_txn_examentry;

GRANT USAGE ON SCHEMA outbox TO app_txn_examentry;

GRANT USAGE ON SCHEMA people TO app_txn_examentry;

GRANT USAGE ON SCHEMA tenancy TO app_txn_examentry;

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
