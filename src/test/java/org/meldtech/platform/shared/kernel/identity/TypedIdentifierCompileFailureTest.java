package org.meldtech.platform.shared.kernel.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedIdentifierCompileFailureTest {

    @Test
    void candidateIdCannotBePassedWhereTenantIdIsRequired(@TempDir Path output) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK compiler is required for the compile-fail fixture");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path fixture =
                Path.of("src", "test", "resources", "compile-fail", "CandidateAsTenant.java");

        boolean compiled;
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> sources =
                    files.getJavaFileObjectsFromPaths(List.of(fixture));
            compiled =
                    compiler.getTask(
                                    null,
                                    files,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-proc:none",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            output.toString()),
                                    null,
                                    sources)
                            .call();
        }

        String messages =
                diagnostics.getDiagnostics().stream()
                        .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        assertFalse(compiled, "The identifier-confusion fixture unexpectedly compiled");
        assertTrue(messages.contains("CandidateId"), messages);
        assertTrue(messages.contains("TenantId"), messages);
        assertTrue(messages.contains("incompatible types"), messages);
    }
}
