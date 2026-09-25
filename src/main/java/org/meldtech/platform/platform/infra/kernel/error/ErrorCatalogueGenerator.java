package org.meldtech.platform.platform.infra.kernel.error;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ErrorCatalogueGenerator {

    private ErrorCatalogueGenerator() {}

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Expected source, runtime, OpenAPI, and documentation paths");
        }
        generate(
                Path.of(arguments[0]),
                Path.of(arguments[1]),
                Path.of(arguments[2]),
                Path.of(arguments[3]));
    }

    static void generate(Path source, Path runtime, Path openApi, Path documentation)
            throws IOException {
        ErrorCatalogue catalogue = new ErrorCatalogueLoader().load(source);
        ErrorCatalogueArtifacts artifacts = new ErrorCatalogueArtifacts();
        write(runtime, artifacts.runtimeJson(catalogue));
        write(openApi, artifacts.openApi(catalogue));
        write(documentation, artifacts.clientDocumentation(catalogue));
    }

    private static void write(Path output, String content) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, content);
    }
}
