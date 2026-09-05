@org.springframework.modulith.ApplicationModule(
        id = "result",
        displayName = "Results",
        allowedDependencies = {
            "tenancy::api",
            "iam::api",
            "people::api",
            "grading::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.result;
