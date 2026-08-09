package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded complete-value design-token substitution for one GDXCSS :root block. */
public final class CssVariables {
    public static final int MAX_VARIABLES = 256;
    public static final int MAX_DEPTH = 16;
    private static final Pattern REFERENCE = Pattern.compile("var\\((--[A-Za-z][A-Za-z0-9_-]*)\\)");

    private CssVariables() {}

    static Map<String, String> resolve(Map<String, String> raw, int line, int column) {
        if (raw.size() > MAX_VARIABLES) throw tooLarge(line, column);
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        for (String name : raw.keySet()) resolveOne(name, raw, resolved,
                new java.util.HashSet<>(), 1, line, column);
        return Map.copyOf(resolved);
    }

    static String substitute(String value, Map<String, String> variables, int line, int column) {
        Matcher matcher = REFERENCE.matcher(value);
        if (matcher.matches()) {
            String resolved = variables.get(matcher.group(1));
            if (resolved == null) throw error(line, column,
                    "unresolved variable \"" + matcher.group(1) + "\"");
            return resolved;
        }
        if (value.contains("var(")) throw error(line, column,
                "var() must replace the complete property value and has no fallback syntax");
        return value;
    }

    private static String resolveOne(String name, Map<String, String> raw,
            Map<String, String> resolved, Set<String> visiting, int depth, int line, int column) {
        String done = resolved.get(name);
        if (done != null) return done;
        if (depth > MAX_DEPTH) throw error(line, column,
                "variable resolution exceeds depth " + MAX_DEPTH);
        String value = raw.get(name);
        if (value == null) throw error(line, column, "unresolved variable \"" + name + "\"");
        if (!visiting.add(name)) throw error(line, column, "variable cycle includes \"" + name + "\"");
        Matcher reference = REFERENCE.matcher(value);
        String result;
        if (reference.matches()) {
            result = resolveOne(reference.group(1), raw, resolved, visiting,
                    depth + 1, line, column);
        } else {
            if (value.contains("var(")) throw error(line, column,
                    "var() must replace a complete value without fallback syntax");
            result = value;
        }
        visiting.remove(name);
        resolved.put(name, result);
        return result;
    }

    private static MarkupException error(int line, int column, String message) {
        return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css", line, column, message);
    }

    private static MarkupException tooLarge(int line, int column) {
        return new MarkupException(MarkupException.Kind.TOO_LARGE, "css", line, column,
                "root variables exceed the " + MAX_VARIABLES + "-variable limit");
    }
}
