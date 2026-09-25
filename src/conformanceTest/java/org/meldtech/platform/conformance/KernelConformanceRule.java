package org.meldtech.platform.conformance;

enum KernelConformanceRule {
    ACTOR_CONTEXT_REQUIRED("actor_context_required", "ACTOR-CONTEXT:", "P4.18"),
    SYSTEM_ACTOR_CLOSED("system_actor_closed", "SYSTEM-ACTOR:", "P4.18"),
    CONTROLLED_TIME("controlled_time", "R6 controlled time violated:", "P4.19"),
    EXACT_DECIMAL("exact_decimal", "R6 exact decimal violated:", "P4.20");

    private final String id;
    private final String failurePrefix;
    private final String implementationTask;

    KernelConformanceRule(String id, String failurePrefix, String implementationTask) {
        this.id = id;
        this.failurePrefix = failurePrefix;
        this.implementationTask = implementationTask;
    }

    String id() {
        return id;
    }

    String failurePrefix() {
        return failurePrefix;
    }

    String implementationTask() {
        return implementationTask;
    }
}
