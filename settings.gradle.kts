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
        exclusiveContent {
            forRepository { google() }
            filter { includeGroup("com.android.tools.smali") }
        }
    }
}

rootProject.name = "acrab"

include(
    ":acrab-core",
    ":acrab-analysis",
    ":acrab-mcp",
    ":acrab-app",
)
