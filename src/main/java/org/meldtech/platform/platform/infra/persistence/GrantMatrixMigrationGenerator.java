package org.meldtech.platform.platform.infra.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class GrantMatrixMigrationGenerator {

    private GrantMatrixMigrationGenerator() {}

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one generated migration output path");
        }
        byte[] source = readMatrixSource();
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        String sql = new GrantMatrixSqlGenerator().generate(matrix, sha256(source));
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, sql);
    }

    static String generateDefault() throws IOException {
        byte[] source = readMatrixSource();
        return new GrantMatrixSqlGenerator()
                .generate(new GrantMatrixLoader().loadDefault(), sha256(source));
    }

    private static byte[] readMatrixSource() throws IOException {
        try (InputStream input =
                Objects.requireNonNull(
                        GrantMatrixMigrationGenerator.class
                                .getClassLoader()
                                .getResourceAsStream(GrantMatrixLoader.RESOURCE),
                        "Missing grant matrix resource")) {
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
