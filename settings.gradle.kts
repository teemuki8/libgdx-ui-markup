rootProject.name = "libgdx-ui-markup"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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
)
