# Context Module Descriptors

Status: normative design for `FEAT-PLAT-001` (`P2.2`). Inputs: `module-map.md`, `package-taxonomy.md`, and Spring Modulith 2.1.1's `ApplicationModule`/`NamedInterface` API.

Each row below is the complete context-dependency specification for `src/main/java/org/meldtech/platform/<module>/package-info.java`. Platform-module dependencies are added only as defined by `P2.3`.

| Module descriptor | `id` | `displayName` | Context `allowedDependencies` |
|---|---|---|---|
| `tenancy/package-info.java` | `tenancy` | Tenancy | `{}` |
| `iam/package-info.java` | `iam` | Identity and Access | `{ "tenancy::api" }` |
| `academic/package-info.java` | `academic` | Academic Catalogue | `{ "tenancy::api" }` |
| `people/package-info.java` | `people` | People and Candidates | `{ "tenancy::api" }` |
| `questionbank/package-info.java` | `questionbank` | Question Bank | `{ "tenancy::api" }` |
| `authoring/package-info.java` | `authoring` | Assessment Authoring | `{ "tenancy::api", "iam::api", "academic::api", "people::api", "questionbank::api" }` |
| `examaccess/package-info.java` | `examaccess` | Exam Access | `{ "tenancy::api", "iam::api", "people::api", "authoring::api", "delivery::api" }` |
| `delivery/package-info.java` | `delivery` | Exam Delivery | `{ "tenancy::api", "authoring::api" }` |
| `grading/package-info.java` | `grading` | Grading | `{ "tenancy::api", "authoring::api", "delivery::api" }` |
| `result/package-info.java` | `result` | Results | `{ "tenancy::api", "iam::api", "people::api", "grading::api" }` |
| `correction/package-info.java` | `correction` | Result Correction | `{ "tenancy::api", "iam::api", "result::api" }` |
| `notification/package-info.java` | `notification` | Notification | `{ "tenancy::api", "people::api", "examaccess::api", "result::api", "correction::api" }` |

The generated root descriptor uses this exact form, with the row values substituted and the platform dependencies from `P2.3` appended:

```java
@org.springframework.modulith.ApplicationModule(
        id = "<module>",
        displayName = "<displayName>",
        allowedDependencies = { <module::api entries> },
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package org.meldtech.platform.<module>;
```

Only the `api` hierarchy is exported. The implementation creates these three API descriptors per module so the DTO and event subpackages join the same named interface:

```java
@org.springframework.modulith.NamedInterface("api")
package org.meldtech.platform.<module>.api;
```

The identical `@NamedInterface("api")` declaration is applied to `<module>.api.dto` and `<module>.api.event`. No `NamedInterface` annotation is permitted on `domain`, `slice`, `infra`, or `migration`. Root packages contain only their module descriptor, so they expose no unnamed API types.

`allowedDependencies = {}` is intentional for a module with no context dependency; leaving the annotation default would make the module unrestricted. All references use a named interface (`module::api`), never the whole target module.
