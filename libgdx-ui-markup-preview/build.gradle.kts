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
}

tasks.named<Sync>("installDist") {
    from(rootProject.file("samples")) {
        into("samples")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
