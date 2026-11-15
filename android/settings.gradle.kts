// Configuración de módulos de Aura Music.
// Arquitectura modular separada por capas (core) y pantallas (feature) para evitar acoplamiento
// y mantener la estabilidad de la aplicación a medida que crezca.
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
    }
}

rootProject.name = "AuraMusic"

include(":app")
include(":core:model")
include(":core:rust")
include(":core:database")
include(":core:importer")
include(":core:downloader")
include(":core:player")
include(":feature:library")
include(":feature:playlists")
include(":feature:player")
include(":feature:settings")
include(":feature:equalizer")
include(":feature:effects")
include(":feature:downloader")
