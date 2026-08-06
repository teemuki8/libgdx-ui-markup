package dev.gdx.markup.idea

/**
 * One parsed {@code markup-status: {...}} line emitted by the preview application. The JSON is
 * bounded and produced by the preview, so a small scanner (no JSON dependency) is sufficient.
 */
data class MarkupStatusLine(
    val ok: Boolean,
    val nodes: Int?,
    val message: String?,
    val line: Int?,
    val column: Int?,
)

/** Parses {@code markup-status: {json}} lines; returns {@code null} for other output. */
object MarkupStatusLineParser {
    private val PREFIX = "markup-status:"

    fun parse(line: String): MarkupStatusLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX)) {
            return null
        }
        val json = trimmed.substring(PREFIX.length).trim()
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return null
        }
        val ok = boolean(json, "ok") ?: return null
        return MarkupStatusLine(
            ok = ok,
            nodes = int(json, "nodes"),
            message = string(json, "message"),
            line = int(json, "line"),
            column = int(json, "column"),
        )
    }

    private fun boolean(json: String, key: String): Boolean? {
        val value = value(json, key) ?: return null
        return when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun int(json: String, key: String): Int? =
        value(json, key)?.toIntOrNull()

    private fun string(json: String, key: String): String? {
        val value = value(json, key) ?: return null
        if (value.length < 2 || value.first() != '"' || value.last() != '"') {
            return null
        }
        val body = value.substring(1, value.length - 1)
        return buildString {
            var index = 0
            while (index < body.length) {
                val char = body[index]
                if (char == '\\' && index + 1 < body.length) {
                    append(body[index + 1])
                    index += 2
                } else {
                    append(char)
                    index += 1
                }
            }
        }
    }

    /** Returns the raw value token for one {@code "key":value} pair. */
    private fun value(json: String, key: String): String? {
        val needle = "\"$key\""
        val keyAt = json.indexOf(needle)
        if (keyAt < 0) {
            return null
        }
        val colonAt = json.indexOf(':', keyAt + needle.length)
        if (colonAt < 0) {
            return null
        }
        var start = colonAt + 1
        while (start < json.length && (json[start] == ' ' || json[start] == '\t')) {
            start++
        }
        if (start >= json.length) {
            return null
        }
        return when (json[start]) {
            '"' -> {
                var end = start + 1
                var escaped = false
                while (end < json.length) {
                    val char = json[end]
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        return json.substring(start, end + 1)
                    }
                    end++
                }
                null
            }
            else -> {
                var end = start
                while (end < json.length && json[end] != ',' && json[end] != '}') {
                    end++
                }
                json.substring(start, end).trim()
            }
        }
    }
}
