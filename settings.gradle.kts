pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        // Needed only for legacy third-party libraries still declared by the bundled commons module.
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.scijava.org/content/repositories/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The three Androbox libraries are bundled as local modules; no JitPack is needed for them.
        // This repository remains only for legacy dependencies declared inside Fossify Commons.
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.scijava.org/content/repositories/public") }
    }
}

rootProject.name = "Kashem"
include(":app")
include(":commons")
include(":mmslib")
include(":indicator-fast-scroll")
