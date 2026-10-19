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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Miuix（MIUI 风格 Compose 组件库）与 kyant 的 backdrop/shapes 都在
        // Maven Central 有正式发布，无需 GitHub Packages 的 token，CI 直接可跑。
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }
}

rootProject.name = "HOA"
include(":app")
