package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeCandidateCatalogueTest {
    @Test
    void discoversProductionCandidatesFromPackagedJar(@TempDir Path temporary) throws Exception {
        Path classes =
                Path.of(
                        RuntimeCandidateCatalogue.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path jar = temporary.resolve("runtime.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
                var paths = Files.walk(classes)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(classes)) {
                    continue;
                }
                String name = classes.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(Files.isDirectory(path) ? name + "/" : name));
                if (Files.isRegularFile(path)) {
                    Files.copy(path, output);
                }
                output.closeEntry();
            }
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new ArtifactLoader(jar.toUri().toURL(), previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> catalogue = loader.loadClass(RuntimeCandidateCatalogue.class.getName());
            assertThat(catalogue.getClassLoader()).isSameAs(loader);
            assertThat(
                            java.util.Objects.requireNonNull(
                                            loader.getResource(
                                                    catalogue.getName().replace('.', '/')
                                                            + ".class"))
                                    .getProtocol())
                    .isEqualTo("jar");
            var validation = catalogue.getDeclaredMethod("validateProduction");
            validation.setAccessible(true);
            validation.invoke(null);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static final class ArtifactLoader extends URLClassLoader {
        private ArtifactLoader(URL artifact, ClassLoader parent) {
            super(new URL[] {artifact}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && name.startsWith("org.meldtech.platform.")) {
                    try {
                        type = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // The shared kernel lives in a separate compiled artifact.
                    }
                }
                if (type == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }

        @Override
        public @Nullable URL getResource(String name) {
            URL own = name.startsWith("org/meldtech/platform/") ? findResource(name) : null;
            return own == null ? super.getResource(name) : own;
        }
    }
}
