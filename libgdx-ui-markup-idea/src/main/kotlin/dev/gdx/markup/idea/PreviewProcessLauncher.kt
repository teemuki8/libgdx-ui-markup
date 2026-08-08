package dev.gdx.markup.idea

import java.io.File
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
    fun buildCommand(distribution: Path, ui: Path, css: Path?): List<String> =
        buildCommand(distribution, ui, css, System.getProperty("os.name"))

    /**
     * Command construction with an explicit OS name so the macOS
     * {@code -XstartOnFirstThread} placement is testable deterministically on any host.
     * The command is assembled by the shared {@code PreviewJvmCommand} — the single platform
     * command builder also used by the preview test/GL-probe child launcher — invoked here
     * through its stable static API (the shared Java source cannot be linked directly: the
     * IDEA module compiles at Java 21 against the JBR while the preview module compiles at
     * Java 25, so Kotlin's Java-source linking does not apply; the class ships on this
     * module's runtime classpath from the same shared source compiled with this module's
     * toolchain).
     */
    internal fun buildCommand(distribution: Path, ui: Path, css: Path?, osName: String): List<String> {
        val java = Path.of(
            System.getProperty("java.home") ?: "java", "bin", "java").toString()
        // The classpath wildcard is JVM syntax, not a filesystem path: Path.resolve("*")
        // is illegal on Windows, so the glob is appended as a plain string.
        val classpath = distribution.resolve("lib").toString() + File.separatorChar + "*"
        val arguments = mutableListOf(
            "--ui",
            ui.toAbsolutePath().toString(),
        )
        if (css != null) {
            arguments += "--css"
            arguments += css.toAbsolutePath().toString()
        }
        val builder = Class.forName("dev.gdx.markup.preview.PreviewJvmCommand")
        val build = builder.getMethod(
            "build",
            String::class.java, List::class.java, String::class.java,
            String::class.java, List::class.java, String::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        return build.invoke(
            null,
            java,
            listOf("--enable-native-access=ALL-UNNAMED"),
            classpath,
            MAIN_CLASS,
            arguments,
            osName,
        ) as List<String>
    }

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
