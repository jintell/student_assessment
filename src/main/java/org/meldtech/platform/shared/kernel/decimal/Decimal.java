package org.meldtech.platform.shared.kernel.decimal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

public final class Decimal {

    public static final MathContext INTERMEDIATE_CONTEXT = MathContext.DECIMAL128;
    public static final int RAW_SCORE_PRECISION = 12;
    public static final int RAW_SCORE_SCALE = 4;
    public static final int PERCENTAGE_PRECISION = 9;
    public static final int PERCENTAGE_SCALE = 6;

    private Decimal() {}

    public static BigDecimal requireRawScore(BigDecimal value) {
        return requireFits(value, RAW_SCORE_PRECISION, RAW_SCORE_SCALE, "raw score");
    }

    public static BigDecimal requirePercentage(BigDecimal value) {
        return requireFits(value, PERCENTAGE_PRECISION, PERCENTAGE_SCALE, "percentage");
    }

    public static BigDecimal roundHalfUpToWholeNumber(BigDecimal value) {
        return Objects.requireNonNull(value, "value").setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal requireFits(
            BigDecimal value, int precision, int scale, String description) {
        Objects.requireNonNull(value, "value");
        int integerDigits = Math.max(0, value.precision() - value.scale());
        if (value.scale() > scale || integerDigits > precision - scale) {
            throw new IllegalArgumentException(
                    description + " exceeds NUMERIC(" + precision + "," + scale + ")");
        }
        return value;
    }
}
