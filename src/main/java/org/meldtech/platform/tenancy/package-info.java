@org.springframework.modulith.ApplicationModule(
        id = "tenancy",
        displayName = "Tenancy",
        allowedDependencies = {"shared::api", "shared::kernel", "outbox::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.tenancy;
