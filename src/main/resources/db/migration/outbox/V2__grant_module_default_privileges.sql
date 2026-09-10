GRANT USAGE ON SCHEMA outbox TO
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
    app_notification;

ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA outbox
    GRANT INSERT ON TABLES TO
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
        app_notification;
