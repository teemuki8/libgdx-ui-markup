rootProject.name = "gdx-ui-markup"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "gdx-ui-markup",
    "gdx-ui-markup-harness",
    "gdx-ui-markup-preview",
    "gdx-ui-markup-idea",
)
