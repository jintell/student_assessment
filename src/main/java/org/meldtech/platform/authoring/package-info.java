@org.springframework.modulith.ApplicationModule(
        id = "authoring",
        displayName = "Assessment Authoring",
        allowedDependencies = {
            "tenancy::api",
            "iam::api",
            "academic::api",
            "people::api",
            "questionbank::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.authoring;
