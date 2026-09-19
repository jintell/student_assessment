package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.migrationverify.adapter.profile.YamlLockThresholdLoader;

class LockThresholdPolicyTest {

    private final Set<String> critical = Set.of("delivery.answer");
    private final LockThresholds thresholds =
            new YamlLockThresholdLoader()
                    .load(Path.of("..", "config", "lock-duration-thresholds.yml"));
    private final LockThresholdPolicy policy =
            new LockThresholdPolicy(thresholds, critical::contains);

    @Test
    void appliesExamCriticalWarningAndFailureBoundaries() {
        assertEquals(LockVerdict.PASS, verdict("delivery.answer", "AccessExclusiveLock", 99));
        assertEquals(LockVerdict.WARN, verdict("delivery.answer", "AccessExclusiveLock", 100));
        assertEquals(LockVerdict.FAIL, verdict("delivery.answer", "AccessExclusiveLock", 250));
    }

    @Test
    void appliesTheNonCriticalFailureBoundary() {
        assertEquals(LockVerdict.PASS, verdict("people.person", "AccessExclusiveLock", 1_999));
        assertEquals(LockVerdict.FAIL, verdict("people.person", "AccessExclusiveLock", 2_000));
    }

    @Test
    void reportsTheOnlineCompatibleModeWithoutAThresholdFailure() {
        var result =
                policy.evaluate(
                        new LockHoldMeasurement(
                                1, "delivery.answer", "ShareUpdateExclusiveLock", 20_000));

        assertEquals(LockVerdict.INFO, result.verdict());
        assertEquals("ONLINE_COMPATIBLE", result.policy());
    }

    private LockVerdict verdict(String relation, String mode, long millis) {
        return policy.evaluate(new LockHoldMeasurement(1, relation, mode, millis)).verdict();
    }
}
