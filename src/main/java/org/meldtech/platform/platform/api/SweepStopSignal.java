package org.meldtech.platform.platform.api;

/** Read-only, thread-safe view of the runner's request to stop at a batch boundary. */
@FunctionalInterface
public interface SweepStopSignal {

    boolean isStopRequested();
}
