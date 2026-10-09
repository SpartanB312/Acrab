pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "antares-lite"

include(
    ":antares-lite-core",
    ":antares-lite-analysis",
    ":antares-lite-mcp",
    ":antares-lite-app",
)
