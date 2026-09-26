package org.meldtech.platform.platform.infra.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.time.FixedClock;

class UlidCorrelationIdGeneratorTest {

    @Test
    void generatesCanonicalUlidsFromTheControlledClock() {
        UlidCorrelationIdGenerator generator =
                new UlidCorrelationIdGenerator(
                        new FixedClock(Instant.parse("2026-09-25T10:15:30Z")),
                        new ZeroSecureRandom());

        CorrelationId correlationId = generator.generate();

        assertEquals(26, correlationId.toString().length());
        assertTrue(CorrelationId.isValid(correlationId.toString()));
    }

    private static final class ZeroSecureRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
