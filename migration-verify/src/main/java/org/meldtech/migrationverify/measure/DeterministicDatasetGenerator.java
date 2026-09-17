package org.meldtech.migrationverify.measure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.meldtech.migrationverify.core.VolumetricProfile;
import tools.jackson.databind.json.JsonMapper;

public final class DeterministicDatasetGenerator {

    public static final String VERSION = "1";
    private static final Pattern SCALED_ROW_COUNT = Pattern.compile("([1-9][0-9]*) \\* scale");
    private static final String NULL_VALUE = "\\N";

    public DatasetManifest generate(
            Path profilePath,
            VolumetricProfile profile,
            long seed,
            int scale,
            Path outputDirectory) {
        validate(profile, scale);
        try {
            Files.createDirectories(outputDirectory);
            var tableFiles = new ArrayList<DatasetManifest.TableFile>();
            for (VolumetricProfile.TableProfile table : orderedTables(profile)) {
                long rowCount = rowCount(table.rowCount(), scale);
                Path relativePath = Path.of(table.name().replace('.', '/') + ".csv");
                Path output = outputDirectory.resolve(relativePath);
                Files.createDirectories(output.getParent());
                writeTable(output, table, rowCount, seed);
                tableFiles.add(
                        new DatasetManifest.TableFile(
                                table.name(),
                                relativePath.toString().replace('\\', '/'),
                                rowCount,
                                checksum(Files.readAllBytes(output))));
            }

            DatasetManifest manifest =
                    new DatasetManifest(
                            1,
                            VERSION,
                            profile.profileVersion(),
                            checksum(Files.readAllBytes(profilePath)),
                            seed,
                            scale,
                            List.copyOf(tableFiles),
                            bundleChecksum(tableFiles));
            byte[] manifestBytes =
                    JsonMapper.builder()
                            .build()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(manifest);
            Files.write(outputDirectory.resolve("manifest.json"), appendLineFeed(manifestBytes));
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot generate deterministic dataset", exception);
        }
    }

    public void verifyCoverage(VolumetricProfile profile, Set<String> discoveredTables) {
        Set<String> configured = new TreeSet<>();
        profile.tables().forEach(table -> configured.add(table.name()));
        Set<String> missing = new TreeSet<>(discoveredTables);
        missing.removeAll(configured);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing volumetric profile rows: " + missing);
        }
    }

    private static void validate(VolumetricProfile profile, int scale) {
        if (profile.schemaVersion() != 1 || scale < 1) {
            throw new IllegalArgumentException("Unsupported volumetric profile or scale");
        }
        var names = new TreeSet<String>();
        for (VolumetricProfile.TableProfile table : profile.tables()) {
            if (!names.add(table.name())) {
                throw new IllegalArgumentException(
                        "Duplicate volumetric profile row: " + table.name());
            }
            if (!table.expectedToExist() && rowCount(table.rowCount(), scale) != 0) {
                throw new IllegalArgumentException(
                        "A table that does not exist must have zero rows: " + table.name());
            }
        }
    }

    private static List<VolumetricProfile.TableProfile> orderedTables(VolumetricProfile profile) {
        return profile.tables().stream()
                .sorted(
                        Comparator.comparingInt(VolumetricProfile.TableProfile::generationOrder)
                                .thenComparing(VolumetricProfile.TableProfile::name))
                .toList();
    }

    private static long rowCount(String expression, int scale) {
        if ("0".equals(expression)) {
            return 0;
        }
        Matcher matcher = SCALED_ROW_COUNT.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported row-count expression: " + expression);
        }
        return Math.multiplyExact(Long.parseLong(matcher.group(1)), scale);
    }

    private static void writeTable(
            Path output, VolumetricProfile.TableProfile table, long rowCount, long seed)
            throws IOException {
        List<String> columns = table.columns().keySet().stream().sorted().toList();
        var content = new StringBuilder();
        content.append(String.join(",", columns)).append('\n');
        for (long row = 0; row < rowCount; row++) {
            var values = new ArrayList<String>(columns.size());
            for (String column : columns) {
                values.add(
                        csv(value(seed, table.name(), column, table.columns().get(column), row)));
            }
            content.append(String.join(",", values)).append('\n');
        }
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private static String value(long seed, String table, String column, String strategy, long row) {
        if (strategy.startsWith("sequence:")) {
            return Long.toString(Long.parseLong(strategy.substring("sequence:".length())) + row);
        }
        if (strategy.equals("uuid:row")) {
            return deterministicUuid(seed, table, column, row);
        }
        if (strategy.startsWith("uuid:tenant:")) {
            int cardinality = Integer.parseInt(strategy.substring("uuid:tenant:".length()));
            return deterministicUuid(seed, table, column, row % cardinality);
        }
        if (strategy.startsWith("constant:")) {
            return strategy.substring("constant:".length());
        }
        if (strategy.startsWith("integer:0..")) {
            int maximum = Integer.parseInt(strategy.substring("integer:0..".length()));
            return Long.toString(unsignedHash(seed, table, column, row) % (maximum + 1L));
        }
        if (strategy.startsWith("token:cardinality=")) {
            int cardinality = Integer.parseInt(strategy.substring("token:cardinality=".length()));
            return "token-" + unsignedHash(seed, table, column, row) % cardinality;
        }
        if (strategy.equals("token:null-frequency=0.10")) {
            return row % 10 == 0 ? NULL_VALUE : "token-" + unsignedHash(seed, table, column, row);
        }
        if (strategy.startsWith("reference:") && strategy.endsWith(":null-frequency=0.25")) {
            return row % 4 == 0 ? NULL_VALUE : Long.toString(row % 10_000 + 1);
        }
        throw new IllegalArgumentException("Unsupported synthetic strategy: " + strategy);
    }

    private static String deterministicUuid(
            long seed, String table, String column, long discriminator) {
        byte[] hash =
                digest(seed + "\u0000" + table + "\u0000" + column + "\u0000" + discriminator);
        ByteBuffer bytes = ByteBuffer.wrap(hash);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }

    private static long unsignedHash(long seed, String table, String column, long row) {
        long value =
                ByteBuffer.wrap(
                                digest(
                                        seed + "\u0000" + table + "\u0000" + column + "\u0000"
                                                + row))
                        .getLong();
        return value & Long.MAX_VALUE;
    }

    private static String csv(String value) {
        if (NULL_VALUE.equals(value)) {
            return value;
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String bundleChecksum(List<DatasetManifest.TableFile> files) {
        var canonical = new StringBuilder();
        files.stream()
                .sorted(Comparator.comparing(DatasetManifest.TableFile::path))
                .forEach(
                        file ->
                                canonical
                                        .append(file.path())
                                        .append('\0')
                                        .append(file.sha256())
                                        .append('\n'));
        return checksum(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String checksum(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(messageDigest().digest(bytes));
    }

    private static byte[] digest(String value) {
        return messageDigest().digest(value.getBytes(StandardCharsets.UTF_8));
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
}
