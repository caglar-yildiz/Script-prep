pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Google's copy of Maven Central, used when Maven Central itself does not answer.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Google's copy of Maven Central, used when Maven Central itself does not answer.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "ScriptPrep"
include(":app")
