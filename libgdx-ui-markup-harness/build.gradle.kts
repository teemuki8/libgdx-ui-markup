dependencies {
    api(project(":libgdx-ui-markup"))
    api(libs.harness.scene2d)
    // Test-scoped only: the end-to-end test file exercises the published
    // AgentRuntimeObservationSource in-process to pin which correlation statuses are reachable.
    // The published harness artifact stays agent-runtime-free.
    testImplementation(libs.harness.agent.runtime)
    testImplementation(libs.harness.protocol)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(project(":libgdx-ui-markup-preview").tasks.named("installDist"))
    systemProperty(
        "markup.preview.distribution",
        project(":libgdx-ui-markup-preview").layout.buildDirectory
            .dir("install/libgdx-ui-markup-preview").get().asFile.absolutePath,
    )
    systemProperty(
        "markup.samples.dir",
        rootProject.file("samples").absolutePath,
    )
}
