package org.meldtech.platform.platform.deployment;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface RollbackCompatibilityEvidenceQuery {

    Mono<Boolean> isGreen(String release, String manifestChecksum, String previousImageDigest);
}
