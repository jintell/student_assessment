package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GrantMatrixMigrationGeneratorTest {

    @Test
    void committedMigrationExactlyMatchesTheCanonicalMatrix() throws IOException {
        try (var input =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("db/migration/platform/R__apply_grant_matrix.sql")) {
            assertThat(input).isNotNull();
            String committed = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(committed).isEqualTo(GrantMatrixMigrationGenerator.generateDefault());
        }
    }
}
