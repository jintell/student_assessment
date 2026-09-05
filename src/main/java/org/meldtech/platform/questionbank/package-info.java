@org.springframework.modulith.ApplicationModule(
        id = "questionbank",
        displayName = "Question Bank",
        allowedDependencies = {"tenancy::api", "shared::api", "audit::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.questionbank;
