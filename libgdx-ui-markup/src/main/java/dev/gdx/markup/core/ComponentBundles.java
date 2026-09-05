package dev.gdx.markup.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bounded raw-source composition; the existing component compiler owns all dialect validation. */
final class ComponentBundles {
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");

    private ComponentBundles() {}

    static Map<String, String> sources(Map<String, String> sources) {
        Objects.requireNonNull(sources, "bundles");
        if (sources.size() > 16) {
            throw new IllegalArgumentException("at most 16 component bundles are supported");
        }
        Map<String, String> copy = new TreeMap<>();
        sources.forEach((name, xml) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("bundle namespace must be an UpperCamel identifier");
            }
            copy.put(name, Objects.requireNonNull(xml, "bundle XML"));
        });
        return Collections.unmodifiableMap(copy);
    }

    static RawElement merge(RawElement screen, Map<String, RawElement> bundles, int maxElements) {
        if (!"ui".equals(screen.tag())) {
            throw invalid(screen, "registered bundles require an <ui> screen root");
        }
        int count = count(screen);
        List<RawElement> definitions = new ArrayList<>();
        for (Map.Entry<String, RawElement> entry : bundles.entrySet()) {
            RawElement root = entry.getValue();
            count += count(root);
            if (count > maxElements) {
                throw failure(root, MarkupException.Kind.TOO_LARGE,
                        "screen and bundles exceed the " + maxElements + "-element limit");
            }
            if (!"ui".equals(root.tag()) || !root.attrs().isEmpty() || !root.text().isEmpty()
                    || root.children().size() != 1
                    || !"components".equals(root.children().getFirst().tag())) {
                throw invalid(root, "bundle must contain only <ui><components> definitions");
            }
            RawElement block = root.children().getFirst();
            if (!block.attrs().isEmpty() || !block.text().isEmpty()) {
                throw invalid(block, "bundle <components> accepts definitions only");
            }
            for (RawElement definition : block.children()) {
                RawAttribute name = definition.attrs().get("name");
                if (!"component".equals(definition.tag()) || name == null
                        || !NAME.matcher(name.value()).matches()) {
                    throw invalid(definition, "bundle definitions require unqualified UpperCamel names");
                }
                RawElement qualified = qualify(definition, entry.getKey());
                Map<String, RawAttribute> attrs = new LinkedHashMap<>(qualified.attrs());
                attrs.put("name", new RawAttribute(entry.getKey() + "." + name.value(), name.origin()));
                definitions.add(copy(qualified, attrs, qualified.children()));
            }
        }
        List<RawElement> body = new ArrayList<>(screen.children());
        RawElement local = !body.isEmpty() && "components".equals(body.getFirst().tag())
                ? body.removeFirst() : new RawElement("components", Map.of(), "", List.of(),
                        screen.origin(), List.of());
        definitions.addAll(local.children());
        body.addFirst(copy(local, local.attrs(), definitions));
        return copy(screen, screen.attrs(), body);
    }

    private static RawElement qualify(RawElement raw, String namespace) {
        Map<String, RawAttribute> attrs = new LinkedHashMap<>(raw.attrs());
        RawAttribute target = attrs.get("component");
        if ("use".equals(raw.tag()) && target != null && NAME.matcher(target.value()).matches()) {
            attrs.put("component", new RawAttribute(namespace + "." + target.value(), target.origin()));
        }
        List<RawElement> children = raw.children().stream().map(child -> qualify(child, namespace)).toList();
        return copy(raw, attrs, children);
    }

    private static int count(RawElement node) {
        int count = 1;
        for (RawElement child : node.children()) {
            count += count(child);
        }
        return count;
    }

    private static RawElement copy(RawElement raw, Map<String, RawAttribute> attrs, List<RawElement> children) {
        return new RawElement(raw.tag(), attrs, raw.text(), children, raw.origin(), raw.componentTrace());
    }

    private static MarkupException invalid(RawElement node, String message) {
        return failure(node, MarkupException.Kind.INVALID_VALUE, message);
    }

    private static MarkupException failure(RawElement node, MarkupException.Kind kind, String message) {
        MarkupSourceLocation origin = node.origin();
        return new MarkupException(kind, origin.elementPath(), origin.line(), origin.column(), message,
                new MarkupDiagnosticContext(origin.source(), "", "bounded component-only bundle",
                        node.tag(), "", "document rejected before Scene2D build", List.of()));
    }
}
