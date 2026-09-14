plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "statemachine-kt"

include(":statemachine", ":sample-kiosk")
