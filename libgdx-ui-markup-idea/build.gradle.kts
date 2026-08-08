import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    id("org.jetbrains.intellij.platform") version libs.versions.intellij.platform.get()
}

// The plugin runs inside IntelliJ's bundled JBR (Java 21+), so this module compiles with its own
// toolchain and is deliberately excluded from the Java 25 / -Werror project rules in the root.
kotlin {
    jvmToolchain(21)
}

// PreviewJvmCommand is the single shared platform command builder. The preview module
// compiles the file from libgdx-ui-markup-preview/src/shared/java at Java 25 (it ships in
// the preview distribution), and this module compiles the SAME source with its own Java 21
// toolchain. Kotlin's Java-source stub parsing does not resolve this file reliably, so it is
// compiled here by javac first and the classes are put on this module's compile/runtime
// classpath (they are also packaged into the plugin distribution).
val sharedJava = tasks.register<JavaCompile>("compileSharedJava") {
    source(fileTree(rootProject.file("libgdx-ui-markup-preview/src/shared/java")) {
        include("**/*.java")
    })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("generated/shared-java"))
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    implementation(files(sharedJava.map { it.destinationDirectory }))
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(sharedJava)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Bundle the preview distribution so the plugin can launch it from its install directory.
val preparePreview = tasks.register<Copy>("preparePreview") {
    dependsOn(":libgdx-ui-markup-preview:installDist")
    from(project(":libgdx-ui-markup-preview").layout.buildDirectory
        .dir("install/libgdx-ui-markup-preview"))
    into(layout.buildDirectory.dir("markup-preview"))
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        description = "Live preview and hot reload for libgdx-ui-markup Scene2D markup"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        freeArgs = listOf("-mute", "PluginVerificationResult.NOT_DYNAMIC")
    }
}

tasks {
    prepareSandbox {
        from(preparePreview) {
            into("libgdx-ui-markup-preview")
        }
    }
}

tasks.named("buildPlugin") {
    (this as Zip).from(preparePreview) {
        into("libgdx-ui-markup-preview")
    }
}

// Pure unit tests run without the IDE sandbox (the platform plugin's own test task is IDE-bound).
val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs pure Kotlin unit tests outside the IDE sandbox"
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    dependsOn(preparePreview)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("markup.preview.dist", preparePreview.get().destinationDir.absolutePath)
    useJUnitPlatform()
    doFirst {
        // The intellij platform plugin may leak its IDE classloader into every Test task;
        // unit tests must run on the plain JVM classloader.
        systemProperties.remove("java.system.class.loader")
        jvmArgs.removeAll { it.contains("PathClassLoader") }
    }
}

tasks.named("check") {
    dependsOn(unitTest)
}

tasks.named<Test>("test") {
    enabled = false
}
