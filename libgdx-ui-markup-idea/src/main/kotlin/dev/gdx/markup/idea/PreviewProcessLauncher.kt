package dev.gdx.markup.idea

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves and launches the preview distribution. Resolution order: explicit system property
 * {@code markup.preview.dist} (tests and dev), the distribution bundled next to the plugin
 * installation, then the prepared dev build directory.
 */
object PreviewProcessLauncher {
    private const val MAIN_CLASS = "dev.gdx.markup.preview.PreviewApp"

    /** Returns the launchable distribution, or {@code null} when nothing was found. */
    fun resolveDistribution(): Path? {
        System.getProperty("markup.preview.dist")?.takeIf { it.isNotBlank() }?.let {
            return Path.of(it).takeIf(::launchable)
        }
        pluginInstallRoot()?.let { root ->
            val bundled = root.resolve("libgdx-ui-markup-preview")
            if (launchable(bundled)) {
                return bundled
            }
        }
        return null
    }

    /** Builds the bounded command line for one markup file and its optional sibling CSS. */
    fun buildCommand(distribution: Path, ui: Path, css: Path?): List<String> {
        val java = Path.of(
            System.getProperty("java.home") ?: "java", "bin", "java").toString()
        val arguments = mutableListOf(
            java,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            distribution.resolve("lib").resolve("*").toString(),
            MAIN_CLASS,
            "--ui",
            ui.toAbsolutePath().toString(),
        )
        if (css != null) {
            arguments += "--css"
            arguments += css.toAbsolutePath().toString()
        }
        return arguments
    }

    /** Launches the preview for one markup file; callers stream stdout for status lines. */
    fun launch(distribution: Path, ui: Path, css: Path?): Process =
        ProcessBuilder(buildCommand(distribution, ui, css)).start()

    /** Returns the sibling {@code .css} file, or {@code null} when the preview cannot run. */
    fun siblingCss(ui: Path): Path? {
        val sibling = ui.resolveSibling(ui.fileName.toString()
            .substringBeforeLast('.') + ".css")
        return if (Files.isRegularFile(sibling)) sibling else null
    }

    private fun launchable(distribution: Path): Boolean =
        Files.isDirectory(distribution.resolve("lib"))

    private fun pluginInstallRoot(): Path? {
        val codeSource = PreviewProcessLauncher::class.java.protectionDomain
            ?.codeSource?.location ?: return null
        return try {
            val path = Path.of(codeSource.toURI())
            if (path.fileName?.toString()?.endsWith(".jar") == true) {
                // Plugin jars live in <plugin>/lib/; the bundled dist sits next to lib/.
                path.parent?.parent
            } else {
                path.parent?.parent
            }
        } catch (failure: Exception) {
            null
        }
    }
}
