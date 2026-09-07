package org.meldtech.platform.conformance.fixtures.r7;

public final class IllegalCaller {

    private final IllegalCommand command;

    public IllegalCaller(IllegalCommand command) {
        this.command = command;
    }

    public IllegalCommand command() {
        return command;
    }
}
