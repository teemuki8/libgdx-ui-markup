package dev.gdx.markup.idea

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves and launches the preview distribution. Resolution order: explicit system property
 * {@code markup.preview.dist} (tests and prepared development builds), then the distribution
 * bundled in the installed plugin directory.
 */
object PreviewProcessLauncher {
    private const val PLUGIN_ID = "dev.gdx.markup.idea"
    private const val MAIN_CLASS = "dev.gdx.markup.preview.PreviewApp"

    /** Returns the launchable distribution, or {@code null} when nothing was found. */
    fun resolveDistribution(): Path? {
        val explicitDistribution = System.getProperty("markup.preview.dist")
        if (!explicitDistribution.isNullOrBlank()) {
            return Path.of(explicitDistribution).takeIf(::launchable)
        }
        return resolveDistribution(null, pluginInstallRoot())
    }

    internal fun resolveDistribution(explicitDistribution: String?, pluginRoot: Path?): Path? {
        explicitDistribution?.takeIf { it.isNotBlank() }?.let {
            return Path.of(it).takeIf(::launchable)
        }
        pluginRoot?.let { root ->
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
     * The macOS-only flag is selected at construction time and placed before the classpath
     * and the main class; the same platform-flag contract is pinned by the preview test
     * helper ({@code PreviewTestProcess}) so the GL probe and the production launch agree.
     */
    internal fun buildCommand(distribution: Path, ui: Path, css: Path?, osName: String): List<String> {
        val java = Path.of(
            System.getProperty("java.home") ?: "java", "bin", "java").toString()
        // The classpath wildcard is JVM syntax, not a filesystem path: Path.resolve("*")
        // is illegal on Windows, so the glob is appended as a plain string.
        val classpath = distribution.resolve("lib").toString() + File.separatorChar + "*"
        val programArgs = mutableListOf(
            "--ui",
            ui.toAbsolutePath().toString(),
        )
        if (css != null) {
            programArgs += "--css"
            programArgs += css.toAbsolutePath().toString()
        }
        return buildCommand(
            java,
            listOf("--enable-native-access=ALL-UNNAMED"),
            classpath,
            MAIN_CLASS,
            programArgs,
            osName,
        )
    }

    /**
     * Assembles the child JVM command: {@code java <platform-flags> <jvmFlags> -cp
     * <classpath> <mainClass> <programArgs...>}. Platform flags (macOS
     * {@code -XstartOnFirstThread} only) are inserted before {@code -cp} and the main class,
     * in the deterministic position the JVM requires.
     */
    internal fun buildCommand(
        java: String,
        jvmFlags: List<String>,
        classpath: String,
        mainClass: String,
        programArgs: List<String>,
        osName: String,
    ): List<String> {
        val command = mutableListOf<String>()
        command += java
        command += platformJvmFlags(osName)
        command += jvmFlags
        command += "-cp"
        command += classpath
        command += mainClass
        command += programArgs
        return command
    }

    /**
     * Extra JVM flags a preview/GL child requires on {@code osName} before any other option.
     * macOS forbids GLFW/AppKit window creation unless the main thread is the process's first
     * thread, so every preview child must run with {@code -XstartOnFirstThread} there; other
     * platforms need nothing extra.
     */
    internal fun platformJvmFlags(osName: String?): List<String> =
        if (osName != null && osName.lowercase().contains("mac")) {
            listOf("-XstartOnFirstThread")
        } else {
            emptyList()
        }

    /** Returns the sibling {@code .css} file, or {@code null} when the preview cannot run. */
    fun siblingCss(ui: Path): Path? {
        val sibling = ui.resolveSibling(ui.fileName.toString()
            .substringBeforeLast('.') + ".css")
        return if (Files.isRegularFile(sibling)) sibling else null
    }

    private fun launchable(distribution: Path): Boolean =
        Files.isDirectory(distribution.resolve("lib"))

    private fun pluginInstallRoot(): Path? =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath
}
