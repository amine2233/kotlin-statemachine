plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":statemachine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.amine2233.statemachine.kiosk.KioskFoodOrderExampleKt")
}
