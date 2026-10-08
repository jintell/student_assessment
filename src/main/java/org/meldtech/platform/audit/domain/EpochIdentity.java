package org.meldtech.platform.audit.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;

public record EpochIdentity(RetentionClass retentionClass, YearMonth period) {

    private static final Map<RetentionClass, Integer> CLOSE_ORDER =
            Map.of(
                    RetentionClass.RESULT_CORRECTION_EVIDENCE, 0,
                    RetentionClass.RESULT_PUBLICATION_EVIDENCE, 1,
                    RetentionClass.PIN_SECURITY_EVENT, 2,
                    RetentionClass.GENERAL_AUDIT_EVENT, 3);

    public static final Comparator<EpochIdentity> CANONICAL_ORDER =
            Comparator.comparing(EpochIdentity::period)
                    .thenComparingInt(epoch -> CLOSE_ORDER.get(epoch.retentionClass()));

    public EpochIdentity {
        Objects.requireNonNull(retentionClass, "retentionClass");
        Objects.requireNonNull(period, "period");
    }

    public static EpochIdentity from(RetentionClass retentionClass, Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new EpochIdentity(retentionClass, YearMonth.from(occurredAt.atZone(ZoneOffset.UTC)));
    }
}
