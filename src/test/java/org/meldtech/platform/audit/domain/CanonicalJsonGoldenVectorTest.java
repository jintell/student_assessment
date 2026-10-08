package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.DecimalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.yaml.snakeyaml.Yaml;

class CanonicalJsonGoldenVectorTest {

    private static final Path VECTORS =
            Path.of("config", "audit", "canonical-json-v1-golden-vectors.json");

    @Test
    @SuppressWarnings("unchecked")
    void approvedGoldenVectorsFreezeCanonicalBytesAndDigests()
            throws IOException, NoSuchAlgorithmException {
        Map<String, Object> document = new Yaml().load(Files.readString(VECTORS));
        assertThat(document.get("codec")).isEqualTo("AUDIT_CANONICAL_JSON_V1");
        assertThat(document.get("hashAlgorithmVersion"))
                .isEqualTo((int) CanonicalJsonCodec.HASH_ALGORITHM_VERSION);

        CanonicalJsonCodec codec = new CanonicalJsonCodec();
        List<Map<String, Object>> vectors =
                (List<Map<String, Object>>) Objects.requireNonNull(document.get("vectors"));
        assertThat(vectors).hasSize(4);
        for (Map<String, Object> vector : vectors) {
            String name = (String) Objects.requireNonNull(vector.get("name"));
            byte[] actual = codec.encode(input(name)).bytes();
            assertThat(HexFormat.of().formatHex(actual))
                    .as("canonical bytes for %s", name)
                    .isEqualTo(vector.get("canonicalUtf8Hex"));
            assertThat(
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(actual)))
                    .as("canonical digest for %s", name)
                    .isEqualTo(vector.get("canonicalSha256"));
        }
    }

    private static CanonicalValue input(String name) {
        return switch (name) {
            case "recursive-key-ordering" ->
                    new ObjectValue(Map.of("b", new IntegerValue(2), "a", new IntegerValue(1)));
            case "unicode-nfc" -> new ObjectValue(Map.of("name", new StringValue("Cafe\u0301")));
            case "fixed-utc-microseconds" ->
                    new ObjectValue(
                            Map.of(
                                    "occurred_at",
                                    new InstantValue(
                                            Instant.parse("2026-10-07T03:04:05.123456Z"))));
            case "decimal-null-and-absent" -> decimalNullAndAbsent();
            default -> throw new IllegalArgumentException("Unknown approved vector: " + name);
        };
    }

    private static ObjectValue decimalNullAndAbsent() {
        Map<String, CanonicalValue> values = new LinkedHashMap<>();
        values.put("z", new DecimalValue(new BigDecimal("-0012.3400")));
        values.put("explicit_null", NullValue.INSTANCE);
        values.put("whole_decimal", new DecimalValue(new BigDecimal("1.000")));
        return new ObjectValue(values);
    }
}
