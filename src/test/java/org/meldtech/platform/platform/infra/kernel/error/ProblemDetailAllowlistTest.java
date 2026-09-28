package org.meldtech.platform.platform.infra.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProblemDetailAllowlistTest {

    private static final Pattern PROBLEM_CODE =
            Pattern.compile("CBT-PLAT-[A-Z0-9]+(?:-[A-Z0-9]+)*");
    private static final Path CATALOGUE =
            Path.of("src", "main", "resources", "error-catalogue.yaml");
    private static final Path PRODUCTION_JAVA = Path.of("src", "main", "java");

    @Test
    void everyEmittableProblemCodeIsDeclaredByTheCatalogue() throws Exception {
        ErrorCatalogue catalogue = new ErrorCatalogueLoader().load(CATALOGUE);

        assertAllCodesAreCatalogued(productionProblemCodes(), catalogue.problems().keySet());
    }

    @Test
    void anEmittableBodyShapeWithoutACatalogueEntryFailsTheGate() {
        Set<String> declared = Set.of("CBT-PLAT-INTERNAL");
        Set<String> emitted = Set.of("CBT-PLAT-INTERNAL", "CBT-PLAT-UNDECLARED-FIXTURE");

        assertThatThrownBy(() -> assertAllCodesAreCatalogued(emitted, declared))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CBT-PLAT-UNDECLARED-FIXTURE");
    }

    @Test
    void generatedProblemSchemasRejectUncataloguedFields() throws Exception {
        ErrorCatalogue catalogue = new ErrorCatalogueLoader().load(CATALOGUE);
        String openApi = new ErrorCatalogueArtifacts().openApi(catalogue);

        assertThat(openApi).contains("additionalProperties: false");
        assertThat(countOccurrences(openApi, "additionalProperties: false"))
                .isEqualTo(catalogue.problems().size());
    }

    private static Set<String> productionProblemCodes() throws IOException {
        Set<String> codes = new HashSet<>();
        try (Stream<Path> files = Files.walk(PRODUCTION_JAVA)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = PROBLEM_CODE.matcher(Files.readString(source));
                while (matcher.find()) {
                    codes.add(matcher.group());
                }
            }
        }
        return codes;
    }

    private static void assertAllCodesAreCatalogued(Set<String> emitted, Set<String> declared) {
        Set<String> unknown = new HashSet<>(emitted);
        unknown.removeAll(declared);
        assertThat(unknown).as("emittable problem codes absent from the catalogue").isEmpty();
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
