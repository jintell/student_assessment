@org.springframework.modulith.ApplicationModule(
        id = "outbox",
        displayName = "Outbox",
        allowedDependencies = {"shared::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.outbox;
