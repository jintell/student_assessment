package org.meldtech.platform.outbox.api;

public enum RedriveResult {
    REQUEUED,
    REPUBLISHED,
    ALREADY_PROCESSED,
    NOT_ELIGIBLE
}
