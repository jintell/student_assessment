package org.meldtech.migrationverify.measure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Stage12DatabaseVerifier {

    public void verify(String pinnedImage, long generatedRowCount, Path report) {
        try (var database = new Stage12Database(pinnedImage, generatedRowCount)) {
            database.start();
            String serverVersion;
            try (var connection =
                            DriverManager.getConnection(
                                    database.jdbcUrl(), database.username(), database.password());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("SHOW server_version_num")) {
                if (!result.next()) {
                    throw new IllegalStateException("PostgreSQL did not report its server version");
                }
                serverVersion = result.getString(1);
            }
            if (!serverVersion.startsWith("17")) {
                throw new IllegalStateException(
                        "Stage 12 requires PostgreSQL 17 but started " + serverVersion);
            }
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(
                    report,
                    "status=PASS\nimage="
                            + pinnedImage
                            + "\nserverVersion="
                            + serverVersion
                            + "\n");
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Stage 12 database verification failed", exception);
        }
    }
}
