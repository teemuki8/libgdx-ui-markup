rootProject.name = "gdx-ui-markup"

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
    "gdx-ui-markup",
    "gdx-ui-markup-harness",
    "gdx-ui-markup-runtime",
    "gdx-ui-markup-preview",
    "gdx-ui-markup-idea",
)
