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

rootProject.name = "Mellow"

include(":app")

// ── core ────────────────────────────────────────────────────────────────────
// Pure Kotlin, no Android SDK: the vocabulary every other module speaks.
include(":core:model")
// Tokens, the lighting engine, glass, and every reusable component. Knows nothing
// about tracks or playback.
include(":core:designsystem")
// Where the library comes from: the music already stored on the phone, read a page at a
// time from the system's media index. Also the playlists the user makes out of it.
include(":core:data")
// Media3 service + the controller the UI talks to.
include(":core:playback")

// ── feature ─────────────────────────────────────────────────────────────────
// Feature modules never depend on each other; they meet only in :app.
include(":feature:home")
include(":feature:library")
include(":feature:player")
include(":feature:search")
include(":feature:settings")
