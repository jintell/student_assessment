package org.meldtech.platform.platform.infra.outbox;

import java.nio.file.Path;
import org.meldtech.platform.platform.infra.security.CapturedJsonPayloadLeakScanner;

public final class EventPayloadLeakScanner {

    private EventPayloadLeakScanner() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: EventPayloadLeakScanner <payload-capture-directory>");
        }
        scan(Path.of(arguments[0]));
    }

    static void scan(Path captureDirectory) {
        CapturedJsonPayloadLeakScanner.scan(captureDirectory, "EVENT_PAYLOAD");
    }
}
