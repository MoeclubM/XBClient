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
rootProject.name = "XBClient"
includeBuild("deps/neto") {
    dependencySubstitution {
        substitute(module("moe.telecom.neto:sdk-android")).using(project(":sdk:android"))
    }
}
include(":apps:android")
