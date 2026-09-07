package org.meldtech.platform.platform.slice.getConformanceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import org.springframework.transaction.annotation.Transactional;

class ReferenceSliceAnatomyTests {

    private static final Path MAIN_SLICE =
            Path.of("src/main/java/org/meldtech/platform/platform/slice/getConformanceReference");
    private static final Path SLICE_TEST =
            Path.of(
                    "src/test/java/org/meldtech/platform/platform/slice/getConformanceReference/SliceTest.java");

    @Test
    void referenceSliceContainsTheCompleteRequiredAnatomy() throws IOException {
        Set<String> productionFiles;
        try (var files = Files.list(MAIN_SLICE)) {
            productionFiles =
                    files.filter(path -> path.getFileName().toString().endsWith(".java"))
                            .map(path -> path.getFileName().toString())
                            .collect(Collectors.toUnmodifiableSet());
        }

        assertEquals(
                Set.of(
                        "Endpoint.java",
                        "Request.java",
                        "Response.java",
                        "Policy.java",
                        "Handler.java",
                        "Queries.java"),
                productionFiles);
        assertTrue(Files.isRegularFile(SLICE_TEST), "SliceTest.java must accompany the slice");
        assertTrue(Request.class.isRecord(), "Request must be an immutable record");
        assertTrue(Response.class.isRecord(), "Response must be an immutable record");
        assertTrue(PolicyProtectedRoute.class.isAssignableFrom(Endpoint.class));
        assertTrue(SlicePolicy.class.isAssignableFrom(Policy.class));
        assertTrue(TenantScopedQuery.class.isAssignableFrom(Queries.class));
        assertEquals(
                1,
                java.util.Arrays.stream(Handler.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Transactional.class))
                        .count(),
                "Handler must own exactly one transaction boundary");
    }
}
