package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorCatalogueGeneratorTest {

    @Test
    void platformCatalogueContainsTheCompleteInitialErrorSet() throws Exception {
        ErrorCatalogue catalogue =
                new ErrorCatalogueLoader()
                        .load(Path.of("src", "main", "resources", "error-catalogue.yaml"));

        assertThat(catalogue.problems().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "CBT-PLAT-VALIDATION",
                                "CBT-PLAT-NOT-FOUND",
                                "CBT-PLAT-CONFLICT",
                                "CBT-PLAT-UNAUTHORISED",
                                "CBT-PLAT-FORBIDDEN",
                                "CBT-PLAT-RATE-LIMITED",
                                "CBT-PLAT-IDEMPOTENCY-UNAVAILABLE",
                                "CBT-PLAT-INTERNAL"));
    }

    @Test
    void oneSourceEditPublishesANewCodeToOpenApiAndClientDocumentation(
            @TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("error-catalogue.yaml");
        Files.writeString(
                source,
                """
                catalogueVersion: 1
                problems:
                  CBT-PLAT-CONFLICT:
                    type: https://errors.meld-tech.com/problems/conflict
                    title: Conflict
                    status: 409
                    detail: The request conflicts with current state.
                    extensions: {}
                  CBT-PLAT-INTERNAL:
                    type: https://errors.meld-tech.com/problems/internal
                    title: Unexpected error
                    status: 500
                    detail: The request could not be completed.
                    extensions: {}
                """);
        Path runtime = temporaryDirectory.resolve("runtime.json");
        Path openApi = temporaryDirectory.resolve("openapi.yaml");
        Path documentation = temporaryDirectory.resolve("error-catalogue.md");

        ErrorCatalogueGenerator.generate(source, runtime, openApi, documentation);

        assertThat(Files.readString(runtime)).contains("CBT-PLAT-CONFLICT");
        assertThat(Files.readString(openApi)).contains("CBT-PLAT-CONFLICT");
        assertThat(Files.readString(documentation)).contains("CBT-PLAT-CONFLICT");
    }

    @Test
    void incompleteEntriesFailGeneration(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("incomplete.yaml");
        Files.writeString(
                source,
                """
                catalogueVersion: 1
                problems:
                  CBT-PLAT-INTERNAL:
                    type: https://errors.meld-tech.com/problems/internal
                    title: Unexpected error
                    status: 500
                    extensions: {}
                """);

        assertThatThrownBy(() -> new ErrorCatalogueLoader().load(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing fields")
                .hasMessageContaining("detail");
    }
}
