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
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Each group is pinned to the repository that legitimately publishes it.
        //
        // Without content filtering, every repository is asked for every
        // artifact and the first to answer wins. That is the dependency
        // confusion class of supply chain attack: publish something named
        // "dev.rikka.shizuku:api" in a repository the build also trusts, and
        // it may be served instead of the real one.
        //
        // Rationale: context/_shared/security.md
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // exclusiveContent: these groups come from Maven Central and nowhere
        // else, even if another repository claims to have them.
        exclusiveContent {
            forRepository { mavenCentral() }
            filter {
                includeGroupByRegex("dev\\.rikka.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Bulwark"
include(":app")
