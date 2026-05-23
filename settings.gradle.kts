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
    }
}

rootProject.name = "Hortay"
include(":androidApp")
include(":shared")
include(":libtdlib")
include(":baselineprofile")
