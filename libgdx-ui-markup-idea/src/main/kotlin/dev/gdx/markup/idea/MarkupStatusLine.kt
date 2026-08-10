package dev.gdx.markup.idea

/** One component invocation frame in a schema-v3 preview diagnostic. */
data class MarkupStatusTraceFrame(
    val component: String,
    val source: String,
    val elementPath: String,
    val line: Int,
    val column: Int,
)

/** One parsed schema-v3 {@code markup-status} line emitted by the preview application. */
data class MarkupStatusLine(
    val schemaVersion: Int,
    val ok: Boolean,
    val kind: String?,
    val source: String?,
    val elementPath: String?,
    val line: Int?,
    val column: Int?,
    val attribute: String?,
    val expected: String?,
    val received: String?,
    val suggestion: String?,
    val consequence: String?,
    val componentTrace: List<MarkupStatusTraceFrame>,
    val message: String?,
    val nodes: Int?,
)

/** Parses bounded {@code markup-status: {json}} lines without adding a JSON dependency. */
object MarkupStatusLineParser {
    private const val PREFIX = "markup-status:"
    private const val SUPPORTED_SCHEMA_VERSION = 3
    private const val GENERIC_KIND = "GENERIC"
    private const val MAX_STRING_LENGTH = 2_000
    private const val MAX_TRACE_FRAMES = 16
    private const val MAX_TRACE_LENGTH = 16_384
    private val TRACE_KEYS = setOf("component", "source", "elementPath", "line", "column")
    private val SUCCESS_KEYS = setOf("schemaVersion", "ok", "nodes")
    private val ERROR_KEYS = setOf(
        "schemaVersion", "ok", "kind", "source", "elementPath", "line", "column",
        "attribute", "expected", "received", "suggestion", "consequence", "componentTrace",
        "message",
    )

