package org.meldtech.platform.shared.kernel.context;

@FunctionalInterface
public interface CorrelationIdGenerator {

    CorrelationId generate();
}
