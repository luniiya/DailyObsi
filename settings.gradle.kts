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
        google()
        mavenCentral()
        // Nextcloud's Android-SingleSignOn is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.nextcloud") }
        }
    }
}

rootProject.name = "dailyobsi"
include(":app")
