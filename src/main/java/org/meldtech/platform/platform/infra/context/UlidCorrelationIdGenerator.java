package org.meldtech.platform.platform.infra.context;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.springframework.stereotype.Component;

@Component
public final class UlidCorrelationIdGenerator implements CorrelationIdGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final BigInteger MASK = BigInteger.valueOf(31);

    private final Clock clock;
    private final SecureRandom random;

    public UlidCorrelationIdGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UlidCorrelationIdGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public CorrelationId generate() {
        byte[] value = new byte[16];
        long timestamp = timestamp(clock.now());
        for (int index = 5; index >= 0; index--) {
            value[index] = (byte) timestamp;
            timestamp >>>= 8;
        }
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, value, 6, randomness.length);
        return new CorrelationId(encode(value));
    }

    private static long timestamp(Instant instant) {
        long value = instant.toEpochMilli();
        if (value < 0 || value > 0xFFFFFFFFFFFFL) {
            throw new IllegalArgumentException("ULID timestamp is outside the 48-bit range");
        }
        return value;
    }

    private static String encode(byte[] value) {
        BigInteger remaining = new BigInteger(1, value);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            encoded[index] = ENCODING[remaining.and(MASK).intValue()];
            remaining = remaining.shiftRight(5);
        }
        return new String(encoded);
    }
}
