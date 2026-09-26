package org.meldtech.platform.shared.kernel.decimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecimalTest {

    @Test
    void usesDecimal128ForIntermediateArithmetic() {
        assertSame(MathContext.DECIMAL128, Decimal.INTERMEDIATE_CONTEXT);
    }

    @Test
    void roundsHalfUpToWholeNumbersIncludingNegativeTies() {
        Map<String, String> cases =
                Map.of(
                        "0.499999", "0",
                        "0.500000", "1",
                        "1.500000", "2",
                        "-0.499999", "0",
                        "-0.500000", "-1",
                        "-1.500000", "-2");

        cases.forEach(
                (input, expected) ->
                        assertEquals(
                                new BigDecimal(expected),
                                Decimal.roundHalfUpToWholeNumber(new BigDecimal(input))));
    }

    @Test
    void validatesRawScoreStorageBoundariesWithoutTruncation() {
        assertEquals(
                new BigDecimal("99999999.9999"),
                Decimal.requireRawScore(new BigDecimal("99999999.9999")));
        assertEquals(
                new BigDecimal("-99999999.9999"),
                Decimal.requireRawScore(new BigDecimal("-99999999.9999")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Decimal.requireRawScore(new BigDecimal("100000000.0000")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Decimal.requireRawScore(new BigDecimal("1.00001")));
    }

    @Test
    void validatesPercentageStorageBoundariesWithoutTruncation() {
        assertEquals(
                new BigDecimal("999.999999"),
                Decimal.requirePercentage(new BigDecimal("999.999999")));
        assertEquals(
                new BigDecimal("-999.999999"),
                Decimal.requirePercentage(new BigDecimal("-999.999999")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Decimal.requirePercentage(new BigDecimal("1000.000000")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Decimal.requirePercentage(new BigDecimal("1.0000001")));
    }
}
