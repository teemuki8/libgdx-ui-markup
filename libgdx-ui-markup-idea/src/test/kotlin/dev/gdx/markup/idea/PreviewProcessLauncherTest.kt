package dev.gdx.markup.idea

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewProcessLauncherTest {
    @Test
    fun buildsBoundedCommandLine() {
        val dist = createTempDirectory("dist")
        val lib = dist.resolve("lib")
        Files.createDirectories(lib)
        val ui = Path.of("/tmp/app.xml")
        val css = Path.of("/tmp/app.css")
        val command = PreviewProcessLauncher.buildCommand(dist, ui, css)
        // Host-agnostic assertions: on macOS a platform flag (-XstartOnFirstThread) is
        // inserted at index 1, on other hosts the first JVM flag is at index 1; the
        // dedicated macOs/nonMacOs builder tests pin the flag selection itself.
        val nativeAccess = command.indexOf("--enable-native-access=ALL-UNNAMED")
        assertTrue(nativeAccess >= 1, "the JVM flag follows the java executable: $command")
        if (nativeAccess > 1) {
            assertEquals("-XstartOnFirstThread", command[1],
                "on macOS the platform flag precedes the JVM flags")
        }
        assertEquals("-cp", command[nativeAccess + 1], "-cp follows the JVM flags")
        assertTrue(command[nativeAccess + 2].endsWith("${java.io.File.separator}lib"
            + "${java.io.File.separator}*"))
        assertEquals("dev.gdx.markup.preview.PreviewApp", command[nativeAccess + 3],
            "the main class follows the classpath")
        val uiIndex = command.indexOf("--ui")
        assertEquals(uiIndex + 1, command.indexOf(ui.toAbsolutePath().toString()))
        assertEquals(uiIndex + 2, command.indexOf("--css"))
        assertEquals(uiIndex + 3, command.indexOf(css.toAbsolutePath().toString()))
    }

    @Test
    fun macOsLauncherPutsXstartOnFirstThreadBeforeClasspathAndMain() {
        val dist = createTempDirectory("dist")
        Files.createDirectories(dist.resolve("lib"))
        val ui = Path.of("/tmp/app.xml")
        val command = PreviewProcessLauncher.buildCommand(dist, ui, null, "Mac OS X")
        assertEquals("-XstartOnFirstThread", command[1],
            "macOS production children run with -XstartOnFirstThread")
        assertEquals("-cp", command[3],
            "the platform flag precedes the classpath option")
        assertTrue(command[4].endsWith("${java.io.File.separator}lib"
            + "${java.io.File.separator}*"))
        assertEquals("dev.gdx.markup.preview.PreviewApp", command[5],
            "the main class follows the classpath")
        assertEquals("--ui", command[6])
    }

    @Test
    fun nonMacOsLauncherAddsNoPlatformFlag() {
        val dist = createTempDirectory("dist")
        Files.createDirectories(dist.resolve("lib"))
        val ui = Path.of("/tmp/app.xml")
        for (os in listOf("Linux", "Windows 11")) {
            val command = PreviewProcessLauncher.buildCommand(dist, ui, null, os)
            assertFalse(command.contains("-XstartOnFirstThread"),
                "no platform flag on $os (it is macOS-only)")
            assertEquals("--enable-native-access=ALL-UNNAMED", command[1])
            assertEquals("-cp", command[2])
        }
    }

    @Test
    fun siblingCssRequiresTheCssFile() {
        val dir = createTempDirectory("ui")
        val ui = dir.resolve("signin.xml")
        Files.writeString(ui, "<ui/>")
        assertNull(PreviewProcessLauncher.siblingCss(ui), "no css yet")
        Files.writeString(dir.resolve("signin.css"), "")
        assertEquals(dir.resolve("signin.css"), PreviewProcessLauncher.siblingCss(ui))
    }

    @Test
    fun resolvesDistributionFromSystemProperty() {
        val dist = createTempDirectory("dist")
        Files.createDirectories(dist.resolve("lib"))
        val previous = System.getProperty("markup.preview.dist")
        try {
            System.setProperty("markup.preview.dist", dist.toString())
            val resolved = PreviewProcessLauncher.resolveDistribution()
            assertNotNull(resolved)
            assertEquals(dist.toAbsolutePath(), resolved.toAbsolutePath())
        } finally {
            if (previous == null) {
                System.clearProperty("markup.preview.dist")
            } else {
                System.setProperty("markup.preview.dist", previous)
            }
        }
    }

    @Test
    fun resolutionNeverPointsAtMissingDistribution() {
        val previous = System.getProperty("markup.preview.dist")
        try {
            System.clearProperty("markup.preview.dist")
            val resolved = PreviewProcessLauncher.resolveDistribution()
            if (resolved != null) {
                assertTrue(Files.isDirectory(resolved.resolve("lib")),
                    "resolved distribution must be launchable: $resolved")
            }
        } finally {
            if (previous != null) {
                System.setProperty("markup.preview.dist", previous)
            }
        }
    }
}
