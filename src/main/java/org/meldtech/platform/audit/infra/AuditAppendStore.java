package org.meldtech.platform.audit.infra;

import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.platform.api.TransactionalConnection;
import reactor.core.publisher.Mono;

interface AuditAppendStore {

    Mono<LockedChainHead> lockHead(TransactionalConnection connection, AuditChainKey key);

    Mono<Void> appendAndAdvance(
            TransactionalConnection connection,
            LockedChainHead lockedHead,
            PreparedAuditRecord record);
}
