package org.meldtech.platform.audit.application;

import java.time.Instant;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditCheckpoint;
import org.meldtech.platform.audit.domain.AuditCheckpointTail;
import reactor.core.publisher.Mono;

public interface AuditCheckpointRepository {

    Mono<AuditCheckpointTail> loadVerifiedTail(AuditChainKey chainKey);

    Mono<Instant> trustedSigningTime();

    Mono<Boolean> insert(AuditCheckpoint checkpoint);
}
