package org.meldtech.platform.platform.slice.getConformanceReference;

import java.util.List;
import java.util.Objects;

record Response(
        String applicationVersion,
        String architectureVersion,
        String architectureCommit,
        int contextModuleCount,
        int platformModuleCount,
        List<String> rules) {

    Response {
        applicationVersion = requireText(applicationVersion, "applicationVersion");
        architectureVersion = requireText(architectureVersion, "architectureVersion");
        architectureCommit = requireText(architectureCommit, "architectureCommit");
        if (contextModuleCount < 1 || platformModuleCount < 1) {
            throw new IllegalArgumentException("module counts must be positive");
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
