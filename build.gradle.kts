import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    checkstyle
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("net.ltgt.errorprone") version "4.3.0"
    id("com.diffplug.spotless") version "8.0.0"
}

jacoco {
    toolVersion = "0.8.15"
}

group = "org.meldtech.platform"
version = "0.0.1-SNAPSHOT"
description = "cbt-platform"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

val conformanceTestSourceSet = sourceSets.create("conformanceTest")
val integrationTestSourceSet = sourceSets.create("integrationTest")
val compatibilityProbeSourceSet = sourceSets.create("compatibilityProbe")
val kernelCompileClasspath =
    configurations.create("kernelCompileClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

conformanceTestSourceSet.compileClasspath += sourceSets.main.get().output
conformanceTestSourceSet.runtimeClasspath += conformanceTestSourceSet.output + conformanceTestSourceSet.compileClasspath
integrationTestSourceSet.compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
integrationTestSourceSet.runtimeClasspath +=
    integrationTestSourceSet.output + integrationTestSourceSet.compileClasspath
compatibilityProbeSourceSet.compileClasspath +=
    sourceSets.main.get().output + configurations.runtimeClasspath.get()

configurations.named(conformanceTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(conformanceTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val compatibilityProbeJar =
    tasks.register<Jar>("compatibilityProbeJar") {
        description = "Builds the API probe executed inside the retained N-1 image."
        group = LifecycleBasePlugin.BUILD_GROUP
        archiveClassifier.set("compatibility-probe")
        from(compatibilityProbeSourceSet.output)
        dependsOn(tasks.named(compatibilityProbeSourceSet.classesTaskName))
    }

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.42.0")
    errorprone("com.uber.nullaway:nullaway:0.12.10")
    implementation("io.micrometer:context-propagation")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.r2dbc:r2dbc-pool")
    implementation("io.projectreactor:reactor-core-micrometer")
    implementation("com.github.jsqlparser:jsqlparser:5.3")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-insight")
    implementation("org.yaml:snakeyaml")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-r2dbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("io.micrometer:micrometer-tracing-test")
    testImplementation("io.grpc:grpc-netty-shaded")
    testImplementation("io.grpc:grpc-protobuf")
    testImplementation("io.grpc:grpc-stub")
    // The OpenTelemetry BOM does not manage its alpha wire-protocol artifact.
    testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("org.testcontainers:testcontainers-r2dbc")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
    add(
        conformanceTestSourceSet.implementationConfigurationName,
        "com.tngtech.archunit:archunit-junit5:1.4.1",
    )
    add(
        conformanceTestSourceSet.implementationConfigurationName,
        "org.springframework.modulith:spring-modulith-starter-test",
    )
    add(conformanceTestSourceSet.implementationConfigurationName, "org.ow2.asm:asm:9.10.1")
    add(kernelCompileClasspath.name, "org.reactivestreams:reactive-streams")
}

val compileKernelJava =
    tasks.register<JavaCompile>("compileKernelJava") {
        description = "Compiles shared.kernel against its framework-free dependency boundary."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        source(
            fileTree("src/main/java/org/meldtech/platform/shared/kernel") {
                include("**/*.java")
                exclude("**/package-info.java")
            },
        )
        classpath = kernelCompileClasspath
        destinationDirectory.set(layout.buildDirectory.dir("classes/java/kernel-check"))
        options.release.set(21)
    }

tasks.named("compileJava") {
    dependsOn(compileKernelJava)
}

val generatedErrorCatalogueResources =
    layout.buildDirectory.dir("generated/resources/errorCatalogue")

val generateErrorCatalogue =
    tasks.register<JavaExec>("generateErrorCatalogue") {
        description = "Generates runtime, OpenAPI, and client error-catalogue artifacts."
        group = LifecycleBasePlugin.BUILD_GROUP
        dependsOn(tasks.compileJava)
        classpath =
            files(
                sourceSets.main
                    .get()
                    .output.classesDirs,
                configurations.runtimeClasspath,
            )
        mainClass.set(
            "org.meldtech.platform.platform.infra.kernel.error.ErrorCatalogueGenerator",
        )
        val source = layout.projectDirectory.file("src/main/resources/error-catalogue.yaml")
        val runtimeOutput = generatedErrorCatalogueResources.map { it.file("error-catalogue.json") }
        val openApiOutput = layout.buildDirectory.file("generated/openapi/openapi.yaml")
        val documentationOutput =
            layout.buildDirectory.file("generated/docs/error-catalogue.md")
        inputs.file(source)
        outputs.files(runtimeOutput, openApiOutput, documentationOutput)
        args(
            source.asFile.absolutePath,
            runtimeOutput.get().asFile.absolutePath,
            openApiOutput.get().asFile.absolutePath,
            documentationOutput.get().asFile.absolutePath,
        )
    }

sourceSets.main {
    resources.srcDir(generatedErrorCatalogueResources)
}

tasks.processResources {
    dependsOn(generateErrorCatalogue)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        error("NullAway")
        option("NullAway:AnnotatedPackages", "org.meldtech.platform")
    }
}

spotless {
    java {
        target("src/*/java/**/*.java", "migration-verify/src/*/java/**/*.java")
        googleJavaFormat("1.33.0").aosp()
        formatAnnotations()
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "migration-verify/*.gradle.kts")
        ktlint("1.7.1")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "12.1.1"
    configFile = layout.projectDirectory.file("config/checkstyle/checkstyle.xml").asFile
    isIgnoreFailures = false
    maxWarnings = 0
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

val conformanceTest =
    tasks.register<Test>("conformanceTest") {
        description = "Runs architecture and module conformance tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = conformanceTestSourceSet.output.classesDirs
        classpath = conformanceTestSourceSet.runtimeClasspath
        shouldRunAfter(tasks.test)
    }

val sliceTest =
    tasks.register<Test>("sliceTest") {
        description = "Runs tests at vertical-slice handler boundaries."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("**/SliceTest.class")
        shouldRunAfter(tasks.test)
    }

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests against real infrastructure."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        dependsOn(tasks.testClasses, ":migration-verify:generateStage12Dataset")
        shouldRunAfter(tasks.test, sliceTest)
        val payloadCaptureDirectory =
            layout.buildDirectory.dir("reports/integration-event-payloads")
        val auditPayloadCaptureDirectory =
            layout.buildDirectory.dir("reports/integration-audit-payloads")
        useJUnitPlatform {
            excludeTags("audit-daily-chain-verification")
        }
        systemProperty(
            "cbt.event-payload-capture-dir",
            payloadCaptureDirectory.get().asFile.absolutePath,
        )
        systemProperty(
            "cbt.audit-payload-capture-dir",
            auditPayloadCaptureDirectory.get().asFile.absolutePath,
        )
        outputs.dir(payloadCaptureDirectory)
        outputs.dir(auditPayloadCaptureDirectory)
    }

val auditDailyChainVerificationTest =
    tasks.register<Test>("auditDailyChainVerificationTest") {
        description = "Runs the daily open-audit-chain verification gate against PostgreSQL."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        dependsOn(tasks.testClasses, integrationTestSourceSet.classesTaskName)
        useJUnitPlatform {
            includeTags("audit-daily-chain-verification")
        }
        shouldRunAfter(integrationTest)
    }

tasks.register<Test>("stagingAdversarialTest") {
    description = "Runs ARC-VERIFY-024 against an explicitly configured staging database."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    include("**/AdversarialConnectionReuseIntegrationTest.class")
    dependsOn(tasks.testClasses, integrationTestSourceSet.classesTaskName)
    doFirst {
        require(System.getenv("CBT_TARGET_ENVIRONMENT") == "staging") {
            "CBT_TARGET_ENVIRONMENT must be staging"
        }
        require(System.getenv("CBT_STAGING_ADVERSARIAL") == "true") {
            "CBT_STAGING_ADVERSARIAL must be true"
        }
    }
}

val problemDetailAllowlistTest =
    tasks.register<Test>("problemDetailAllowlistTest") {
        description = "Runs the ProblemDetail allowlist and error-response leak tests."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include(
            "**/CorrelationIdLifecycleTest.class",
            "**/FaultInjectionProblemDetailTest.class",
            "**/ProblemDetailAllowlistTest.class",
            "**/ProblemDetailConstructionSiteTest.class",
            "**/ProblemDetailFailureToleranceTest.class",
            "**/ReactiveTerminationProblemDetailTest.class",
            "**/ErrorResponseSecretLeakTest.class",
        )
        dependsOn(tasks.testClasses)
        shouldRunAfter(integrationTest)
    }

val tenantIsolationMatrixTest =
    tasks.register<Test>("tenantIsolationMatrixTest") {
        description = "Generates and verifies complete tenant-isolation route coverage."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("**/TenantIsolationMatrixGateTest.class")
        dependsOn(tasks.testClasses)
        shouldRunAfter(integrationTest)
    }

tasks.register<JavaExec>("generateGrantMatrixMigration") {
    description = "Regenerates the repeatable Flyway grant migration from the canonical matrix."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(
        "org.meldtech.platform.platform.infra.persistence.GrantMatrixMigrationGenerator",
    )
    args(
        layout.projectDirectory.file(
            "src/main/resources/db/migration/platform/R__apply_grant_matrix.sql",
        ),
    )
}

val verifySliceTests =
    tasks.register<Exec>("verifySliceTests") {
        description = "Fails when a production slice has no SliceTest."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/verify-slice-tests")
    }

val verifyEventSchemas =
    tasks.register<JavaExec>("verifyEventSchemas") {
        description = "Fails when registered event records drift from committed JSON Schemas."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.outbox.EventSchemaGenerator")
        args(
            layout.projectDirectory
                .dir("contracts/events")
                .asFile.absolutePath,
            "org.meldtech.platform.platform.infra.outbox.ReferenceEvent",
        )
    }

val eventSchemaCompatibility =
    tasks.register<JavaExec>("eventSchemaCompatibility") {
        description = "Blocks incompatible changes to registered integration-event schemas."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(
            "org.meldtech.platform.platform.infra.outbox.EventSchemaCompatibilityChecker",
        )
        args(
            layout.projectDirectory
                .dir("contracts/events/baseline")
                .asFile.absolutePath,
            layout.projectDirectory
                .dir("contracts/events")
                .asFile.absolutePath,
            layout.projectDirectory
                .file("contracts/events/consumers.yaml")
                .asFile.absolutePath,
            layout.projectDirectory
                .dir("contracts/events/retirements")
                .asFile.absolutePath,
        )
    }

val verifyEventPayloadPolicy =
    tasks.register<JavaExec>("verifyEventPayloadPolicy") {
        description = "Rejects credential-bearing or unjustified event payload fields."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.outbox.EventPayloadPolicyChecker")
        args(
            layout.projectDirectory
                .dir("contracts/events")
                .asFile.absolutePath,
        )
    }

val verifyIdempotencyInventory =
    tasks.register<JavaExec>("verifyIdempotencyInventory") {
        description = "Verifies ownership and proof coverage for idempotent operations."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.outbox.IdempotencyInventoryChecker")
        args(
            layout.projectDirectory
                .file("contracts/idempotency-inventory.yaml")
                .asFile.absolutePath,
            layout.projectDirectory
                .file("contracts/feature-delivery-status.yaml")
                .asFile.absolutePath,
            layout.projectDirectory.asFile.absolutePath,
        )
    }

tasks.named("check") {
    dependsOn("spotlessCheck")
    dependsOn(verifyEventSchemas)
    dependsOn(eventSchemaCompatibility)
    dependsOn(verifyEventPayloadPolicy)
    dependsOn(verifyIdempotencyInventory)
}

val secretScan =
    tasks.register<Exec>("secretScan") {
        description = "Scans Git history and the working tree for committed secrets."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/secret-scan")
    }

val eventPayloadSecretScan =
    tasks.register<JavaExec>("eventPayloadSecretScan") {
        description = "Scans captured integration-event payloads for credential fields."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(integrationTest)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.outbox.EventPayloadLeakScanner")
        args(
            layout.buildDirectory
                .dir("reports/integration-event-payloads")
                .get()
                .asFile.absolutePath,
        )
    }

val auditPayloadLeakScannerSelfTest =
    tasks.register<Test>("auditPayloadLeakScannerSelfTest") {
        description = "Proves the audit-payload scanner rejects credential fields."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("**/AuditPayloadLeakScannerTest.class")
        dependsOn(tasks.testClasses)
    }

val auditPayloadSecretScan =
    tasks.register<JavaExec>("auditPayloadSecretScan") {
        description = "Scans captured audit payloads for credential fields."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(integrationTest, auditPayloadLeakScannerSelfTest)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.audit.AuditPayloadLeakScanner")
        args(
            layout.buildDirectory
                .dir("reports/integration-audit-payloads")
                .get()
                .asFile.absolutePath,
        )
    }

val operationalLogCaptureDirectory =
    layout.buildDirectory.dir("reports/operational-logs")

val operationalLogCaptureTest =
    tasks.register<Test>("operationalLogCaptureTest") {
        description = "Captures structured operational logs for the blocking leak scanner."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("**/OperationalLogCaptureTest.class")
        dependsOn(tasks.testClasses)
        systemProperty(
            "cbt.operational-log-capture-dir",
            operationalLogCaptureDirectory.get().asFile.absolutePath,
        )
        outputs.dir(operationalLogCaptureDirectory)
    }

val operationalLogLeakScannerSelfTest =
    tasks.register<Test>("operationalLogLeakScannerSelfTest") {
        description = "Proves the operational-log scanner rejects secret fields and values."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        include("**/OperationalLogLeakScannerTest.class")
        dependsOn(tasks.testClasses)
    }

val operationalLogSecretScan =
    tasks.register<JavaExec>("operationalLogSecretScan") {
        description = "Scans captured operational logs for forbidden fields and values."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(operationalLogCaptureTest, operationalLogLeakScannerSelfTest)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(
            "org.meldtech.platform.platform.infra.observability.OperationalLogLeakScanner",
        )
        args(
            operationalLogCaptureDirectory.get().asFile.absolutePath,
            layout.projectDirectory
                .file("config/observability/log-leak-markers.txt")
                .asFile.absolutePath,
        )
    }

val workflowSecurityCheck =
    tasks.register<Exec>("workflowSecurityCheck") {
        description = "Verifies action pins, permissions, and pull-request secret isolation."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/verify-workflow-security")
    }

val verifyOutboxRoleReview =
    tasks.register<Exec>("verifyOutboxRoleReview") {
        description = "Verifies the Security-signed outbox least-privilege review."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/verify-outbox-role-review")
    }

tasks.register<Exec>("verifyP03DefinitionOfReady") {
    description = "Verifies the signed FEAT-PLAT-002 ownership and grant-matrix approvals."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine("ci/verify-p03-definition-of-ready")
}

val featPlat003AdditionalDorSelfTest =
    tasks.register<Exec>("featPlat003AdditionalDorSelfTest") {
        description = "Proves the FEAT-PLAT-003 additional DoR gate rejects invalid evidence."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/test-feat-plat-003-additional-dor")
    }

tasks.register<Exec>("verifyFeatPlat003AdditionalDor") {
    description = "Verifies the signed FEAT-PLAT-003 error and idempotency contract approvals."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(featPlat003AdditionalDorSelfTest)
    commandLine("ci/verify-feat-plat-003-additional-dor")
}

val featPlat003Phase0DorSelfTest =
    tasks.register<Exec>("featPlat003Phase0DorSelfTest") {
        description = "Proves the FEAT-PLAT-003 Phase 0 readiness gate rejects invalid evidence."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/test-feat-plat-003-phase-0-dor")
    }

tasks.register<Exec>("verifyFeatPlat003Phase0Dor") {
    description = "Verifies FEAT-PLAT-003 Phase 0 decisions and universal readiness."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(featPlat003Phase0DorSelfTest)
    commandLine("ci/verify-feat-plat-003-phase-0-dor")
}

tasks.register<Exec>("verifyPostgresqlBaseline") {
    description = "Verifies the approved PostgreSQL image baseline and database behavior."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine("ci/verify-postgresql-baseline")
}

tasks.register<Exec>("verifyLockThresholdApproval") {
    description = "Verifies the signed migration lock-duration threshold approvals."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine("ci/verify-lock-threshold-approval")
}

tasks.register<Exec>("verifyClosedDdlAllowlistApproval") {
    description = "Verifies the signed closed DDL allowlist approval."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine("ci/verify-closed-ddl-allowlist-approval")
}

tasks.register<Exec>("verifyDatasetProvenanceApproval") {
    description = "Verifies the signed synthetic dataset-provenance approval."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    commandLine("ci/verify-dataset-provenance-approval")
}

tasks.register("generateReleaseManifest") {
    description = "Generates the checksummed migration release manifest."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(":migration-verify:generateReleaseManifest")
}

val verifyPreviousReleaseImage =
    tasks.register<Exec>("verifyPreviousReleaseImage") {
        description = "Verifies that the manifest's retained N-1 image is resolvable."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn("generateReleaseManifest")
        commandLine("ci/verify-previous-image")
    }

tasks.register("ciStage12") {
    description = "CI stage 12: verifies migration infrastructure and immutable inputs."
    group = "ci"
    dependsOn(
        "verifyLockThresholdApproval",
        "verifyClosedDdlAllowlistApproval",
        "verifyDatasetProvenanceApproval",
        ":migration-verify:check",
        ":migration-verify:generateStage12Dataset",
        ":migration-verify:verifyStage12Database",
        ":migration-verify:verifyStage12Migrations",
        verifyPreviousReleaseImage,
    )
}

tasks.register<Exec>("ciStage1") {
    description = "CI stage 1: records and verifies checkout provenance."
    group = "ci"
    commandLine("ci/verify-checkout-provenance")
}

tasks.register("ciStage2") {
    description = "CI stage 2: compiles the application with strict dependency locks."
    group = "ci"
    dependsOn(
        "classes",
        "testClasses",
        "conformanceTestClasses",
        ":migration-verify:classes",
        ":migration-verify:testClasses",
    )
    doLast {
        require(
            layout.projectDirectory
                .file("gradle.lockfile")
                .asFile.isFile,
        ) {
            "gradle.lockfile is required"
        }
        require(
            layout.projectDirectory
                .file("migration-verify/gradle.lockfile")
                .asFile.isFile,
        ) {
            "migration-verify/gradle.lockfile is required"
        }
    }
}

tasks.register("ciStage3") {
    description = "CI stage 3: runs compiler and source static analysis."
    group = "ci"
    dependsOn(
        "compileJava",
        "compileTestJava",
        "compileConformanceTestJava",
        "compileIntegrationTestJava",
        "spotlessCheck",
        "checkstyleMain",
        "checkstyleTest",
        "checkstyleConformanceTest",
        "checkstyleIntegrationTest",
    )
    dependsOn(secretScan, workflowSecurityCheck, verifyOutboxRoleReview)
}

val stage4aUnitTest =
    tasks.register<Exec>("stage4aUnitTest") {
        description = "Exercises all ordered architecture-ratification gate checks."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/test-stage-4a-unit")
    }

val stage4aSelfTest =
    tasks.register<Exec>("stage4aSelfTest") {
        description = "Runs and retains the eight-case Stage 4a self-test evidence."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/stage-4a-self-test")
    }

val stage4aBypassTest =
    tasks.register<Exec>("stage4aBypassTest") {
        description = "Proves Stage 4a cannot be downgraded or bypassed."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/test-stage-4a-bypass")
    }

tasks.register<Exec>("ciStage4a") {
    description = "CI stage 4a: validates the ratified architecture baseline."
    group = "ci"
    dependsOn(stage4aSelfTest, stage4aBypassTest)
    commandLine("ci/stage-4a")
}

val approvedObservabilityContract =
    layout.projectDirectory.file("ci/dor/FEAT-OBS-001/P0.8-observability-contract-dor.json")

val businessEventCompletenessGate =
    tasks.register<JavaExec>("businessEventCompletenessGate") {
        description = "Asserts the approved six-event MVP telemetry contract is complete."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(
            "org.meldtech.platform.platform.infra.observability.BusinessEventCompletenessGate",
        )
        args(approvedObservabilityContract.asFile.absolutePath)
    }

val metricCardinalityGate =
    tasks.register<JavaExec>("metricCardinalityGate") {
        description = "Rejects metric definitions without bounded, approved labels."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(
            "org.meldtech.platform.platform.infra.observability.MetricCardinalityGate",
        )
        args(
            layout.projectDirectory
                .file("config/observability/metric-cardinality.json")
                .asFile.absolutePath,
            approvedObservabilityContract.asFile.absolutePath,
        )
    }

val queryBudgetGate =
    tasks.register<JavaExec>("queryBudgetGate") {
        description = "Verifies registered Phase 0 routes have coherent query budgets."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.observability.QueryBudgetGate")
        args(
            layout.projectDirectory
                .file("config/observability/query-budgets.json")
                .asFile.absolutePath,
        )
    }

val telemetrySchemaGate =
    tasks.register<JavaExec>("telemetrySchemaGate") {
        description = "Rejects unsafe fields and domain objects at structured logging boundaries."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("org.meldtech.platform.platform.infra.observability.TelemetrySchemaGate")
        args(
            "org.meldtech.platform.platform.infra.observability.StructuredLogEvent",
            layout.projectDirectory
                .file("config/observability/metric-cardinality.json")
                .asFile.absolutePath,
        )
    }

tasks.register("ciStage4") {
    description = "CI stage 4: runs architecture conformance."
    group = "ci"
    dependsOn(conformanceTest, businessEventCompletenessGate, telemetrySchemaGate)
}

tasks.register("ciStage5") {
    description = "CI stage 5: runs unit tests and their coverage gate."
    group = "ci"
    dependsOn("jacocoTestReport", "jacocoTestCoverageVerification", metricCardinalityGate)
}

tasks.register("ciStage7") {
    description = "CI stage 7: runs slice tests and verifies every slice is covered."
    group = "ci"
    dependsOn(sliceTest, verifySliceTests)
}

tasks.register("ciStage8") {
    description = "CI stage 8: runs PostgreSQL integration and daily audit-chain verification."
    group = "ci"
    dependsOn(integrationTest, auditDailyChainVerificationTest, queryBudgetGate)
}

tasks.register("ciStage9") {
    description = "CI stage 9: blocks incompatible event-contract changes."
    group = "ci"
    dependsOn(verifyEventSchemas, eventSchemaCompatibility, verifyEventPayloadPolicy)
}

tasks.register("ciStage10") {
    description =
        "CI stage 10: verifies tenant isolation and secret-free payloads, responses, and logs."
    group = "ci"
    dependsOn(
        problemDetailAllowlistTest,
        tenantIsolationMatrixTest,
        eventPayloadSecretScan,
        auditPayloadSecretScan,
        operationalLogSecretScan,
    )
}

val documentationConformanceSelfTest =
    tasks.register<Exec>("documentationConformanceSelfTest") {
        description = "Proves Stage 13 rejects unqualified review citations."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/test-documentation-conformance")
    }

tasks.register<Exec>("ciStage13") {
    description = "CI stage 13: verifies documentation citation conformance."
    group = "ci"
    dependsOn(documentationConformanceSelfTest)
    commandLine("ci/verify-documentation-conformance")
}
