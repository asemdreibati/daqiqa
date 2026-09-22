pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "daqiqa"

include(
    // Platform: cross-cutting building blocks with no business logic of their own
    ":platform:shared-kernel",
    ":platform:contracts",
    ":platform:messaging",
    ":platform:testing",
    // Bounded contexts (one Gradle module each, public surface = the `api` package)
    ":modules:network",
    ":modules:catalog",
    ":modules:inventory",
    ":modules:promise",
    ":modules:ordering",
    // Deployables
    ":apps:daqiqa-server",
)
