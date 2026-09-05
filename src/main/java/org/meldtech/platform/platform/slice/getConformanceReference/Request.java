package org.meldtech.platform.platform.slice.getConformanceReference;

/** Empty, valid-by-construction input contract; trusted request context is never client input. */
record Request() {

    static final Request INSTANCE = new Request();
}
