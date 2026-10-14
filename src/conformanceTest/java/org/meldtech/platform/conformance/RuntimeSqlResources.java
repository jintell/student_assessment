package org.meldtech.platform.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/** Applied data-integrity migrations are immutable; no new resource gets their exemption. */
final class RuntimeSqlResources {
    private static final Map<String, String> LEGACY_INTEGRITY_LOCKS =
            Map.of(
                    "db/migration/audit/V4__create_audit_event_partitions.sql",
                    "b0fa92de33617c55587c1eb8a1e4fd18daec6221a2a06bb20ccfecb61866010d",
                    "db/migration/audit/V6__create_audit_chain_head.sql",
                    "18f44fd55cf77f8e9bf03377126c5d3375a24773979c3effb437256b4c3f20bc",
                    "db/migration/outbox/V8__create_outbox_partition_maintenance.sql",
                    "58d0287ef587ed7da52466277eb1c36ca97a7a47c26112af06263eb27f5dfe4a",
                    "db/migration/outbox/V9__secure_outbox_partitions.sql",
                    "c7b6a256a78f2c48f5d756e43403b3067bdf156333555db71d7f654dfe133214");
    private static final Pattern DYNAMIC_EXECUTE =
            Pattern.compile(
                    "(?i)\\bEXECUTE\\s+(?!ON\\b|FUNCTION\\b|PROCEDURE\\b|'|"
                            + "format\\s*\\(\\s*'(?:CREATE|ALTER|DROP|GRANT|REVOKE)\\b)\\w+");
    private static final Pattern CONCATENATED_EXECUTE =
            Pattern.compile("(?is)\\bEXECUTE\\s+'(?:''|[^'])*'\\s*\\|\\|");

    private RuntimeSqlResources() {}

    static void check(Path root) throws IOException {
        for (String baseline : LEGACY_INTEGRITY_LOCKS.keySet()) {
            check(baseline, Files.readString(root.resolve(baseline)));
        }
        try (var paths = Files.walk(root)) {
            for (Path path :
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".sql"))
                            .sorted()
                            .toList()) {
                check(root.relativize(path).toString().replace('\\', '/'), Files.readString(path));
            }
        }
    }

    static void check(String resource, String sql) {
        String expected = LEGACY_INTEGRITY_LOCKS.get(resource);
        if (expected != null) {
            if (!expected.equals(digest(sql))) {
                throw new AssertionError("RUNTIME_LOCK_BASELINE_CHANGED: " + resource);
            }
            return;
        }
        String normalized =
                sql.replaceAll("(?s)/\\*.*?\\*/|(?m)--[^\\r\\n]*", " ")
                        .replace("'", "")
                        .replaceAll("\\s|\\|\\|", "");
        if (RuntimeSqlBytecode.containsLock(normalized)) {
            throw new AssertionError("RUNTIME_LOCK_SQL_RESOURCE: " + resource);
        }
        if (DYNAMIC_EXECUTE.matcher(sql).find() || CONCATENATED_EXECUTE.matcher(sql).find()) {
            throw new AssertionError("RUNTIME_DYNAMIC_SQL_RESOURCE: " + resource);
        }
    }

    static String digest(String sql) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
