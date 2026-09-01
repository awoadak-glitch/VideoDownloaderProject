pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://raw.githubusercontent.com/mysteriumnetwork/openvpn_dart/master/android/localmaven")
    }
}
rootProject.name = "AWRVPNUltra"
include(":app")
