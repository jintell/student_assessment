package org.meldtech.platform.grading.domain.r6fixture;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DuplicateRoundingHelper {

    private DuplicateRoundingHelper() {}

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
