import org.gradle.plugins.signing.SigningExtension
import java.util.zip.ZipFile

val mavenGroup = "io.github.teemuki8"
val mavenGroupPath = mavenGroup.replace('.', '/')
val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.2.0-SNAPSHOT")
val repositoryUrl = providers.gradleProperty("repositoryUrl")
    .orElse("https://github.com/teemuki8/libgdx-ui-markup")
val releaseBuild = providers.gradleProperty("release").map(String::toBoolean).orElse(false)
val publishableModules = listOf(
    "libgdx-ui-markup",
    "libgdx-ui-markup-runtime",
    "libgdx-ui-markup-harness",
)
val junitJupiter = libs.junit.jupiter
val junitPlatformLauncher = libs.junit.platform.launcher

allprojects {
    group = mavenGroup
    version = releaseVersion.get()
}

subprojects {
    if (name == "libgdx-ui-markup-idea") {
        // The IDEA plugin runs on IntelliJ's JBR (Java 21+) and manages its own toolchain.
        return@subprojects
    }

    pluginManager.apply("java-library")

    dependencies {
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }

    if (name in publishableModules) {
        pluginManager.apply("maven-publish")
        pluginManager.apply("signing")

        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
                rename { "LICENSE" }
            }
        }

        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("mavenJava") {
                from(components["java"])
                pom {
                    name.set("libgdx-ui-markup ${project.name}")
                    description.set("Declarative HTML-like XML + CSS builder for libGDX Scene2D UIs")
                    url.set(repositoryUrl)
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("teemuki8")
                            name.set("Teemu Jääskeläinen")
                            url.set("https://github.com/teemuki8")
                        }
                    }
                    scm {
                        connection.set("scm:git:${repositoryUrl.get()}.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/teemuki8/libgdx-ui-markup.git")
                        url.set(repositoryUrl)
                    }
                }
            }
            repositories.maven {
                name = "centralStaging"
                url = rootProject.layout.buildDirectory.dir("central-staging")
                    .get().asFile.toURI()
            }
        }

        val publishing = extensions.getByType<PublishingExtension>()
        extensions.configure<SigningExtension> {
            val signingKey = providers.environmentVariable("MAVEN_SIGNING_KEY")
            val signingPassword = providers.environmentVariable("MAVEN_SIGNING_PASSWORD")
            if (signingKey.isPresent && signingPassword.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
            }
            isRequired = releaseBuild.get()
            sign(publishing.publications["mavenJava"])
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:all")
        // Warnings in project code fail the build; dependency warnings stay warnings.
        options.compilerArgs.add("-Werror")
    }

    tasks.withType<Javadoc>().configureEach {
        isFailOnError = true
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xmaxwarns", "1000")
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Werror", true)
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

tasks.register("javadoc") {
    group = "documentation"
    description = "Generates warning-free Javadocs for all published modules"
    dependsOn(publishableModules.map { project(":$it").tasks.named("javadoc") })
}

val verifyPublishedLicenseFiles = tasks.register("verifyPublishedLicenseFiles") {
    group = "verification"
    description = "Verifies every published JAR carries the repository license"
    val archiveTasks = publishableModules.flatMap { moduleName ->
        listOf("jar", "sourcesJar", "javadocJar").map { taskName ->
            project(":$moduleName").tasks.named<Jar>(taskName)
        }
    }
    dependsOn(archiveTasks)
    doLast {
        val expectedLicense = rootProject.file("LICENSE").readBytes()
        for (archiveTask in archiveTasks) {
            val archive = archiveTask.get().archiveFile.get().asFile
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("META-INF/LICENSE")
                    ?: throw GradleException("Missing META-INF/LICENSE: $archive")
                val actualLicense = zip.getInputStream(entry).use { it.readBytes() }
                if (!actualLicense.contentEquals(expectedLicense)) {
                    throw GradleException("Incorrect META-INF/LICENSE: $archive")
                }
            }
        }
    }
}

for (moduleName in publishableModules) {
    project(":$moduleName").tasks.named("check") {
        dependsOn(verifyPublishedLicenseFiles)
    }
}

val verifyReleaseConfiguration = tasks.register("verifyReleaseConfiguration") {
    group = "publishing"
    description = "Fails closed unless release version, Central credentials, and PGP secrets exist"
    doLast {
        val versionText = releaseVersion.get()
        val semanticVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        if (!semanticVersion.matches(versionText) || versionText.endsWith("-SNAPSHOT")) {
            throw GradleException("releaseVersion must be a non-SNAPSHOT semantic version")
        }
        val requiredSecrets = listOf(
            "MAVEN_CENTRAL_USERNAME",
            "MAVEN_CENTRAL_PASSWORD",
            "MAVEN_SIGNING_KEY",
            "MAVEN_SIGNING_PASSWORD",
        )
        val missing = requiredSecrets.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing release secrets: ${missing.joinToString()}")
        }
    }
}

val stageRelease = tasks.register("stageRelease") {
    group = "publishing"
    description = "Publishes only the three signed modules to a local Central bundle layout"
    dependsOn(verifyReleaseConfiguration)
    dependsOn(publishableModules.map {
        project(":$it").tasks.named("publishMavenJavaPublicationToCentralStagingRepository")
    })
}

val verifyCentralStaging = tasks.register("verifyCentralStaging") {
    group = "publishing"
    description = "Verifies the signed Central staging layout and unpublished module exclusions"
    dependsOn(stageRelease)
    doLast {
        val stagingRoot = layout.buildDirectory.dir("central-staging").get().asFile
        val versionText = releaseVersion.get()
        for (moduleName in publishableModules) {
            val moduleDirectory = stagingRoot.resolve("$mavenGroupPath/$moduleName/$versionText")
            for (suffix in listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom")) {
                val artifact = moduleDirectory.resolve("$moduleName-$versionText$suffix")
                if (!artifact.isFile || artifact.length() == 0L) {
                    throw GradleException("Missing staged artifact: $artifact")
                }
                val signature = moduleDirectory.resolve("${artifact.name}.asc")
                if (!signature.isFile || signature.length() == 0L) {
                    throw GradleException("Missing staged signature: $signature")
                }
            }
        }
        val forbidden = stagingRoot.walkTopDown().filter { file ->
            file.isFile && (file.path.contains("libgdx-ui-markup-preview")
                || file.path.contains("libgdx-ui-markup-idea"))
        }.toList()
        if (forbidden.isNotEmpty()) {
            throw GradleException("Unpublished modules entered staging: $forbidden")
        }
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Packages the verified Maven Central Portal deployment bundle"
    dependsOn(verifyCentralStaging)
    from(layout.buildDirectory.dir("central-staging"))
    archiveFileName.set("central-bundle-${releaseVersion.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
