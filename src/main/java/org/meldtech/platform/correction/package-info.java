@org.springframework.modulith.ApplicationModule(
        id = "correction",
        displayName = "Result Correction",
        allowedDependencies = {
            "tenancy::api",
            "iam::api",
            "result::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.correction;
