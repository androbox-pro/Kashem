pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
include(":app", ":commons", ":mmslib", ":indicator-fast-scroll", ":patternlockview", ":recyclerview-fast-scroller")

project(":commons").projectDir = file("local-libs/commons")
project(":mmslib").projectDir = file("local-libs/mmslib")
project(":indicator-fast-scroll").projectDir = file("local-libs/indicator-fast-scroll")
project(":patternlockview").projectDir = file("local-libs/patternlockview")
project(":recyclerview-fast-scroller").projectDir = file("local-libs/recyclerview-fast-scroller")
