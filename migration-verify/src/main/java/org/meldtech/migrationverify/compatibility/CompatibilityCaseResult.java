package org.meldtech.migrationverify.compatibility;

public record CompatibilityCaseResult(
        String caseId, String relation, CompatibilityExecution execution) {}
