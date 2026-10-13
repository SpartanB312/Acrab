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

rootProject.name = "acrab"

include(
    ":acrab-core",
    ":acrab-analysis",
    ":acrab-mcp",
    ":acrab-app",
)
