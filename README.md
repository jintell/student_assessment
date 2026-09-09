# Student Assessment Platform (SAP)

Reactive backend foundation for a student assessment and computer-based testing platform. The project is intended to become a single-deployable modular monolith built from clean-architecture vertical slices.

> **Current status:** this repository is an initial Spring Boot scaffold, not a functional assessment API. Architecture ratification is `RATIFIED`, the foundation task list remains open, and the documented Stage 4a gate is reference material rather than an executable CI check. Do not treat planned modules or endpoints as implemented.

## What Exists Today

- A Java 21 and Spring Boot 4 application entry point.
- A Gradle wrapper pinned to Gradle 9.7.1.
- WebFlux, R2DBC, Flyway, OAuth2 resource-server, Actuator, Spring Modulith, Prometheus, and OTLP dependencies.
- A local PostgreSQL service in `compose.yaml`.
- A JUnit smoke test backed by a PostgreSQL Testcontainer.
- Architecture-ratification artifacts and the `FEAT-PLAT-001` foundation task list.

There are currently no business endpoints, domain modules, database migrations, or production-ready security settings.

## Architecture Direction

The approved design direction in the foundation task specification is:

- one Gradle module and one deployable Spring Boot application;
- Spring Modulith boundaries between business capabilities;
- vertical slices as the unit of change;
- a framework-free `domain` layer, slice-owned orchestration and transactions, and adapters under `infra`;
- reactive HTTP and database request paths using WebFlux and R2DBC;
- PostgreSQL as the authoritative data store, with Flyway managing schema evolution over JDBC;
- deny-by-default authorization and explicit policy ownership for every route;
- event-driven cross-module propagation through a transactional outbox.

Twelve bounded-context modules are planned:

`tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`, `examaccess`, `delivery`, `grading`, `result`, `correction`, and `notification`.

The task specification separately defines `shared`, `platform`, `audit`, and `outbox` as platform modules. None of these module packages has been created yet.

The planned slice shape is:

```text
org.meldtech.platform.<module>
├── api
├── domain
├── slice
├── infra
└── migration
```

Within a slice, the intended components are `Endpoint`, `Request`, `Response`, `Policy`, `Handler`, `Queries`, and `SliceTest`. See [`tasks/foundation/baseline/tasks.md`](tasks/foundation/baseline/tasks.md) for the complete design and conformance backlog.

## Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Build | Gradle 9.7.1 with Kotlin DSL |
| Application | Spring Boot 4.1.1 |
| HTTP | Spring WebFlux |
| Data access | Spring Data R2DBC |
| Database | PostgreSQL |
| Migrations | Flyway over JDBC |
| Modularity | Spring Modulith 2.1.1 |
| Security | Spring Security OAuth2 Resource Server |
| Observability | Actuator, Prometheus, and OTLP metrics |
| Testing | JUnit Jupiter and Testcontainers |

The PostgreSQL JDBC driver is present for Flyway. A PostgreSQL R2DBC driver has not yet been added, so reactive persistence is not currently wired.

## Prerequisites

- JDK 21
- A running Docker-compatible daemon
- Network access for the initial Gradle dependency and container-image downloads

Confirm the Java version with:

```bash
java --version
```

Use the checked-in Gradle wrapper for every command. On Windows, replace `./gradlew` with `gradlew.bat`.

## Run Locally

Start Docker, then run:

```bash
./gradlew bootRun
```

Spring Boot discovers `compose.yaml`, starts PostgreSQL, and supplies its connection details. The Compose configuration publishes PostgreSQL on an automatically assigned host port; do not assume it is available on host port `5432`.

To start the application with the test runtime and the Testcontainers configuration instead:

```bash
./gradlew bootTestRun
```

Both development paths use the approved PostgreSQL 17 image and immutable digest declared by `postgresqlImage` in `gradle.properties`. The `verifyPostgresqlBaseline` task checks local, Testcontainers, and CI alignment.

## Build And Test

```bash
# Compile production and test sources without starting Testcontainers
./gradlew compileJava compileTestJava

# Run the test suite; Docker must be running
./gradlew test

# Run one test class
./gradlew test --tests 'org.meldtech.platform.StudentAssessmentApplicationTests'

# Run all checks and assemble the application
./gradlew clean build

# Build the executable JAR
./gradlew bootJar

# Build an OCI image; Docker must be running
./gradlew bootBuildImage
```

The current `StudentAssessmentApplicationTests` test imports `TestcontainersConfiguration`, so even the context smoke test requires Docker. Build artifacts are written under `build/`; the executable JAR is produced under `build/libs/`.

Formatter, Checkstyle, Error Prone, NullAway, coverage, dedicated conformance-test, and documentation-conformance tasks are specified in the foundation backlog but are not configured in the current build.

## Configuration

The committed application configuration currently sets only:

```yaml
spring:
  application:
    name: student-assessment
```

Keep environment-specific database settings, OAuth issuer details, credentials, tokens, and observability endpoints outside source control. Add Flyway migrations under `src/main/resources/db/migration` using names such as `V1__create_assessment_tables.sql`.

## Repository Layout

```text
.
├── build.gradle.kts                         # Dependencies and build configuration
├── settings.gradle.kts                      # Gradle project name
├── compose.yaml                             # Local PostgreSQL service
├── ci/
│   ├── architecture-ratification.json       # Current ratification record
│   ├── architecture-ratification.template.json
│   └── stage-4a.md                          # Reference gate specification
├── src/
│   ├── main/java/org/meldtech/platform/     # Application source
│   ├── main/resources/                      # Runtime config and migrations
│   └── test/java/org/meldtech/platform/     # Tests and Testcontainers setup
└── tasks/foundation/baseline/tasks.md       # FEAT-PLAT-001 source task list
```

## Governance And Task Tracking

[`ci/architecture-ratification.json`](ci/architecture-ratification.json) currently records architecture baseline 1.4 as `PENDING`; its immutable commit, document SHA-256, and countersignature are not populated. Under the foundation task rules, production implementation remains blocked until formal ratification or an approved temporary gate establishes the permitted scope.

[`ci/stage-4a.md`](ci/stage-4a.md) describes the required ratification behavior, including the distinction between governance `BLOCK` outcomes and artifact or baseline `FAIL` outcomes. It explicitly states that its shell example is not an installed executable.

Implementation work is tracked in `tasks/**/tasks.md`:

- `[ ]` means open.
- `[*]` means implemented and verified.
- Task markers must not be changed merely because work started or an issue or pull request was created.
- The local task list and its corresponding GitHub issues must remain synchronized.

See [`.github/copilot-instructions.md`](.github/copilot-instructions.md) for issue and pull-request workflow details, and [`AGENTS.md`](AGENTS.md) for repository-wide engineering conventions.

## Development Principles

- Keep domain code independent of Spring, serialization, database, and infrastructure types.
- Preserve reactive execution across request handling; do not block event-loop threads.
- Keep changes inside the owning feature module and expose cross-module behavior only through explicit APIs or events.
- Add tests with every implementation and use real PostgreSQL behavior when SQL dialect or driver behavior matters.
- Never rewrite an applied Flyway migration; add a new migration instead.
- Keep secrets and environment-specific configuration out of the repository.
