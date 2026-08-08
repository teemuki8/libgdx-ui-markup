package dev.gdx.markup.idea

/**
 * One parsed {@code markup-status: {...}} line emitted by the preview application (schema
 * version 2). The JSON is bounded and produced by the preview, so a small scanner (no JSON
 * dependency) is sufficient. The IDEA panel consumes the typed fields directly — it never
 * parses path or coordinates out of the message prose.
 */
data class MarkupStatusLine(
    val schemaVersion: Int,
    val ok: Boolean,
    val kind: String?,
    val elementPath: String?,
    val line: Int?,
    val column: Int?,
    val message: String?,
    val nodes: Int?,
)

/** Parses {@code markup-status: {json}} lines; returns {@code null} for other output. */
object MarkupStatusLineParser {
    private val PREFIX = "markup-status:"
    private const val SUPPORTED_SCHEMA_VERSION = 2
    private const val GENERIC_KIND = "GENERIC"

    /**
     * Parses one status line. Returns {@code null} for output that is not a status line or a
     * malformed schema-v2 payload (negative nodes, errors without a kind or message, errors
     * carrying nodes, negative locations, or a generic error with an element path); returns an
     * error {@link MarkupStatusLine} carrying an actionable message when the line declares an
     * unsupported schema version (the preview distribution is newer or older than the plugin).
     */
    fun parse(line: String): MarkupStatusLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX)) {
            return null
        }
        val json = trimmed.substring(PREFIX.length).trim()
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return null
        }
        val schemaVersion = int(json, "schemaVersion") ?: 1
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return unsupported(schemaVersion)
        }
        val ok = boolean(json, "ok") ?: return null
        val kind = string(json, "kind")
        val elementPath = string(json, "elementPath")
        val line = int(json, "line")
        val column = int(json, "column")
        val message = string(json, "message")
        val nodes = int(json, "nodes")
        if (ok) {
            if (nodes == null || nodes < 0) {
                return null
            }
            if (kind != null || elementPath != null || message != null
                || line != null || column != null
            ) {
                return null
            }
        } else {
            if (kind == null || kind.isBlank()) {
                return null
            }
            if (message == null) {
                return null
            }
            if (line != null && line < 0) {
                return null
            }
            if (column != null && column < 0) {
                return null
            }
            if (nodes != null && nodes != 0) {
                return null
            }
            val pathEmpty = elementPath == null || elementPath.isEmpty()
            if (kind == GENERIC_KIND && !pathEmpty) {
                return null
            }
        }
        return MarkupStatusLine(
            schemaVersion = schemaVersion,
            ok = ok,
            kind = kind,
            elementPath = elementPath,
            line = line,
            column = column,
            message = message,
            nodes = nodes,
        )
    }

    private fun unsupported(schemaVersion: Int): MarkupStatusLine = MarkupStatusLine(
        schemaVersion = schemaVersion,
        ok = false,
        kind = "UNSUPPORTED_SCHEMA",
        elementPath = null,
        line = null,
        column = null,
        message = "preview status schema v$schemaVersion is not supported by this plugin"
            + " (supports v$SUPPORTED_SCHEMA_VERSION) — update the plugin or rebuild the"
            + " preview distribution",
        nodes = null,
    )

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
                    when (val escaped = body[index + 1]) {
                        '"', '\\', '/' -> append(escaped)
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        'b' -> append('\b')
                        'f' -> append('\u000C')
                        'u' -> {
                            val hex = body.substring(index + 2, (index + 6).coerceAtMost(body.length))
                            val code = hex.toIntOrNull(16)
                            if (hex.length == 4 && code != null) {
                                append(code.toChar())
                                index += 4
                            } else {
                                append(escaped)
                            }
                        }
                        else -> append(escaped)
                    }
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
