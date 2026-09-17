package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Stage12DatabaseTest {

    @Test
    void sizesSharedMemoryForGeneratedRowsWithinBounds() {
        assertEquals(256L * 1024 * 1024, Stage12Database.requiredSharedMemoryBytes(10_100));
        assertEquals(512L * 1_000_000, Stage12Database.requiredSharedMemoryBytes(1_000_000));
        assertEquals(2048L * 1024 * 1024, Stage12Database.requiredSharedMemoryBytes(10_000_000));
    }

    @Test
    void rejectsMutableOrUnapprovedImagesBeforeContactingDocker() {
        assertThrows(IllegalArgumentException.class, () -> new Stage12Database("postgres:17", 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Stage12Database(
                                "postgresql:17@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675",
                                1));
    }
}
