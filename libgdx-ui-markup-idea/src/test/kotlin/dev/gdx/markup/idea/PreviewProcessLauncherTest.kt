package dev.gdx.markup.idea

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("--enable-native-access=ALL-UNNAMED", command[1])
        assertEquals("-cp", command[2])
        assertTrue(command[3].endsWith("${java.io.File.separator}lib"
            + "${java.io.File.separator}*"))
        assertEquals("dev.gdx.markup.preview.PreviewApp", command[4])
        assertEquals("--ui", command[5])
        assertEquals(ui.toAbsolutePath().toString(), command[6])
        assertEquals("--css", command[7])
        assertEquals(css.toAbsolutePath().toString(), command[8])
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
