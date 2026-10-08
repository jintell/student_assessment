package org.meldtech.platform.shared.kernel.audit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface CanonicalValue {

    record ObjectValue(Map<String, CanonicalValue> members) implements CanonicalValue {
        public ObjectValue {
            Objects.requireNonNull(members, "members");
            members.forEach(
                    (key, value) -> {
                        if (key == null || key.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Canonical object keys must not be blank");
                        }
                        Objects.requireNonNull(value, "Canonical object values must not be null");
                    });
            members = Map.copyOf(members);
        }
    }

    record ArrayValue(List<CanonicalValue> values) implements CanonicalValue {
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            values.forEach(
                    value ->
                            Objects.requireNonNull(
                                    value, "Canonical array values must not be null"));
            values = List.copyOf(values);
        }
    }

    record StringValue(String value) implements CanonicalValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record IntegerValue(BigInteger value) implements CanonicalValue {
        public IntegerValue {
            Objects.requireNonNull(value, "value");
        }

        public IntegerValue(long value) {
            this(BigInteger.valueOf(value));
        }
    }

    record DecimalValue(BigDecimal value) implements CanonicalValue {
        public DecimalValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record BooleanValue(boolean value) implements CanonicalValue {}

    record InstantValue(Instant value) implements CanonicalValue {
        public InstantValue {
            Objects.requireNonNull(value, "value");
        }
    }

    enum NullValue implements CanonicalValue {
        INSTANCE
    }
}
