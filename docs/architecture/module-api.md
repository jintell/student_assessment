# Published Module API Pattern

Status: normative design for `FEAT-PLAT-001` (`P2.4`). Sources: architecture §§5.1 and 8.3-8.4 (`TransactionalCollaboration`).

## Contract Rules

1. Every published in-process module interface lives in `<module>.api`, extends `shared.api.ModuleApi`, and exposes only immutable records from `<module>.api.dto` or shared typed identifiers.
2. Domain aggregates, domain value objects, persistence entities, R2DBC types, framework request types, and mutable collections never cross the boundary.
3. Every API method is reactive (`Mono` or `Flux`) and is explicitly annotated `@Transactional(propagation = Propagation.MANDATORY)`. Query methods additionally declare `readOnly = true`.
4. `MANDATORY` means the initiating slice handler owns the transaction and connection. A module API never opens, suspends, commits, rolls back, or acquires a second connection.
5. A module API never changes role or tenant context. The initiating transaction must already have installed the permitted module/composite role and tenant context as its first statements.
6. Cancellation and errors propagate to the initiating handler so the one transaction rolls back. No module API catches an error to commit a partial result.

## Marker and Interface Template

```java
package org.meldtech.platform.shared.api;

public interface ModuleApi {
}
```

```java
package org.meldtech.platform.<module>.api;

import org.meldtech.platform.shared.api.ModuleApi;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface <Module>Api extends ModuleApi {

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    Mono<LookupResponse> find(LookupQuery query);

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    Flux<ListItemResponse> findAll(ListQuery query);

    @Transactional(propagation = Propagation.MANDATORY)
    Mono<CommandResponse> execute(CommandRequest request);
}
```

```java
package org.meldtech.platform.<module>.api.dto;

import java.util.UUID;

public record LookupQuery(UUID tenantId, UUID resourceId) {
}
```

```java
package org.meldtech.platform.<module>.api.dto;

import java.util.List;
import java.util.UUID;

public record LookupResponse(UUID resourceId, String state, List<String> facts) {
    public LookupResponse {
        facts = List.copyOf(facts);
    }
}
```

Real contracts use typed shared identifiers rather than raw `UUID`; the raw type above keeps the Phase 2 template independent of the `FEAT-PLAT-003` implementation. Record compact constructors defensively copy collections and enforce contract-level invariants.

## Transaction and Role Guardrail

`allowedDependencies` grants Java contract visibility only. It does not grant schema access. A database-backed cross-module API call is legal only when the transaction's single preinstalled role has the exact required grants. At the ratified baseline, `ADR-023` enumerates only the exam-entry composite role. Any additional synchronous call that needs both caller and callee schemas requires an ADR amendment and grant entry before implementation; it may not be made to work by `REQUIRES_NEW`, a second connection, direct SQL, or mid-transaction role switching.
