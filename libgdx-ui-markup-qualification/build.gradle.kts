dependencies {
    implementation(libs.jackson.databind)
}

tasks.withType<Test>().configureEach {
    dependsOn(project(":libgdx-ui-markup-preview").tasks.named("installDist"))
    systemProperty(
        "markup.qualification.corpus",
        layout.projectDirectory.dir("corpus").asFile.absolutePath,
    )
    systemProperty(
        "markup.qualification.cache",
        layout.buildDirectory.dir("qualification/reference-images").get().asFile.absolutePath,
    )
    systemProperty(
        "markup.qualification.output",
        layout.buildDirectory.dir("qualification/output").get().asFile.absolutePath,
    )
    systemProperty(
        "markup.preview.distribution",
        project(":libgdx-ui-markup-preview").layout.buildDirectory
            .dir("install/libgdx-ui-markup-preview").get().asFile.absolutePath,
    )
}
