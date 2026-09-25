package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KernelConformanceRuleRegistrationTests {

    @Test
    void stageFourRegistersEveryKernelRule() {
        Map<String, KernelConformanceRule> rules =
                Arrays.stream(KernelConformanceRule.values())
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        KernelConformanceRule::id, Function.identity()));

        assertEquals(
                Set.of(
                        "actor_context_required",
                        "system_actor_closed",
                        "controlled_time",
                        "exact_decimal"),
                rules.keySet());
        assertTrue(rules.values().stream().allMatch(this::hasCompleteRegistration));
    }

    private boolean hasCompleteRegistration(KernelConformanceRule rule) {
        return !rule.failurePrefix().isBlank()
                && rule.implementationTask().matches("P4\\.(18|19|20)");
    }
}
