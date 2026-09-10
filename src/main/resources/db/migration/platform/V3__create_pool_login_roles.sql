DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_api') THEN
        CREATE ROLE app_api;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_worker') THEN
        CREATE ROLE app_worker;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_pindist') THEN
        CREATE ROLE app_pindist;
    END IF;
END
$$;

ALTER ROLE app_api WITH LOGIN NOINHERIT;
ALTER ROLE app_worker WITH LOGIN NOINHERIT;
ALTER ROLE app_pindist WITH LOGIN NOINHERIT;

GRANT
    app_tenancy,
    app_iam,
    app_academic,
    app_people,
    app_questionbank,
    app_authoring,
    app_examaccess,
    app_delivery,
    app_grading,
    app_result,
    app_correction,
    app_notification,
    app_txn_examentry
TO app_api WITH INHERIT FALSE, SET TRUE;

GRANT
    app_tenancy,
    app_iam,
    app_academic,
    app_people,
    app_questionbank,
    app_authoring,
    app_examaccess,
    app_delivery,
    app_grading,
    app_result,
    app_correction,
    app_notification
TO app_worker WITH INHERIT FALSE, SET TRUE;

GRANT app_examaccess TO app_pindist WITH INHERIT FALSE, SET TRUE;
