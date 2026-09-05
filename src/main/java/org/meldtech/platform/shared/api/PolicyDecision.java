package org.meldtech.platform.shared.api;

/** Closed authorization outcome; absence and failures are always interpreted as DENY. */
public enum PolicyDecision {
    ALLOW,
    DENY
}
