pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            content { includeGroup("io.youtrackdb") }
        }
    }
}

rootProject.name = "yaay-ai"

include(":crdt", ":documents", ":desktopApp", ":graph")
