package org.meldtech.platform.shared.api;

/** Required adversarial operation classes for every tenant-scoped route. */
public enum IsolationOperation {
    READ,
    WRITE,
    ENUMERATE
}
