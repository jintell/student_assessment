package org.meldtech.platform.conformance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.Set;

final class RuntimeContainmentRule {
    static final String RUNTIME = "org.meldtech.platform.platform.infra.runtime.";
    private static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    private static final String SCHEDULES = "org.springframework.scheduling.annotation.Schedules";
    static final Set<String> LOCK_ADAPTERS =
            Set.of(RUNTIME + "SessionSweepLock", RUNTIME + "TransactionSweepLock");

    private RuntimeContainmentRule() {}

    static ArchRule rule() {
        return classes()
                .should(
                        new ArchCondition<>(
                                "contain scheduling and advisory locks in their owners") {
                            @Override
                            public void check(JavaClass type, ConditionEvents events) {
                                Set<String> violations = new LinkedHashSet<>();
                                type.getMethods()
                                        .forEach(
                                                method -> {
                                                    if (method.isAnnotatedWith(SCHEDULED)
                                                            || method.isMetaAnnotatedWith(SCHEDULED)
                                                            || method.isAnnotatedWith(SCHEDULES)) {
                                                        violations.add(
                                                                "RUNTIME_INTERVAL: "
                                                                        + method.getFullName()
                                                                        + " uses @Scheduled");
                                                    }
                                                });
                                if (!type.getPackageName()
                                        .equals(RUNTIME.substring(0, RUNTIME.length() - 1))) {
                                    type.getDirectDependenciesFromSelf().stream()
                                            .filter(
                                                    dependency ->
                                                            isLockAdapter(
                                                                    dependency
                                                                            .getTargetClass()
                                                                            .getName()))
                                            .forEach(
                                                    dependency ->
                                                            violations.add(
                                                                    "RUNTIME_LOCK_ADAPTER: "
                                                                            + dependency
                                                                                    .getDescription()));
                                }
                                RuntimeSqlBytecode.inspect(type, violations);
                                violations.forEach(
                                        message ->
                                                events.add(
                                                        SimpleConditionEvent.violated(
                                                                type, message)));
                            }
                        });
    }

    static boolean isLockAdapter(String name) {
        return LOCK_ADAPTERS.stream()
                .anyMatch(owner -> name.equals(owner) || name.startsWith(owner + "$"));
    }
}
