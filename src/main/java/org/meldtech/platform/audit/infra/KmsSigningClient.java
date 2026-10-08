package org.meldtech.platform.audit.infra;

import reactor.core.publisher.Mono;

interface KmsSigningClient {

    Mono<KmsKeyMetadata> describeKey(String keyReference);

    Mono<KmsSignResponse> signDigest(String keyReference, String algorithm, byte[] sha256Digest);
}
