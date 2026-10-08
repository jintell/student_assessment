@org.springframework.modulith.ApplicationModule(
        id = "iam",
        displayName = "Identity and Access",
        allowedDependencies = {"tenancy::api", "shared::api", "shared::kernel", "outbox::api"},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED)
package org.meldtech.platform.iam;
