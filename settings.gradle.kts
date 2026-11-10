pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Vendored, checksum-verified test libraries (JUnit 4.13.2, Hamcrest 1.3) so tests run offline.
        maven { url = uri("gradle/offline-repo") }
        google()
        mavenCentral()
    }
}
rootProject.name = "better_airplay"
include(":app")
