@org.springframework.modulith.ApplicationModule(
        id = "academic",
        displayName = "Academic Catalogue",
        allowedDependencies = {"tenancy::api", "shared::api", "audit::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.academic;
