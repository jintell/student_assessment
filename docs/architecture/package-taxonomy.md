# Package Taxonomy

Status: normative design for `FEAT-PLAT-001` (`P2.1`). Source: architecture §5.1, adapted only from the document's illustrative `com.cbt.platform` prefix to this repository's component-scan root `org.meldtech.platform`.

Every module uses this package shape:

```text
org.meldtech.platform.<module>
├── api
│   ├── dto
│   └── event
├── domain
│   └── policy
├── slice
│   └── <verbNoun>
├── infra
└── migration
```

| Package | Responsibility | May depend on |
|---|---|---|
| `<module>.api` | Published in-process interfaces and stable marker contracts | `api.dto`, shared API types only |
| `<module>.api.dto` | Immutable record DTOs crossing the module boundary | Shared API types and JDK value types |
| `<module>.api.event` | Versioned integration-event schemas published by the module | Shared API types and JDK value types |
| `<module>.domain` | Aggregates, value objects, invariants, and state machines | JDK and its own `domain.policy`; no framework or adapter types |
| `<module>.domain.policy` | Pure domain decisions | Its own domain value types and JDK only |
| `<module>.slice.<verbNoun>` | One use case's endpoint, request/response, policy, handler, queries, and test counterpart | Its own domain, permitted module APIs, and ports; never another slice |
| `<module>.infra` | Adapters for databases, outbox, audit, caches, providers, and schedulers | Inward contracts and framework APIs |
| `<module>.migration` | Module-owned schema/migration metadata boundary | No business package; migration scripts may touch only the matching schema |

Dependency direction is inward: adapters depend on slice/domain contracts, slices orchestrate domain and published APIs, and domain depends on neither slices nor infrastructure. There are no repository-wide `controller`, `service`, or `repository` packages.

All sixteen module roots use this taxonomy: the twelve context modules fixed by `module-map.md` and the four platform modules fixed by Decision 0001. A module may omit an empty runtime subpackage until implementation needs it, but it may not introduce an alternative layer or export an internal package. Any deviation requires an approved ADR before code is merged.
