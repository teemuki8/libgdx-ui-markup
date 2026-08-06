dependencies {
    api(project(":gdx-ui-markup"))
    api(libs.agent.runtime.core)
    testImplementation(libs.gdx.backend.lwjgl3)
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
