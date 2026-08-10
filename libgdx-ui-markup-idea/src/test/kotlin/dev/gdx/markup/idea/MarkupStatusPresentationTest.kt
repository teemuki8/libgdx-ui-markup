package dev.gdx.markup.idea

import kotlin.test.Test
import kotlin.test.assertTrue

class MarkupStatusPresentationTest {
    @Test
    fun presentsSourceExpectedReceivedSuggestionAndTrace() {
        val text = MarkupStatusPresentation.text(componentFailureStatus())

        assertTrue(text.contains("hud.xml:9:9"))
        assertTrue(text.contains("value"))
        assertTrue(text.contains("finite float"))
        assertTrue(text.contains("fast"))
        assertTrue(text.contains("HealthBar"))
    }

    private fun componentFailureStatus() = MarkupStatusLine(
        schemaVersion = 3,
        ok = false,
        kind = "INVALID_VALUE",
        source = "hud.xml",
        elementPath = "ui/table/progressbar",
        line = 9,
        column = 9,
        attribute = "value",
        expected = "finite float",
        received = "fast",
        suggestion = "",
        consequence = "document rejected before Scene2D build",
        componentTrace = listOf(
            MarkupStatusTraceFrame("HealthBar", "hud.xml", "ui/use", 18, 3),
        ),
        message = "invalid value",
        nodes = null,
    )
}
