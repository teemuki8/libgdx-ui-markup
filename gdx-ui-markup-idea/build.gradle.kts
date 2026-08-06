plugins {
    kotlin("jvm") version "2.1.0"
}

// The IDEA plugin runs inside IntelliJ's bundled JBR (Java 21+), so this module uses its own
// toolchain and is deliberately excluded from the Java 25 / -Werror project rules above.
kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
