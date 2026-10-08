@org.springframework.modulith.ApplicationModule(
        id = "audit",
        displayName = "Audit",
        allowedDependencies = {"shared::api", "shared::kernel", "platform::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.audit;
