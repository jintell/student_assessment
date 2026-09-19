package org.meldtech.migrationverify.release;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseManifestClassificationCheckTest {

    private final ReleaseManifestClassificationCheck check =
            new ReleaseManifestClassificationCheck();

    @Test
    void acceptsExactlyOneClassification() {
        assertDoesNotThrow(() -> check.verify("EXPAND", List.of("EXPAND", "EXPAND")));
        assertDoesNotThrow(() -> check.verify("MIGRATE", List.of()));
    }

    @Test
    void rejectsExpandAndContractInOneRelease() {
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> check.verify("EXPAND", List.of("EXPAND", "CONTRACT")));

        assertTrue(failure.getMessage().contains("exactly one"));
        assertTrue(failure.getMessage().contains("EXPAND"));
        assertTrue(failure.getMessage().contains("CONTRACT"));
    }

    @Test
    void rejectsAnAbsentOrUnknownDeclaration() {
        assertThrows(IllegalArgumentException.class, () -> check.verify(null, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> check.verify("ROLLBACK", List.of("ROLLBACK")));
    }
}
