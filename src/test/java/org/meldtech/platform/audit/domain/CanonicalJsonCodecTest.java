package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.DecimalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

class CanonicalJsonCodecTest {

    private final CanonicalJsonCodec codec = new CanonicalJsonCodec();

    @Test
    void canonicalizesValuesWithoutAmbientConfiguration() {
        Map<String, org.meldtech.platform.shared.kernel.audit.CanonicalValue> values =
                new LinkedHashMap<>();
        values.put("z", new DecimalValue(new BigDecimal("-0012.3400")));
        values.put("explicit_null", NullValue.INSTANCE);
        values.put("whole_decimal", new DecimalValue(new BigDecimal("1.000")));
        values.put("integer", new IntegerValue(7));
        values.put("name", new StringValue("Cafe\u0301"));
        values.put("occurred_at", new InstantValue(Instant.parse("2026-10-07T03:04:05.123456Z")));

        String encoded =
                new String(codec.encode(new ObjectValue(values)).bytes(), StandardCharsets.UTF_8);

        assertThat(encoded)
                .isEqualTo(
                        "{\"explicit_null\":null,\"integer\":7,\"name\":\"Caf\u00e9\","
                                + "\"occurred_at\":\"2026-10-07T03:04:05.123456Z\","
                                + "\"whole_decimal\":1.0,\"z\":-12.34}");
    }

    @Test
    void rejectsSubMicrosecondInstants() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                codec.encode(
                                        new InstantValue(
                                                Instant.parse("2026-10-07T03:04:05.123456789Z"))));
    }
}
