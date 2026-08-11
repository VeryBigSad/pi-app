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
    }
}

rootProject.name = "pi-mobile"
include(":android:app")
include(":android:core:protocol")
include(":android:core:network")
include(":android:core:voice")
include(":android:core:push")
include(":android:core:model")
include(":android:core:security")
include(":android:core:storage")
include(":android:core:update")
include(":android:terminal")
include(":android:feature:session")
include(":android:feature:agents")
include(":android:feature:settings")
