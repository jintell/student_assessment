import org.gradle.kotlin.dsl.jacoco

plugins {
    java
    application
    jacoco
}

group = "org.meldtech.migrationverify"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.jsqlparser:jsqlparser:5.3")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
    mainClass.set("org.meldtech.migrationverify.adapter.cli.MigrationVerifyApplication")
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    filter {
        isFailOnNoMatchingTests = false
    }
}

val verifyApplicationIsolation =
    tasks.register("verifyApplicationIsolation") {
        description = "Rejects application dependencies and imports in migration-verify."
        group = LifecycleBasePlugin.VERIFICATION_GROUP

        doLast {
            val projectDependencies =
                configurations
                    .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>() }
                    .map { dependency -> dependency.path }
                    .distinct()
            require(projectDependencies.isEmpty()) {
                "migration-verify must not declare project dependencies: $projectDependencies"
            }

            val forbiddenImports =
                fileTree("src") {
                    include("**/*.java")
                }.filter { source ->
                    source.useLines { lines ->
                        lines.any { line ->
                            line.contains("org.meldtech.platform") ||
                                line.contains("com.cbt.platform")
                        }
                    }
                }
            require(forbiddenImports.isEmpty) {
                "migration-verify must not import application packages: " +
                    forbiddenImports.files.joinToString()
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyApplicationIsolation)
}

val analyseReleaseMigrations =
    tasks.register<JavaExec>("analyseReleaseMigrations") {
        description = "Applies the closed migration policy to every release-manifest migration."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        args(
            "analyse-manifest",
            rootProject.layout.projectDirectory
                .file("migration/exam-critical-tables.yaml")
                .asFile.absolutePath,
            rootProject.layout.projectDirectory.asFile.absolutePath,
            rootProject.layout.projectDirectory
                .file("migration/release-manifest-input.json")
                .asFile.absolutePath,
        )
        inputs.file(
            rootProject.layout.projectDirectory.file("migration/exam-critical-tables.yaml"),
        )
        inputs.file(
            rootProject.layout.projectDirectory.file("migration/release-manifest-input.json"),
        )
        inputs.files(
            rootProject.fileTree("src/main/resources/db/migration") {
                include("**/*.sql")
            },
        )
    }

tasks.named("check") {
    dependsOn(analyseReleaseMigrations)
}

tasks.register<JavaExec>("generateReleaseManifest") {
    description = "Generates the checksummed migration release manifest."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        "generate-manifest",
        rootProject.layout.projectDirectory.asFile.absolutePath,
        rootProject.layout.projectDirectory
            .file("migration/release-manifest-input.json")
            .asFile.absolutePath,
        rootProject.layout.buildDirectory
            .file("release/release-manifest.json")
            .get()
            .asFile.absolutePath,
    )
    inputs.file(rootProject.layout.projectDirectory.file("migration/release-manifest-input.json"))
    inputs.files(
        rootProject.fileTree("src/main/resources/db/migration") {
            include("**/*.sql")
        },
    )
    inputs.property(
        "previousImageDigest",
        providers.environmentVariable("CBT_PREVIOUS_IMAGE_DIGEST"),
    )
    outputs.file(rootProject.layout.buildDirectory.file("release/release-manifest.json"))
}

val generateStage12Dataset =
    tasks.register<JavaExec>("generateStage12Dataset") {
        description = "Generates the deterministic CI stage 12 dataset."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        args(
            "generate-dataset",
            rootProject.layout.projectDirectory
                .file("migration/volumetrics.yaml")
                .asFile.absolutePath,
            rootProject.layout.buildDirectory
                .dir("reports/migration-stage-12/dataset")
                .get()
                .asFile.absolutePath,
        )
        inputs.file(rootProject.layout.projectDirectory.file("migration/volumetrics.yaml"))
        outputs.dir(rootProject.layout.buildDirectory.dir("reports/migration-stage-12/dataset"))
    }

tasks.register<JavaExec>("verifyStage12Database") {
    description = "Starts and verifies the pinned CI stage 12 PostgreSQL container."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(generateStage12Dataset)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args(
        "verify-stage12-database",
        providers.gradleProperty("postgresqlImage").get(),
        "10100",
        rootProject.layout.buildDirectory
            .file("reports/migration-stage-12/database-verification.txt")
            .get()
            .asFile
            .absolutePath,
    )
    inputs.property("postgresqlImage", providers.gradleProperty("postgresqlImage"))
    inputs.file(
        rootProject.layout.buildDirectory.file(
            "reports/migration-stage-12/dataset/manifest.json",
        ),
    )
    outputs.file(
        rootProject.layout.buildDirectory.file(
            "reports/migration-stage-12/database-verification.txt",
        ),
    )
}
