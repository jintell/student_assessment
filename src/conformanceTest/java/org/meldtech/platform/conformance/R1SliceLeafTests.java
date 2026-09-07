package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class R1SliceLeafTests {

    @Test
    void slicePackagesAreDependencyLeaves() {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertSlicePackagesAreDependencyLeaves(classes);
    }

    @Test
    void rejectsAnImportFromOutsideTheTargetSlice() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.r1");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertSlicePackagesAreDependencyLeaves(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R1 slice leaf violated:"));
    }

    private static void assertSlicePackagesAreDependencyLeaves(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();

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
