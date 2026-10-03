package org.meldtech.platform.shared.kernel.observability;

import java.util.Objects;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;

public interface RequestTelemetry {

    <T> Publisher<T> observe(RequestMetadata metadata, Publisher<T> request);

    record RequestMetadata(
            String module,
            String slice,
            Audience audience,
            Operation operation,
            RouteClass routeClass) {

        private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9]*");
        private static final Pattern SLICE = Pattern.compile("[a-z][A-Za-z0-9]*");

        public RequestMetadata {
            module = requireMatch(module, MODULE, "module");
            slice = requireMatch(slice, SLICE, "slice");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(routeClass, "routeClass");
        }

        public String spanName() {
            return module + "." + slice;
        }

        private static String requireMatch(String value, Pattern pattern, String name) {
            Objects.requireNonNull(value, name);
            if (!pattern.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " has invalid telemetry syntax");
            }
            return value;
        }
    }

    enum Audience {
        CANDIDATE,
        WORKFORCE,
        OPERATOR,
        SYSTEM
    }

    enum Operation {
        READ,
        WRITE
    }

    enum RouteClass {
        STANDARD,
        EXAM_ENTRY,
        GRADING
    }
}
