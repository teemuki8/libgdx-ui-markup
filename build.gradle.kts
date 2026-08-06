val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

allprojects {
    group = "io.github.teemuki8"
    version = "0.1.0"
}

subprojects {
    pluginManager.apply("java-library")
    pluginManager.apply("maven-publish")

    dependencies {
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("gdx-ui-markup ${project.name}")
                description.set("Declarative HTML-like XML + CSS builder for libGDX Scene2D UIs")
                url.set("https://github.com/teemuki8/gdx-ui-markup")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
        // Warnings in project code fail the build; dependency warnings stay warnings.
        if (name != "gdx-ui-markup-idea") {
            options.compilerArgs.add("-Werror")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
