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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "usb-bitperfect"
include(":app")
include(":dac-probe")
include(":decent-usb-audio-driver")
include(":decent-usb-audio-wrapper-media3")
project(":decent-usb-audio-driver").projectDir = file("decent-libs/decent-usb-audio-driver")
project(":decent-usb-audio-wrapper-media3").projectDir = file("decent-libs/decent-usb-audio-wrapper-media3")
