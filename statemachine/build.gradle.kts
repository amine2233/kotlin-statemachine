plugins {
    kotlin("jvm")
    `maven-publish`
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("gpr") {
            // Kotlin's jvm plugin registers a "java" software component;
            // this pulls in the main jar + the sources jar configured above.
            from(components["java"])

            groupId = project.group.toString()
            artifactId = "statemachine"
            version = project.version.toString()

            pom {
                name.set("StateMachine")
                description.set(
                    "A small finite state machine for Kotlin/coroutines, " +
                        "ported from amine2233/StateMachine (Swift)."
                )
                url.set("https://github.com/amine2233/StateMachineKt")
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
                    url.set("https://github.com/amine2233/StateMachineKt")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            // Replace amine2233/StateMachineKt with wherever this repo actually lives.
            url = uri("https://maven.pkg.github.com/amine2233/StateMachineKt")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
