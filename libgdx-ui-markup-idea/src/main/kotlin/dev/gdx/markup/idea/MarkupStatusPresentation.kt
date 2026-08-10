package dev.gdx.markup.idea

/** Pure schema-v3 status presentation shared by the panel and unit tests. */
object MarkupStatusPresentation {
    /** Returns source-first actionable status text without applying the panel length cap. */
    fun text(status: MarkupStatusLine): String {
        if (status.ok) return "ok (${status.nodes} actors)"
        val parts = mutableListOf<String>()
        location(status.source, status.elementPath, status.line, status.column)
            .takeIf { it.isNotEmpty() }
            ?.let(parts::add)
        parts += status.message ?: "build failed"
        if (!status.attribute.isNullOrEmpty() || !status.expected.isNullOrEmpty() ||
            !status.received.isNullOrEmpty()
        ) {
            val attribute = status.attribute?.takeIf { it.isNotEmpty() }?.let { "$it: " }.orEmpty()
            parts += attribute + "expected ${status.expected.orEmpty()}, " +
                "received ${status.received.orEmpty()}"
        }
        status.suggestion?.takeIf { it.isNotEmpty() }?.let { parts += "suggestion: $it" }
        status.consequence?.takeIf { it.isNotEmpty() }?.let(parts::add)
        status.componentTrace.forEach { frame ->
            parts += "via ${frame.component} at " +
                location(frame.source, frame.elementPath, frame.line, frame.column)
        }
        return parts.joinToString(" — ")
    }

    private fun location(
        source: String?,
        elementPath: String?,
        line: Int?,
        column: Int?,
    ): String {
        val identity = source?.takeIf { it.isNotEmpty() }
            ?: elementPath?.takeIf { it.isNotEmpty() }
            ?: ""
        if (line == null || line <= 0) return identity
        val prefix = if (identity.isEmpty()) "" else "$identity:"
        return "$prefix$line:${column ?: 0}"
    }
}
