@org.springframework.modulith.ApplicationModule(
        id = "delivery",
        displayName = "Exam Delivery",
        allowedDependencies = {
            "tenancy::api",
            "authoring::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.delivery;
