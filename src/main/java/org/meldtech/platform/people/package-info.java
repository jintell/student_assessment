@org.springframework.modulith.ApplicationModule(
        id = "people",
        displayName = "People and Candidates",
        allowedDependencies = {"tenancy::api", "shared::api", "audit::api", "outbox::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.people;
