package org.meldtech.platform.shared.kernel.identity;

import java.util.UUID;

@FunctionalInterface
public interface IdGenerator {

    UUID generate();
}
