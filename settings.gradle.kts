pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/cmp/dev")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // CMP dev builds — needed to probe pre-release Material3 Expressive
        // exposure. The 1.12.0-alpha02+dev412x builds may publish the
        // ExperimentalMaterial3ExpressiveApi opt-in to non-Android targets,
        // which alpha01 does not (it's `internal` in CMP's iOS publication).
        maven("https://packages.jetbrains.team/maven/p/cmp/dev")
        // compottie's only Compose-1.12-compatible build ships as a SNAPSHOT
        // (2.2.1-compose-1.12-SNAPSHOT); stable 2.2.x targets Compose 1.11 and
        // crashes on iOS with our 1.12 skiko. Scoped to compottie's group only:
        // a SNAPSHOT repo is mutable, so don't let it resolve (or poison) any
        // other dependency.
        maven("https://central.sonatype.com/repository/maven-snapshots") {
            content { includeGroup("io.github.alexzhirkevich") }
        }
    }
}

rootProject.name = "Hortay"
include(":androidApp")
include(":shared")
include(":libtdlib")
include(":baselineprofile")

// Tooling subproject — JVM Kotlin codegen that parses TDLib's td_api.tl schema
// and emits the iosArm64Main TdApi.kt mirror of the upstream Java TdApi shape.
// Phase II-C; invoked manually via `./gradlew :tdapi-gen:generate`. Not on the
// production build path — Android still uses the Docker-generated Java TdApi.
include(":tdapi-gen")
project(":tdapi-gen").projectDir = file("scripts/tdapi-gen")
