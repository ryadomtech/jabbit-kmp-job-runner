pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jabbit"
include(":jabbit")
include(":demo:shared")
include(":demo:androidApp")
include(":demo:webApp")
