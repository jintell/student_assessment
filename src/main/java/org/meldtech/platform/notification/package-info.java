@org.springframework.modulith.ApplicationModule(
        id = "notification",
        displayName = "Notification",
        allowedDependencies = {
            "tenancy::api",
            "people::api",
            "examaccess::api",
            "result::api",
            "correction::api",
            "shared::api",
            "audit::api",
            "outbox::api"
        },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.notification;
