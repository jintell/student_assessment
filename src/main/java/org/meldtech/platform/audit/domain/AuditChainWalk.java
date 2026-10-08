package org.meldtech.platform.audit.domain;

import java.util.Objects;

public final class AuditChainWalk {

    private AuditChainWalk() {}

    public static State begin(AuditHash seed) {
        return new State(1, Objects.requireNonNull(seed, "seed"));
    }

    public static State append(State state, AuditChainRecord record) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(record, "record");
        if (record.sequence() != state.nextSequence()) {
            throw new AuditVerificationMismatch("audit chain sequence is not dense");
        }
        if (!record.previousHash().equals(state.previousHash())) {
            throw new AuditVerificationMismatch("audit chain predecessor does not match");
        }
        AuditHash reproduced =
                AuditHashing.recordHash(state.previousHash(), record.canonicalEvent());
        if (!reproduced.equals(record.recordHash())) {
            throw new AuditVerificationMismatch("audit record hash does not reproduce");
        }
        return new State(Math.addExact(state.nextSequence(), 1), record.recordHash());
    }

    public static void finish(State state, long committedSequence, AuditHash committedHead) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(committedHead, "committedHead");
        if (state.nextSequence() - 1 != committedSequence
                || !state.previousHash().equals(committedHead)) {
            throw new AuditVerificationMismatch("walked chain does not match its committed head");
        }
    }

    public record State(long nextSequence, AuditHash previousHash) {

        public State {
            if (nextSequence <= 0) {
                throw new IllegalArgumentException("nextSequence must be positive");
            }
            Objects.requireNonNull(previousHash, "previousHash");
        }
    }
}
