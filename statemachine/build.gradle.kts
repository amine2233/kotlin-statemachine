import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()
    js(IR) {
        nodejs()
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            pom {
                name.set("StateMachine")
                description.set(
                    "A small finite state machine for Kotlin/coroutines, " +
                        "ported from amine2233/StateMachine (Swift).",
                )
                url.set("https://github.com/amine2233/kotlin-statemachine")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("amine2233")
                        name.set("Amine Bensalah")
                    }
                }
                scm {
                    url.set("https://github.com/amine2233/kotlin-statemachine")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/amine2233/kotlin-statemachine")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
