@org.springframework.modulith.ApplicationModule(
        id = "platform",
        displayName = "Platform Governance",
        allowedDependencies = {
            "shared::api",
            "shared::kernel",
            "audit::api",
            "outbox::api",
            "shared"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.platform;
