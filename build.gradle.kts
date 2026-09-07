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

conformanceTestSourceSet.compileClasspath += sourceSets.main.get().output
conformanceTestSourceSet.runtimeClasspath += conformanceTestSourceSet.output + conformanceTestSourceSet.compileClasspath

configurations.named(conformanceTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(conformanceTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.42.0")
    errorprone("com.uber.nullaway:nullaway:0.12.10")
    implementation("io.micrometer:context-propagation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-insight")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
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
    testImplementation("org.testcontainers:testcontainers-r2dbc")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
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
    add(
        conformanceTestSourceSet.implementationConfigurationName,
        "com.github.jsqlparser:jsqlparser:5.3",
    )
    add(conformanceTestSourceSet.implementationConfigurationName, "org.ow2.asm:asm:9.10.1")
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
        target("src/*/java/**/*.java")
        googleJavaFormat("1.33.0").aosp()
        formatAnnotations()
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
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

val verifySliceTests =
    tasks.register<Exec>("verifySliceTests") {
        description = "Fails when a production slice has no SliceTest."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/verify-slice-tests")
    }

tasks.named("check") {
    dependsOn("spotlessCheck")
}

val secretScan =
    tasks.register<Exec>("secretScan") {
        description = "Scans Git history and the working tree for committed secrets."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/secret-scan")
    }

val workflowSecurityCheck =
    tasks.register<Exec>("workflowSecurityCheck") {
        description = "Verifies action pins, permissions, and pull-request secret isolation."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        commandLine("ci/verify-workflow-security")
    }

tasks.register<Exec>("ciStage1") {
    description = "CI stage 1: records and verifies checkout provenance."
    group = "ci"
    commandLine("ci/verify-checkout-provenance")
}

tasks.register("ciStage2") {
    description = "CI stage 2: compiles the application with strict dependency locks."
    group = "ci"
    dependsOn("classes", "testClasses", "conformanceTestClasses")
    doLast {
        require(
            layout.projectDirectory
                .file("gradle.lockfile")
                .asFile.isFile,
        ) {
            "gradle.lockfile is required"
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
        "spotlessCheck",
        "checkstyleMain",
        "checkstyleTest",
        "checkstyleConformanceTest",
    )
    dependsOn(secretScan, workflowSecurityCheck)
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

tasks.register("ciStage4") {
    description = "CI stage 4: runs architecture conformance."
    group = "ci"
    dependsOn(conformanceTest)
}

tasks.register("ciStage5") {
    description = "CI stage 5: runs unit tests and their coverage gate."
    group = "ci"
    dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
}

tasks.register("ciStage7") {
    description = "CI stage 7: runs slice tests and verifies every slice is covered."
    group = "ci"
    dependsOn(sliceTest, verifySliceTests)
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