    /** Returns null for non-status output or malformed schema-v3 payloads. */
    fun parse(line: String): MarkupStatusLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val json = trimmed.substring(PREFIX.length).trim()
        val fields = parseObject(json) ?: return null
        val schemaVersion = fields.int("schemaVersion") ?: 1
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) return unsupported(schemaVersion)
        val ok = fields.boolean("ok") ?: return null
        val kind = fields.string("kind")
        val source = fields.string("source")
        val elementPath = fields.string("elementPath")
        val sourceLine = fields.int("line")
        val column = fields.int("column")
        val attribute = fields.string("attribute")
        val expected = fields.string("expected")
        val received = fields.string("received")
        val suggestion = fields.string("suggestion")
        val consequence = fields.string("consequence")
        val message = fields.string("message")
        val nodes = fields.int("nodes")

        val trace: List<MarkupStatusTraceFrame>
        if (ok) {
            if (fields.keys != SUCCESS_KEYS) return null
            if (nodes == null || nodes < 0) return null
            if (kind != null || source != null || elementPath != null || message != null ||
                sourceLine != null || column != null || attribute != null || expected != null ||
                received != null || suggestion != null || consequence != null ||
                fields.containsKey("componentTrace")
            ) return null
            trace = emptyList()
        } else {
            if (fields.keys != ERROR_KEYS && fields.keys != ERROR_KEYS + "nodes") return null
            if (kind == null || kind.isBlank() || message == null) return null
            if (source == null || elementPath == null || sourceLine == null || column == null ||
                attribute == null || expected == null || received == null || suggestion == null ||
                consequence == null
            ) return null
            if (sourceLine < 0 || column < 0) return null
            if (nodes != null && nodes != 0) return null
            if (kind == GENERIC_KIND && elementPath.isNotEmpty()) return null
            trace = trace(fields["componentTrace"] ?: return null) ?: return null
        }
        return MarkupStatusLine(
            schemaVersion = schemaVersion,
            ok = ok,
            kind = kind,
            source = source,
            elementPath = elementPath,
            line = sourceLine,
            column = column,
            attribute = attribute,
            expected = expected,
            received = received,
            suggestion = suggestion,
            consequence = consequence,
            componentTrace = trace,
            message = message,
            nodes = nodes,
        )
    }

    private fun unsupported(schemaVersion: Int) = MarkupStatusLine(
        schemaVersion = schemaVersion,
        ok = false,
        kind = "UNSUPPORTED_SCHEMA",
        source = null,
        elementPath = null,
        line = null,
        column = null,
        attribute = null,
        expected = null,
        received = null,
        suggestion = null,
        consequence = null,
        componentTrace = emptyList(),
        message = "preview status schema v$schemaVersion is not supported by this plugin " +
            "(supports v$SUPPORTED_SCHEMA_VERSION) — update the plugin or rebuild the " +
            "preview distribution",
        nodes = null,
    )

    private fun trace(raw: String): List<MarkupStatusTraceFrame>? {
        val objects = objectArray(raw, MAX_TRACE_FRAMES) ?: return null
        val frames = objects.map { objectJson ->
            val fields = parseObject(objectJson) ?: return null
            if (fields.keys != TRACE_KEYS) return null
            val component = fields.string("component") ?: return null
            val source = fields.string("source") ?: return null
            val elementPath = fields.string("elementPath") ?: return null
            val line = fields.int("line") ?: return null
            val column = fields.int("column") ?: return null
            if (component.isBlank() || source.isBlank() || line < 0 || column < 0) return null
            MarkupStatusTraceFrame(component, source, elementPath, line, column)
        }
        return frames.takeIf {
            it.sumOf { frame -> frame.source.length + frame.elementPath.length } <= MAX_TRACE_LENGTH
        }
    }

    private fun objectArray(raw: String, maxObjects: Int): List<String>? {
        if (raw.length < 2 || raw.first() != '[' || raw.last() != ']') return null
        val result = mutableListOf<String>()
        var index = 1
        while (true) {
            index = skipWhitespace(raw, index)
            if (index >= raw.lastIndex) return result
            if (raw[index] != '{') return null
            val end = compoundEnd(raw, index) ?: return null
            result += raw.substring(index, end)
            if (result.size > maxObjects) return null
            index = skipWhitespace(raw, end)
            if (index == raw.lastIndex) return result
            if (raw[index] != ',') return null
            index++
            if (skipWhitespace(raw, index) == raw.lastIndex) return null
        }
    }

    private fun parseObject(json: String): Map<String, String>? {
        if (json.length < 2 || json.first() != '{' || json.last() != '}') return null
        val fields = linkedMapOf<String, String>()
        var index = 1
        while (true) {
            index = skipWhitespace(json, index)
            if (index >= json.lastIndex) return fields
            if (json[index] != '"') return null
            val keyEnd = stringEnd(json, index) ?: return null
            val key = decodeString(json.substring(index, keyEnd)) ?: return null
            index = skipWhitespace(json, keyEnd)
            if (index >= json.lastIndex || json[index] != ':') return null
            index = skipWhitespace(json, index + 1)
            val valueEnd = valueEnd(json, index) ?: return null
            if (fields.putIfAbsent(key, json.substring(index, valueEnd).trim()) != null) return null
            index = skipWhitespace(json, valueEnd)
            if (index == json.lastIndex) return fields
            if (json[index] != ',') return null
            index++
            if (skipWhitespace(json, index) == json.lastIndex) return null
        }
    }

    private fun valueEnd(json: String, start: Int): Int? {
        if (start >= json.lastIndex) return null
        return when (json[start]) {
            '"' -> stringEnd(json, start)
            '{', '[' -> compoundEnd(json, start)
            else -> {
                var end = start
                while (end < json.length && json[end] != ',' && json[end] != '}') end++
                end.takeIf { it > start }
            }
        }
    }

    private fun stringEnd(json: String, start: Int): Int? {
        var index = start + 1
        var escaped = false
        while (index < json.length) {
            val char = json[index]
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') return index + 1
            index++
        }
        return null
    }

    private fun compoundEnd(json: String, start: Int): Int? {
        val stack = mutableListOf(json[start])
        var index = start + 1
        var inString = false
        var escaped = false
        while (index < json.length) {
            val char = json[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> stack += char
                    '}', ']' -> {
                        val open = stack.removeLastOrNull() ?: return null
                        if ((open == '{' && char != '}') || (open == '[' && char != ']')) return null
                        if (stack.isEmpty()) return index + 1
                    }
                }
            }
            index++
        }
        return null
    }

    private fun decodeString(token: String): String? {
        if (token.length < 2 || token.first() != '"' || token.last() != '"') return null
        val result = StringBuilder()
        var index = 1
        while (index < token.lastIndex) {
            val char = token[index]
            if (char == '\\') {
                if (index + 1 >= token.lastIndex) return null
                when (val escaped = token[index + 1]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'n' -> result.append('\n')
                    't' -> result.append('\t')
                    'r' -> result.append('\r')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'u' -> {
                        if (index + 6 > token.length) return null
                        val code = token.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                        result.append(code.toChar())
                        index += 4
                    }
                    else -> return null
                }
                index += 2
            } else {
                if (char.code < 0x20) return null
                result.append(char)
                index++
            }
            if (result.length > MAX_STRING_LENGTH) return null
        }
        return result.toString()
    }

    private fun skipWhitespace(value: String, start: Int): Int {
        var index = start
        while (index < value.length && value[index].isWhitespace()) index++
        return index
    }

    private fun Map<String, String>.boolean(key: String): Boolean? = when (this[key]) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun Map<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()

    private fun Map<String, String>.string(key: String): String? =
        this[key]?.let(::decodeString)
}
