# Repository Guide

## Scope

These instructions apply to the entire repository.

## Project Overview

- This is a single-module Gradle project named `student-assessment`.
- The application uses Java 21, Spring Boot 4.1.1, and the Gradle 9.7.1 wrapper.
- The base package is `org.meldtech.platform`; `StudentAssessmentApplication` is the Spring Boot entry point and component-scan root.
- The selected runtime stack is reactive: Spring WebFlux and Spring Data R2DBC.
- PostgreSQL is the target database. Flyway handles schema migrations through JDBC, while application data access is intended to use R2DBC.
- Spring Modulith supplies modular-monolith boundaries and runtime insight.
- OAuth2 resource-server security, Actuator, Prometheus, and OTLP metrics dependencies are present.
- The repository is currently a scaffold: there are no domain modules, HTTP endpoints, repositories, or migration scripts yet. Do not describe unimplemented behavior as existing behavior.

## Repository Layout

- `build.gradle.kts`: plugins, Java toolchain, dependency declarations, and JUnit Platform configuration.
- `settings.gradle.kts`: root project name.
- `src/main/java/org/meldtech/platform/`: production code and application bootstrap.
- `src/main/resources/application.yaml`: application configuration.
- `src/main/resources/db/migration/`: Flyway migration location.
- `src/test/java/org/meldtech/platform/`: JUnit tests and development-time Testcontainers bootstrap.
- `compose.yaml`: local PostgreSQL service used by Spring Boot Docker Compose support.
- `gradle/wrapper/`: pinned Gradle wrapper; use it instead of a machine-installed Gradle.

Do not edit or commit generated content under `build/` or `.gradle/`.

## Architecture Guidelines

- Keep code under `org.meldtech.platform` so it remains below the application scan root.
- Organize new business capabilities as cohesive top-level feature packages beneath the base package. Keep a module's domain, application logic, persistence adapters, and web adapters close to that feature instead of creating repository-wide `controller`, `service`, and `repository` buckets.
- Make module APIs explicit and keep implementation details package-private where possible. Avoid reaching into another feature's internals; communicate through exposed APIs or application events.
- Add Spring Modulith verification tests as real modules emerge, especially when introducing cross-module dependencies.
- Preserve the reactive request path. Use `Mono`/`Flux` and reactive Spring APIs, and do not call `block()`, use blocking JDBC access, or perform blocking work on event-loop threads.
- Flyway is deliberately JDBC-based and runs at startup. Runtime request handling should use R2DBC. The current build has the PostgreSQL JDBC driver but no PostgreSQL R2DBC driver; add an appropriate R2DBC driver when implementing reactive database access.
- Put schema changes in `src/main/resources/db/migration` using Flyway names such as `V1__create_assessment_tables.sql`. Never rewrite a migration that may already have been applied; add a new migration.
- Keep credentials, issuer URLs, tokens, and observability endpoints out of source control. Supply environment-specific values through external configuration or profiles.
- Treat security as part of endpoint design. Do not disable resource-server security globally to make a feature or test pass.
- Always follow the clean architecture principles.

## Code Conventions

- Use Java 21 language features only when they improve clarity and fit Spring proxy/serialization requirements.
- Follow the existing Java style: four-space indentation, opening braces on the declaration line, one public top-level type per file, and no wildcard imports.
- Use descriptive class and method names. Keep Spring configuration classes focused and set `proxyBeanMethods = false` when inter-bean method calls are not required.
- Lombok is available for main and test code, but prefer records or ordinary Java when they make contracts clearer; do not hide important invariants in generated methods.
- Format YAML with two-space indentation. Keep Gradle configuration in Kotlin DSL.
- Let Spring Boot dependency management and the Spring Modulith BOM control managed versions. Add explicit dependency versions only when they are not managed or when there is a documented compatibility reason.
- Always follow the clean code principles.
- No formatter, linter, or static-analysis task is configured. Match nearby code and keep formatting-only churn out of focused changes.

## Prerequisites

- JDK 21. Gradle can discover a matching local toolchain.
- A working Docker-compatible daemon for the current integration tests, `bootTestRun`, Docker Compose development services, and OCI image builds.
- Initial dependency resolution and container image pulls require network access.

Both `compose.yaml` and the Testcontainers configuration currently use `postgres:latest`. Keep local and production database versions compatible, and prefer a reviewed pinned tag before relying on version-specific PostgreSQL behavior.

## Build And Run Commands

Run commands from the repository root. On Unix-like systems use `./gradlew`; on Windows use `gradlew.bat`.

```bash
# Show available tasks
./gradlew tasks

# Compile production and test code without running Docker-backed tests
./gradlew compileJava compileTestJava

# Run all verification and assemble artifacts (requires Docker for current tests)
./gradlew clean build

# Build the executable Spring Boot JAR without running tests
./gradlew bootJar

# Run the application; Spring Boot discovers compose.yaml for local PostgreSQL
./gradlew bootRun

# Run through the test-runtime launcher and Testcontainers configuration
./gradlew bootTestRun

# Build an OCI image (requires Docker)
./gradlew bootBuildImage
```

`compose.yaml` publishes PostgreSQL on an automatically assigned host port. Let Spring Boot's service-connection support supply connection details instead of assuming host port `5432`.

## Testing Commands

Tests use JUnit Jupiter through the JUnit Platform.

```bash
# Entire test suite; start Docker first
./gradlew test

# One test class
./gradlew test --tests 'org.meldtech.platform.StudentAssessmentApplicationTests'

# One test method
./gradlew test --tests 'org.meldtech.platform.StudentAssessmentApplicationTests.contextLoads'

# Rerun instead of accepting Gradle's up-to-date result
./gradlew test --rerun-tasks
```

- The current context test imports `TestcontainersConfiguration`, whose `@ServiceConnection` PostgreSQL container supplies database connection details. Consequently, even the smoke test requires Docker.
- Put tests in the same package hierarchy as the production type. Existing tests use package-private test classes and methods.
- Prefer fast unit tests for isolated domain logic. Use `@SpringBootTest` only when the full application context is part of the behavior under test.
- Use focused WebFlux or data tests where a slice is sufficient. Keep container-backed tests for real PostgreSQL behavior, migrations, repository integration, or end-to-end wiring.
- Do not replace PostgreSQL with an in-memory database for persistence tests when SQL dialect or reactive-driver behavior matters.
- Reuse the shared Testcontainers service-connection configuration for full-context integration tests rather than hard-coding container ports or credentials.

## Development Workflow

1. Inspect the affected feature, its tests, configuration, and migrations before editing.
2. Keep changes within the owning feature/module and avoid unrelated refactors.
3. Add or update tests with the implementation. Cover success paths, validation failures, authorization boundaries, and persistence behavior relevant to the change.
4. Run `./gradlew compileJava compileTestJava` for a Docker-free compile check.
5. With Docker running, run targeted tests and then `./gradlew test`; use `./gradlew clean build` for final full verification when practical.
6. Report any verification that could not run, including missing Docker or external configuration, instead of presenting it as successful.

When adding configuration, document required environment variables and provide safe local defaults only where they cannot expose secrets or weaken production security.
