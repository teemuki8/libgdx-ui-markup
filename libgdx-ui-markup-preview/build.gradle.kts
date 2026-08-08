plugins {
    application
}

// The platform command builder (PreviewJvmCommand) is shared source: the preview module
// compiles it from src/shared/java at Java 25 (it ships in the preview distribution), and
// the IDEA module compiles the same file with its own Java 21 toolchain. It must stay
// Java-21-compatible and dependency-free.
sourceSets {
    main {
        java.srcDir("src/shared/java")
    }
}

val previewIsMac = System.getProperty("os.name", "").lowercase().contains("mac")

dependencies {
    implementation(project(":libgdx-ui-markup"))
    implementation(project(":libgdx-ui-markup-harness"))
    implementation(project(":libgdx-ui-markup-runtime"))
    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.harness.core)
    implementation(libs.harness.scene2d)
    implementation(libs.harness.lwjgl3)
    implementation(libs.harness.protocol)
    implementation(libs.harness.mcp)
    implementation(libs.harness.agent.runtime)
    implementation(libs.jackson.databind)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("dev.gdx.markup.preview.PreviewApp")
    // macOS preview/GL children need -XstartOnFirstThread before the classpath/main; the
    // Gradle-launched preview and the installDist scripts are production launch sites, so
    // they carry the same platform flag the centralized builder injects everywhere else.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") +
        if (previewIsMac) listOf("-XstartOnFirstThread") else emptyList()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.named<Sync>("installDist") {
    from(rootProject.file("samples")) {
        into("samples")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
