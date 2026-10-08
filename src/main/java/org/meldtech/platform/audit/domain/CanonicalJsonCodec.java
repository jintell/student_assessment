package org.meldtech.platform.audit.domain;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.BooleanValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.DecimalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

public final class CanonicalJsonCodec {

    public static final short HASH_ALGORITHM_VERSION = 1;

    private static final byte[] NULL = "null".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT)
                    .withZone(ZoneOffset.UTC);
    private static final Comparator<byte[]> UNSIGNED_UTF8_ORDER =
            (left, right) -> {
                int sharedLength = Math.min(left.length, right.length);
                for (int index = 0; index < sharedLength; index++) {
                    int comparison =
                            Integer.compare(
                                    Byte.toUnsignedInt(left[index]),
                                    Byte.toUnsignedInt(right[index]));
                    if (comparison != 0) {
                        return comparison;
                    }
                }
                return Integer.compare(left.length, right.length);
            };

    public CanonicalDocument encode(CanonicalValue value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(value, output);
        return new CanonicalDocument(HASH_ALGORITHM_VERSION, output.toByteArray());
    }

    private static void write(CanonicalValue value, ByteArrayOutputStream output) {
        switch (value) {
            case ObjectValue object -> writeObject(object, output);
            case ArrayValue array -> writeArray(array, output);
            case StringValue string -> writeString(string.value(), output);
            case IntegerValue integer -> writeAscii(integer.value().toString(), output);
            case DecimalValue decimal -> writeAscii(canonicalDecimal(decimal.value()), output);
            case BooleanValue bool -> writeAscii(Boolean.toString(bool.value()), output);
            case InstantValue instant -> writeInstant(instant.value(), output);
            case NullValue ignored -> output.writeBytes(NULL);
        }
    }

    private static void writeObject(ObjectValue object, ByteArrayOutputStream output) {
        List<NormalizedMember> members = new ArrayList<>(object.members().size());
        Set<String> normalizedKeys = new HashSet<>();
        for (Map.Entry<String, CanonicalValue> member : object.members().entrySet()) {
            String normalizedKey = normalize(member.getKey());
            if (!normalizedKeys.add(normalizedKey)) {
                throw new IllegalArgumentException(
                        "Object keys collide after Unicode normalization");
            }
            members.add(
                    new NormalizedMember(
                            normalizedKey,
                            normalizedKey.getBytes(StandardCharsets.UTF_8),
                            member.getValue()));
        }
        members.sort((left, right) -> UNSIGNED_UTF8_ORDER.compare(left.utf8Key, right.utf8Key));

        output.write('{');
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                output.write(',');
            }
            NormalizedMember member = members.get(index);
            writeString(member.key, output);
            output.write(':');
            write(member.value, output);
        }
        output.write('}');
    }

    private static void writeArray(ArrayValue array, ByteArrayOutputStream output) {
        output.write('[');
        for (int index = 0; index < array.values().size(); index++) {
            if (index > 0) {
                output.write(',');
            }
            write(array.values().get(index), output);
        }
        output.write(']');
    }

    private static void writeInstant(Instant instant, ByteArrayOutputStream output) {
        if (!instant.truncatedTo(ChronoUnit.MICROS).equals(instant)) {
            throw new IllegalArgumentException("Audit instants must have microsecond precision");
        }
        writeString(INSTANT_FORMAT.format(instant), output);
    }

    private static String canonicalDecimal(BigDecimal value) {
        if (value.signum() == 0) {
            return "0.0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0
                ? stripped.toBigIntegerExact() + ".0"
                : stripped.toPlainString();
    }

    private static void writeString(String value, ByteArrayOutputStream output) {
        String normalized = normalize(value);
        rejectUnpairedSurrogates(normalized);
        output.write('"');
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '"' || codePoint == '\\') {
                output.write('\\');
                output.write(codePoint);
            } else if (codePoint <= 0x1f) {
                writeAscii(String.format(Locale.ROOT, "\\u%04x", codePoint), output);
            } else {
                output.writeBytes(
                        new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
            }
        }
        output.write('"');
    }

    private static void rejectUnpairedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "Unpaired Unicode surrogate is not canonical");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Unpaired Unicode surrogate is not canonical");
            }
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static void writeAscii(String value, ByteArrayOutputStream output) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class NormalizedMember {

        private final String key;
        private final byte[] utf8Key;
        private final CanonicalValue value;

        private NormalizedMember(String key, byte[] utf8Key, CanonicalValue value) {
            this.key = key;
            this.utf8Key = utf8Key;
            this.value = value;
        }
    }
}
