@org.springframework.modulith.ApplicationModule(
        id = "grading",
        displayName = "Grading",
        allowedDependencies = {
            "tenancy::api",
            "authoring::api",
            "delivery::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.grading;
