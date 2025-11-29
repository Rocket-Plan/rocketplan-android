pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Local FLIR AARs (drop androidsdk-release.aar and thermalsdk-release.aar into app/libs)
        flatDir {
            dirs(file("app/libs"))
        }
    }
}

rootProject.name = "Rocketplan_android"
include(":app")
 
