package dev.gdx.markup.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles raw nodes into the existing validated concrete element dialect. */
final class ConcreteElementCompiler {
    private final Set<String> extraTags;
    private final int maxElements;
    private final Set<String> ids = new HashSet<>();
    private final ElementPathTracker paths = new ElementPathTracker();
    private final Map<String, ElementProvenance> provenance = new LinkedHashMap<>();
    private int elements;

    ConcreteElementCompiler(Set<String> extraTags, int maxElements) {
        this.extraTags = Set.copyOf(Objects.requireNonNull(extraTags, "extraTags"));
        if (maxElements < 1) {
            throw new IllegalArgumentException("maxElements must be positive");
        }
        this.maxElements = maxElements;
    }

    MarkupDocument compile(RawElement rawRoot, int byteLength, String source) {
        Objects.requireNonNull(rawRoot, "rawRoot");
        String sourceValue = MarkupSourceLocation.validateSource(source);
        Element root = compileElement(rawRoot);
        if (!"ui".equals(root.tag()) && !"table".equals(root.tag())) {
            throw failure(
                    MarkupException.Kind.INVALID_VALUE,
                    rawRoot.origin(),
                    root.tag(),
                    "root element must be <ui> or <table>, got <" + root.tag() + ">",
                    "ui or table",
                    root.tag());
        }
        return new MarkupDocument(root, byteLength, sourceValue, provenance);
    }

    private Element compileElement(RawElement raw) {
        String path = paths.enter(raw.tag());
        try {
            if (++elements > maxElements) {
                throw failure(
                        MarkupException.Kind.TOO_LARGE,
                        raw.origin(),
                        path,
                        "document exceeds the " + maxElements + "-element limit",
                        "at most " + maxElements + " concrete elements",
                        Integer.toString(elements));
            }
            TagSpec spec = TagSpec.require(
                    raw.tag(),
                    extraTags,
                    path,
                    raw.origin().line(),
                    raw.origin().column());
            LinkedHashMap<String, String> attrs = validateAttributes(raw, spec, path);
            requireAttributes(raw, spec, attrs, path);
            rejectDuplicateId(raw, attrs.get("id"), path);

            List<Element> children = new ArrayList<>(raw.children().size());
            for (RawElement child : raw.children()) {
                children.add(compileElement(child));
            }
            if (!raw.text().isEmpty() && !children.isEmpty()) {
                throw failure(
                        MarkupException.Kind.INVALID_VALUE,
                        raw.origin(),
                        path,
                        "mixed text content is not supported in <" + raw.tag() + ">",
                        "text-only content or child elements",
                        "both text and child elements");
            }

            Element element = element(raw, attrs, children);
            provenance.put(path, provenance(raw));
            return element;
        } finally {
            paths.exit();
        }
    }

    private LinkedHashMap<String, String> validateAttributes(
            RawElement raw, TagSpec spec, String path) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : raw.attrs().entrySet()) {
            String name = entry.getKey();
            RawAttribute attribute = entry.getValue();
            if (!spec.allows(name)) {
                throw failure(
                        MarkupException.Kind.UNKNOWN_ATTRIBUTE,
                        attribute.origin(),
                        path,
                        "unknown attribute \"" + name + "\" on <" + raw.tag() + ">",
                        "an attribute supported by <" + raw.tag() + ">",
                        name,
                        name);
            }
            TagSpec.ValueKind kind = spec.attributes().get(name);
            String validation = kind == null ? null : TagSpec.validate(kind, attribute.value());
            if (validation != null) {
                throw failure(
                        MarkupException.Kind.INVALID_VALUE,
                        attribute.origin(),
                        path,
                        "invalid value for \"" + name + "\": " + validation,
                        kind.name().toLowerCase(Locale.ROOT),
                        attribute.value(),
                        name);
            }
            attrs.put(name, attribute.value());
        }
        return attrs;
    }

    private static void requireAttributes(
            RawElement raw, TagSpec spec, Map<String, String> attrs, String path) {
        for (String required : spec.required()) {
            if (!attrs.containsKey(required)) {
                throw failure(
                        MarkupException.Kind.MISSING_ATTRIBUTE,
                        raw.origin(),
                        path,
                        "<" + raw.tag() + "> requires attribute \"" + required + "\"",
                        "required attribute \"" + required + "\"",
                        "",
                        required);
            }
        }
    }

    private void rejectDuplicateId(RawElement raw, String id, String path) {
        if (id != null && !ids.add(id)) {
            throw failure(
                    MarkupException.Kind.DUPLICATE_ID,
                    raw.attrs().get("id").origin(),
                    path,
                    "duplicate id \"" + id + "\"",
                    "a document-unique id",
                    id,
                    "id");
        }
    }

    private static Element element(
            RawElement raw, Map<String, String> attrs, List<Element> children) {
        String declaredText = attrs.get("text");
        String textValue = raw.text().isEmpty() ? declaredText : raw.text();
        LinkedHashMap<String, String> semanticAttrs = new LinkedHashMap<>(attrs);
        if (raw.text().isEmpty() && declaredText != null) {
            semanticAttrs.put("text", declaredText);
        } else if (!raw.text().isEmpty()) {
            semanticAttrs.put("text", raw.text());
        }
        return new Element(
                raw.tag(),
                attrs.get("id"),
                attrs.get("name"),
                attrs.get("label"),
                textValue,
                semanticAttrs,
                splitClasses(attrs.get("class")),
                children,
                raw.origin().line(),
                raw.origin().column());
    }

    private static List<String> splitClasses(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static ElementProvenance provenance(RawElement raw) {
        Map<String, MarkupSourceLocation> attributeOrigins = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : raw.attrs().entrySet()) {
            attributeOrigins.put(entry.getKey(), entry.getValue().origin());
        }
        return new ElementProvenance(raw.origin(), attributeOrigins, raw.componentTrace());
    }

    private static MarkupException failure(
            MarkupException.Kind kind,
            MarkupSourceLocation location,
            String path,
            String message,
            String expected,
            String received) {
        return failure(kind, location, path, message, expected, received, "");
    }

    private static MarkupException failure(
            MarkupException.Kind kind,
            MarkupSourceLocation location,
            String path,
            String message,
            String expected,
            String received,
            String attribute) {
        return new MarkupException(
                kind,
                path,
                location.line(),
                location.column(),
                message,
                new MarkupDiagnosticContext(
                        location.source(),
                        attribute,
                        expected,
                        received,
                        "",
                        "document rejected before Scene2D build",
                        List.of()));
    }
}
