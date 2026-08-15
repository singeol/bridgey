pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google") {
            name = "GoogleMavenFallback"
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Some networks return 404 for every dl.google.com Maven request.
        // Keep the official repository first and use this only for Google artifacts.
        maven("https://maven.aliyun.com/repository/google") {
            name = "GoogleMavenFallback"
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

rootProject.name = "Bridgey"
include(":app", ":core:discovery")
