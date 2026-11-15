plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "com.amine2233.statemachine"
    version = (findProperty("version") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
