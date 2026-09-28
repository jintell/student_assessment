package org.meldtech.platform.shared.kernel.r6fixture;

import org.springframework.context.ApplicationContext;

public final class SpringDependentKernel {

    private final ApplicationContext applicationContext;

    public SpringDependentKernel(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public ApplicationContext applicationContext() {
        return applicationContext;
    }
}
