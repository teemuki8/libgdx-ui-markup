dependencies {
    implementation(libs.jackson.databind)
}

val qualificationProperties = mapOf(
    "markup.qualification.corpus" to layout.projectDirectory.dir("corpus").asFile.absolutePath,
    "markup.qualification.output" to
        layout.buildDirectory.dir("qualification/output").get().asFile.absolutePath,
    "markup.preview.distribution" to project(":libgdx-ui-markup-preview").layout.buildDirectory
        .dir("install/libgdx-ui-markup-preview").get().asFile.absolutePath,
)

tasks.register<JavaExec>("calibrateQualification") {
    group = "qualification"
    description = "Measures every corpus recreation and rewrites manifest thresholds (65% of measured)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.gdx.markup.qualification.CalibrateMain")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    qualificationProperties.forEach { (name, value) -> systemProperty(name, value) }
    dependsOn(project(":libgdx-ui-markup-preview").tasks.named("installDist"))
}

tasks.withType<Test>().configureEach {
    dependsOn(project(":libgdx-ui-markup-preview").tasks.named("installDist"))
    // Recreation edits and manifest threshold changes must re-run the qualification.
    inputs.files(layout.projectDirectory.dir("corpus"))
    qualificationProperties.forEach { (name, value) -> systemProperty(name, value) }
    systemProperty(
        "markup.qualification.strict",
        providers.gradleProperty("strictQualification").orElse("false").get(),
    )
}
