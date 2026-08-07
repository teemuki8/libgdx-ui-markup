rootProject.name = "libgdx-ui-markup"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Declares the Foojay Toolchains resolver so auto-provisioned JDKs (the IDEA module's
    // JBR-compatible 21) do not trigger Gradle's deprecated implicit-provisioning path.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

include(
    "libgdx-ui-markup",
    "libgdx-ui-markup-harness",
    "libgdx-ui-markup-runtime",
    "libgdx-ui-markup-preview",
    "libgdx-ui-markup-idea",
    "libgdx-ui-markup-qualification",
)
