package org.meldtech.platform.shared.kernel.observability;

import org.reactivestreams.Publisher;

@FunctionalInterface
public interface BusinessEventRecorder {

    Publisher<Void> record(BusinessEvent event);
}
