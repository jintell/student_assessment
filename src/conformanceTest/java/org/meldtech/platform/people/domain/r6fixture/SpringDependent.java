package org.meldtech.platform.people.domain.r6fixture;

import org.springframework.context.ApplicationContext;

public final class SpringDependent {

    private final ApplicationContext applicationContext;

    public SpringDependent(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public ApplicationContext applicationContext() {
        return applicationContext;
    }
}
