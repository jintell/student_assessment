@org.springframework.modulith.ApplicationModule(
        id = "examaccess",
        displayName = "Exam Access",
        allowedDependencies = {
            "tenancy::api",
            "iam::api",
            "people::api",
            "authoring::api",
            "delivery::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.examaccess;
