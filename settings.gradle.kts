// settings.gradle.kts
pluginManagement {
    repositories {
        // 🔑  host of the Android-Gradle plugin
        google()
        // Kotlin plugin, OkHttp, etc.
        mavenCentral()
        // Keep the default Gradle Plugin Portal
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Optional but recommended: force every module to use the same repos
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StarAnnotationApp"
include(":app")
