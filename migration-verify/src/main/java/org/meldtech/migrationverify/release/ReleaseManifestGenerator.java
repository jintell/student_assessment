package org.meldtech.migrationverify.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

public final class ReleaseManifestGenerator {

    private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ReleaseManifestClassificationCheck CLASSIFICATION_CHECK =
            new ReleaseManifestClassificationCheck();

    public ReleaseManifest generate(
            Path repository, Path specificationPath, Path output, String previousImageDigest) {
        requireImageDigest(previousImageDigest);
        try {
            ManifestSpecification specification =
                    JSON.readValue(specificationPath.toFile(), ManifestSpecification.class);
            validate(specification);
            Path canonicalRepository = repository.toRealPath();
            var migrations = new ArrayList<MigrationEntry>();
            for (MigrationSpecification migration : specification.migrations()) {
                Path source = canonicalRepository.resolve(migration.path()).normalize();
                if (!source.startsWith(canonicalRepository) || !Files.isRegularFile(source)) {
                    throw new IllegalArgumentException(
                            "Migration path escapes the repository or is absent: "
                                    + migration.path());
                }
                String checksum = checksum(Files.readAllBytes(source));
                migrations.add(
                        new MigrationEntry(
                                migration.module(),
                                migration.path().replace('\\', '/'),
                                migration.phase(),
                                migration.transactional(),
                                checksum));
            }
            migrations.sort(
                    Comparator.comparing(MigrationEntry::module)
                            .thenComparing(MigrationEntry::path));
            ReleaseManifest manifest =
                    new ReleaseManifest(
                            1,
                            specification.release(),
                            specification.classification(),
                            previousImageDigest,
                            migrationSetChecksum(migrations),
                            List.copyOf(migrations));
            Files.createDirectories(output.toAbsolutePath().getParent());
            byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            Files.write(output, appendLineFeed(json));
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate release manifest", exception);
        }
    }

    private static void validate(ManifestSpecification specification) {
        if (specification.release() == null || specification.release().isBlank()) {
            throw new IllegalArgumentException("Release identifier is required");
        }
        if (specification.migrations() == null) {
            throw new IllegalArgumentException("Migration list is required");
        }
        CLASSIFICATION_CHECK.verify(
                specification.classification(),
                specification.migrations().stream().map(MigrationSpecification::phase).toList());
        var paths = new HashSet<String>();
        for (MigrationSpecification migration : specification.migrations()) {
            if (migration.module() == null || migration.module().isBlank()) {
                throw new IllegalArgumentException("Migration module is required");
            }
            if (migration.path() == null
                    || migration.path().isBlank()
                    || !paths.add(migration.path())) {
                throw new IllegalArgumentException("Migration path is absent or duplicated");
            }
        }
    }

    private static void requireImageDigest(String value) {
        if (value == null || !IMAGE_DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "CBT_PREVIOUS_IMAGE_DIGEST must be an immutable OCI sha256 digest");
        }
    }

    private static String migrationSetChecksum(List<MigrationEntry> migrations) {
        MessageDigest digest = messageDigest();
        for (MigrationEntry migration : migrations) {
            digest.update(migration.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(
                    HexFormat.of().parseHex(migration.sha256().substring("sha256:".length())));
            digest.update((byte) '\n');
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static String checksum(byte[] value) {
        return "sha256:" + HexFormat.of().formatHex(messageDigest().digest(value));
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static byte[] appendLineFeed(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        result[value.length] = '\n';
        return result;
    }

    public record ManifestSpecification(
            String release, String classification, List<MigrationSpecification> migrations) {}

    public record MigrationSpecification(
            String module, String path, String phase, boolean transactional) {}

    public record ReleaseManifest(
            int schemaVersion,
            String release,
            String classification,
            String previousImageDigest,
            String migrationSetChecksum,
            List<MigrationEntry> migrations) {}

    public record MigrationEntry(
            String module, String path, String phase, boolean transactional, String sha256) {}
}
