rootProject.name = "AutoRunner"

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
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// AutoRunner — Kotlin Multiplatform module graph
//
//   autorunner-app     : Android application (services, overlay, manifest)
//   autorunner-ui      : shared Compose Multiplatform UI built on MIUIX
//   autorunner-gamepad : optional Bluetooth HID gamepad simulation
//   autorunner-core    : platform-agnostic script model / engine / recording
// ---------------------------------------------------------------------------
include(":autorunner-core")
include(":autorunner-gamepad")
include(":autorunner-ui")
include(":autorunner-app")
