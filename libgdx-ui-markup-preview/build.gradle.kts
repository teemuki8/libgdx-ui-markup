plugins {
    application
}

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
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Dev-only launch executes on the build host; macOS hosts need the first-thread flag.
    if (System.getProperty("os.name", "").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}

// The macOS-only -XstartOnFirstThread flag must be selected at RUNTIME by the generated Unix
// launcher (a distribution built on Linux can run on macOS), and must NEVER appear in the
// Windows launcher. The Gradle script template has no conditional option support, so patch
// the generated Unix script right after DEFAULT_JVM_OPTS; the script already sets a
// `darwin` flag from `uname` earlier.
val unixLauncherFile = layout.buildDirectory.file("scripts/libgdx-ui-markup-preview")
tasks.named("startScripts") {
    val macBlock = """
        if [ "${'$'}darwin" = "true" ]; then
            DEFAULT_JVM_OPTS="${'$'}DEFAULT_JVM_OPTS -XstartOnFirstThread"
        fi
    """.trimIndent()
    doLast {
        val marker = "DEFAULT_JVM_OPTS='\"--enable-native-access=ALL-UNNAMED\"'"
        val script = unixLauncherFile.get().asFile
        val patched = script.readText()
        require(patched.contains(marker)) {
            "unexpected start script template; the DEFAULT_JVM_OPTS marker moved: $marker"
        }
        if (!patched.contains("-XstartOnFirstThread")) {
            script.writeText(patched.replace(marker, marker + "\n" + macBlock))
        }
    }
}

tasks.named<Test>("test") {
    // Script-content tests read the generated launchers.
    dependsOn(tasks.named("startScripts"))
    systemProperty("preview.unixScript",
        layout.buildDirectory.file("scripts/libgdx-ui-markup-preview").get().asFile.absolutePath)
    systemProperty("preview.windowsScript",
        layout.buildDirectory.file("scripts/libgdx-ui-markup-preview.bat").get().asFile.absolutePath)
}

tasks.named<Sync>("installDist") {
    from(rootProject.file("samples")) {
        into("samples")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
