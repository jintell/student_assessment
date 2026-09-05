package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class R1SliceLeafTests {

    private static final String BASE_PACKAGE = "org.meldtech.platform";

    @Test
    void slicePackagesAreDependencyLeaves() {
        List<String> violations = new ArrayList<>();
        Iterable<JavaClass> classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(BASE_PACKAGE);

        for (JavaClass origin : classes) {
            for (var dependency : origin.getDirectDependenciesFromSelf()) {
                Optional<String> targetSlice = sliceRoot(dependency.getTargetClass());
                if (targetSlice.isEmpty()) {
                    continue;
                }
                Optional<String> originSlice = sliceRoot(origin);
                if (!originSlice.equals(targetSlice)) {
                    violations.add(
                            "R1 slice leaf violated: "
                                    + origin.getName()
                                    + " imports "
                                    + dependency.getTargetClass().getName()
                                    + " from slice "
                                    + targetSlice.orElseThrow()
                                    + "; depend on domain or a published api instead.");
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static Optional<String> sliceRoot(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        int sliceMarker = packageName.indexOf(".slice.");
        if (sliceMarker < 0) {
            return Optional.empty();
        }
        int sliceNameStart = sliceMarker + ".slice.".length();
        int sliceNameEnd = packageName.indexOf('.', sliceNameStart);
        return Optional.of(sliceNameEnd < 0 ? packageName : packageName.substring(0, sliceNameEnd));
    }
}
