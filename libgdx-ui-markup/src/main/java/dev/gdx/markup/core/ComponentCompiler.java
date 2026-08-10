package dev.gdx.markup.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands bounded document-local components before concrete-dialect validation. */
final class ComponentCompiler {
    private static final Pattern COMPONENT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern SUBSTITUTION =
            Pattern.compile("\\$\\{([a-z][a-z0-9-]{0,63})}");
    private static final Set<String> DEFINITION_TAGS =
            Set.of("components", "component", "param");
    private static final Set<String> INVALID_COMPONENT_ROOTS =
            Set.of("ui", "row", "slot", "fill", "components", "component", "param");

    private final int maxFinalElements;
    private final int maxAttributeValue;
    private final int maxText;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

    ComponentCompiler(int maxFinalElements, int maxAttributeValue, int maxText) {
        if (maxFinalElements < 1 || maxAttributeValue < 1 || maxText < 1) {
            throw new IllegalArgumentException("component compiler limits must be positive");
        }
        this.maxFinalElements = maxFinalElements;
        this.maxAttributeValue = maxAttributeValue;
        this.maxText = maxText;
    }

    RawElement expand(RawElement documentRoot) {
        Objects.requireNonNull(documentRoot, "documentRoot");
        if (!"ui".equals(documentRoot.tag())) {
            rejectReservedTree(documentRoot);
            return documentRoot;
        }

        RawElement components = findComponentsBlock(documentRoot);
        if (components != null) {
            indexDefinitions(components);
        }

        List<RawElement> body = new ArrayList<>();
        for (RawElement child : documentRoot.children()) {
            if (child == components) {
                continue;
            }
            body.add(expandBodyNode(child));
        }
        if (1 + countConcrete(body) > maxFinalElements) {
            throw tooLarge(
                    documentRoot.origin(),
                    "expanded document exceeds the " + maxFinalElements + "-element limit");
        }
        return copy(documentRoot, documentRoot.attrs(), documentRoot.text(), body,
                documentRoot.componentTrace());
    }

    private RawElement findComponentsBlock(RawElement root) {
        RawElement block = null;
        List<RawElement> children = root.children();
        for (int index = 0; index < children.size(); index++) {
            RawElement child = children.get(index);
            if ("components".equals(child.tag())) {
                if (index != 0 || block != null) {
                    throw invalid(
                            child.origin(),
                            child.origin().elementPath(),
                            "<components> must be the first and only component block under <ui>",
                            "one optional first <components> child",
                            child.tag(),
                            "");
                }
                block = child;
            }
        }
        return block;
    }

    private void indexDefinitions(RawElement block) {
        requireOnlyAttributes(block, Set.of());
        if (!block.text().isEmpty()) {
            throw invalid(
                    block.origin(),
                    block.origin().elementPath(),
                    "<components> accepts only <component> children",
                    "component definitions",
                    "text content",
                    "");
        }
        for (RawElement child : block.children()) {
            if (!"component".equals(child.tag())) {
                throw invalid(
                        child.origin(),
                        child.origin().elementPath(),
                        "<components> accepts only <component> children",
                        "component",
                        child.tag(),
                        "");
            }
            if (definitions.size() >= MarkupParser.MAX_COMPONENTS) {
                throw tooLarge(
                        child.origin(),
                        "document exceeds the " + MarkupParser.MAX_COMPONENTS
                                + "-component limit");
            }
            Definition definition = definition(child);
            Definition previous = definitions.putIfAbsent(definition.name(), definition);
            if (previous != null) {
                throw diagnostic(
                        MarkupException.Kind.DUPLICATE_COMPONENT,
                        child.origin(),
                        child.origin().elementPath(),
                        "duplicate component \"" + definition.name() + "\"",
                        "a document-unique component name",
                        definition.name(),
                        "name",
                        List.of());
            }
        }
    }

    private Definition definition(RawElement component) {
        requireOnlyAttributes(component, Set.of("name"));
        RawAttribute nameAttribute = requireAttribute(component, "name");
        String name = nameAttribute.value();
        if (!COMPONENT_NAME.matcher(name).matches()) {
            throw invalid(
                    nameAttribute.origin(),
                    component.origin().elementPath(),
                    "invalid component name \"" + name + "\"",
                    "[A-Z][A-Za-z0-9]{0,63}",
                    name,
                    "name");
        }

        LinkedHashMap<String, Parameter> parameters = new LinkedHashMap<>();
        RawElement templateRoot = null;
        for (RawElement child : component.children()) {
            if ("param".equals(child.tag()) && templateRoot == null) {
                if (parameters.size() >= MarkupParser.MAX_COMPONENT_PARAMETERS) {
                    throw tooLarge(
                            child.origin(),
                            "component \"" + name + "\" exceeds the "
                                    + MarkupParser.MAX_COMPONENT_PARAMETERS + "-parameter limit");
                }
                Parameter parameter = parameter(child);
                if (parameters.putIfAbsent(parameter.name(), parameter) != null) {
                    throw invalid(
                            child.origin(),
                            child.origin().elementPath(),
                            "duplicate parameter \"" + parameter.name() + "\"",
                            "a component-unique parameter name",
                            parameter.name(),
                            "name");
                }
            } else {
                if (templateRoot != null) {
                    throw invalid(
                            child.origin(),
                            component.origin().elementPath(),
                            "component \"" + name + "\" must declare exactly one template root",
                            "zero or more parameters followed by one actor root",
                            "multiple roots or a late parameter",
                            "");
                }
                templateRoot = child;
            }
        }
        if (templateRoot == null) {
            throw invalid(
                    component.origin(),
                    component.origin().elementPath(),
                    "component \"" + name + "\" has no template root",
                    "exactly one actor root",
                    "none",
                    "");
        }
        if (INVALID_COMPONENT_ROOTS.contains(templateRoot.tag())) {
            throw invalid(
                    templateRoot.origin(),
                    templateRoot.origin().elementPath(),
                    "<" + templateRoot.tag() + "> cannot be a component root",
                    "one actor tag or nested use",
                    templateRoot.tag(),
                    "");
        }

        Map<String, Parameter> immutableParameters =
                Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        validateReferences(templateRoot, immutableParameters);
        return new Definition(name, immutableParameters, templateRoot, component.origin());
    }

    private Parameter parameter(RawElement raw) {
        requireOnlyAttributes(raw, Set.of("name", "required", "default"));
        if (!raw.children().isEmpty() || !raw.text().isEmpty()) {
            throw invalid(
                    raw.origin(),
                    raw.origin().elementPath(),
                    "<param> must be empty",
                    "an empty parameter declaration",
                    "content",
                    "");
        }
        RawAttribute nameAttribute = requireAttribute(raw, "name");
        String name = nameAttribute.value();
        if (!PARAMETER_NAME.matcher(name).matches()) {
            throw invalid(
                    nameAttribute.origin(),
                    raw.origin().elementPath(),
                    "invalid parameter name \"" + name + "\"",
                    "[a-z][a-z0-9-]{0,63}",
                    name,
                    "name");
        }
        boolean required = false;
        RawAttribute requiredAttribute = raw.attrs().get("required");
        if (requiredAttribute != null) {
            if (!"true".equals(requiredAttribute.value())
                    && !"false".equals(requiredAttribute.value())) {
                throw invalid(
                        requiredAttribute.origin(),
                        raw.origin().elementPath(),
                        "invalid value for \"required\"",
                        "true or false",
                        requiredAttribute.value(),
                        "required");
            }
            required = Boolean.parseBoolean(requiredAttribute.value());
        }
        RawAttribute defaultAttribute = raw.attrs().get("default");
        String defaultValue = defaultAttribute == null ? null : defaultAttribute.value();
        if (required && defaultValue != null) {
            throw invalid(
                    raw.origin(),
                    raw.origin().elementPath(),
                    "required parameter \"" + name + "\" cannot declare a default",
                    "required or default, not both",
                    "required and default",
                    "default");
        }
        if (defaultValue != null && SUBSTITUTION.matcher(defaultValue).find()) {
            throw invalid(
                    defaultAttribute.origin(),
                    raw.origin().elementPath(),
                    "parameter default cannot contain component substitution",
                    "literal default text",
                    defaultValue,
                    "default");
        }
        return new Parameter(name, required, defaultValue);
    }

    private RawElement expandBodyNode(RawElement raw) {
        if ("use".equals(raw.tag())) {
            return expandUse(raw);
        }
        if (DEFINITION_TAGS.contains(raw.tag()) || "param".equals(raw.tag())) {
            throw invalid(
                    raw.origin(),
                    raw.origin().elementPath(),
                    "<" + raw.tag() + "> is only valid in the document component block",
                    "ordinary actor content or <use>",
                    raw.tag(),
                    "");
        }
        List<RawElement> children = new ArrayList<>(raw.children().size());
        for (RawElement child : raw.children()) {
            children.add(expandBodyNode(child));
        }
        return copy(raw, raw.attrs(), raw.text(), children, raw.componentTrace());
    }

    private RawElement expandUse(RawElement invocation) {
        RawAttribute componentAttribute = requireAttribute(invocation, "component");
        Definition definition = definitions.get(componentAttribute.value());
        if (definition == null) {
            throw diagnostic(
                    MarkupException.Kind.UNKNOWN_COMPONENT,
                    componentAttribute.origin(),
                    invocation.origin().elementPath(),
                    "unknown component \"" + componentAttribute.value() + "\"",
                    "a locally declared component name",
                    componentAttribute.value(),
                    "component",
                    invocation.componentTrace());
        }
        if (!invocation.children().isEmpty() || !invocation.text().isEmpty()) {
            throw invalid(
                    invocation.origin(),
                    invocation.origin().elementPath(),
                    "<use> children must be supplied through <fill>",
                    "an empty invocation until slots are supplied",
                    "direct content",
                    "");
        }

        LinkedHashMap<String, String> parameterValues = new LinkedHashMap<>();
        LinkedHashMap<String, RawAttribute> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : invocation.attrs().entrySet()) {
            String attribute = entry.getKey();
            if ("component".equals(attribute)) {
                continue;
            }
            boolean parameter = definition.parameters().containsKey(attribute);
            boolean override = TagSpec.isCommonAttribute(attribute)
                    || attribute.startsWith("data-");
            if (!parameter && !override) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        entry.getValue().origin(),
                        invocation.origin().elementPath(),
                        "unknown parameter or root override \"" + attribute + "\"",
                        "a declared parameter or common actor attribute",
                        attribute,
                        attribute,
                        invocation.componentTrace());
            }
            if (parameter) {
                parameterValues.put(attribute, entry.getValue().value());
            }
            if (override) {
                overrides.put(attribute, entry.getValue());
            }
        }
        for (Parameter parameter : definition.parameters().values()) {
            if (parameterValues.containsKey(parameter.name())) {
                continue;
            }
            if (parameter.defaultValue() != null) {
                parameterValues.put(parameter.name(), parameter.defaultValue());
            } else if (parameter.required()) {
                throw diagnostic(
                        MarkupException.Kind.MISSING_PARAMETER,
                        invocation.origin(),
                        invocation.origin().elementPath(),
                        "component \"" + definition.name() + "\" requires parameter \""
                                + parameter.name() + "\"",
                        "required parameter \"" + parameter.name() + "\"",
                        "",
                        parameter.name(),
                        invocation.componentTrace());
            } else {
                parameterValues.put(parameter.name(), "");
            }
        }

        List<ComponentTraceFrame> trace = appendTrace(
                invocation.componentTrace(),
                new ComponentTraceFrame(definition.name(), invocation.origin()));
        RawElement expanded = cloneTemplate(definition.templateRoot(), parameterValues, trace);
        return applyOverrides(expanded, overrides);
    }

    private RawElement cloneTemplate(
            RawElement raw,
            Map<String, String> parameters,
            List<ComponentTraceFrame> trace) {
        LinkedHashMap<String, RawAttribute> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : raw.attrs().entrySet()) {
            String value = substitute(
                    entry.getValue().value(), parameters, maxAttributeValue,
                    entry.getValue().origin(), trace);
            attrs.put(entry.getKey(), new RawAttribute(value, entry.getValue().origin()));
        }
        String text = substitute(raw.text(), parameters, maxText, raw.origin(), trace);
        List<RawElement> children = new ArrayList<>(raw.children().size());
        for (RawElement child : raw.children()) {
            children.add(cloneTemplate(child, parameters, trace));
        }
        return copy(raw, attrs, text, children, trace);
    }

    private RawElement applyOverrides(
            RawElement root, Map<String, RawAttribute> overrides) {
        LinkedHashMap<String, RawAttribute> attrs = new LinkedHashMap<>(root.attrs());
        for (Map.Entry<String, RawAttribute> entry : overrides.entrySet()) {
            if ("class".equals(entry.getKey())) {
                RawAttribute templateClass = attrs.get("class");
                String merged = mergeClasses(
                        templateClass == null ? "" : templateClass.value(),
                        entry.getValue().value());
                attrs.put("class", new RawAttribute(merged, entry.getValue().origin()));
            } else {
                attrs.put(entry.getKey(), entry.getValue());
            }
        }
        return copy(root, attrs, root.text(), root.children(), root.componentTrace());
    }

    private void validateReferences(RawElement raw, Map<String, Parameter> parameters) {
        for (RawAttribute attribute : raw.attrs().values()) {
            validateReferences(attribute.value(), parameters, attribute.origin());
        }
        validateReferences(raw.text(), parameters, raw.origin());
        for (RawElement child : raw.children()) {
            validateReferences(child, parameters);
        }
    }

    private static void validateReferences(
            String value, Map<String, Parameter> parameters, MarkupSourceLocation origin) {
        Matcher matcher = SUBSTITUTION.matcher(value);
        int substitutions = 0;
        while (matcher.find()) {
            if (++substitutions > MarkupParser.MAX_SUBSTITUTIONS_PER_VALUE) {
                throw tooLarge(
                        origin,
                        "value exceeds the " + MarkupParser.MAX_SUBSTITUTIONS_PER_VALUE
                                + "-substitution limit");
            }
            String name = matcher.group(1);
            if (!parameters.containsKey(name)) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        origin,
                        origin.elementPath(),
                        "unknown parameter reference \"" + name + "\"",
                        "a parameter declared by this component",
                        name,
                        name,
                        List.of());
            }
        }
    }

    private static String substitute(
            String value,
            Map<String, String> parameters,
            int maxLength,
            MarkupSourceLocation origin,
            List<ComponentTraceFrame> trace) {
        Matcher matcher = SUBSTITUTION.matcher(value);
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        int position = 0;
        int substitutions = 0;
        while (matcher.find()) {
            if (++substitutions > MarkupParser.MAX_SUBSTITUTIONS_PER_VALUE) {
                throw tooLarge(
                        origin,
                        "value exceeds the " + MarkupParser.MAX_SUBSTITUTIONS_PER_VALUE
                                + "-substitution limit");
            }
            appendBounded(result, value, position, matcher.start(), maxLength, origin, trace);
            String replacement = parameters.get(matcher.group(1));
            if (replacement == null) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        origin,
                        origin.elementPath(),
                        "unknown parameter reference \"" + matcher.group(1) + "\"",
                        "a parameter declared by this component",
                        matcher.group(1),
                        matcher.group(1),
                        trace);
            }
            appendBounded(result, replacement, 0, replacement.length(), maxLength, origin, trace);
            position = matcher.end();
        }
        appendBounded(result, value, position, value.length(), maxLength, origin, trace);
        return result.toString();
    }

    private static void appendBounded(
            StringBuilder result,
            String value,
            int start,
            int end,
            int maxLength,
            MarkupSourceLocation origin,
            List<ComponentTraceFrame> trace) {
        if (result.length() + end - start > maxLength) {
            throw diagnostic(
                    MarkupException.Kind.TOO_LARGE,
                    origin,
                    origin.elementPath(),
                    "expanded value exceeds the " + maxLength + "-character limit",
                    "at most " + maxLength + " characters",
                    Integer.toString(result.length() + end - start),
                    "",
                    trace);
        }
        result.append(value, start, end);
    }

    private static String mergeClasses(String template, String invocation) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addClasses(tokens, template);
        addClasses(tokens, invocation);
        return String.join(" ", tokens);
    }

    private static void addClasses(Set<String> tokens, String value) {
        if (value.isBlank()) {
            return;
        }
        for (String token : value.strip().split("\\s+")) {
            tokens.add(token.toLowerCase(Locale.ROOT));
        }
    }

    private static RawAttribute requireAttribute(RawElement raw, String name) {
        RawAttribute attribute = raw.attrs().get(name);
        if (attribute == null) {
            throw diagnostic(
                    MarkupException.Kind.MISSING_ATTRIBUTE,
                    raw.origin(),
                    raw.origin().elementPath(),
                    "<" + raw.tag() + "> requires attribute \"" + name + "\"",
                    "required attribute \"" + name + "\"",
                    "",
                    name,
                    raw.componentTrace());
        }
        return attribute;
    }

    private static void requireOnlyAttributes(RawElement raw, Set<String> allowed) {
        for (String attribute : raw.attrs().keySet()) {
            if (!allowed.contains(attribute)) {
                throw invalid(
                        raw.attrs().get(attribute).origin(),
                        raw.origin().elementPath(),
                        "unknown attribute \"" + attribute + "\" on <" + raw.tag() + ">",
                        allowed.isEmpty() ? "no attributes" : String.join(", ", allowed),
                        attribute,
                        attribute);
            }
        }
    }

    private static void rejectReservedTree(RawElement raw) {
        if (DEFINITION_TAGS.contains(raw.tag()) || "use".equals(raw.tag())
                || "slot".equals(raw.tag()) || "fill".equals(raw.tag())) {
            throw invalid(
                    raw.origin(),
                    raw.origin().elementPath(),
                    "<" + raw.tag() + "> requires an <ui> document component context",
                    "a concrete actor tag",
                    raw.tag(),
                    "");
        }
        for (RawElement child : raw.children()) {
            rejectReservedTree(child);
        }
    }

    private static int countConcrete(List<RawElement> roots) {
        int count = 0;
        for (RawElement root : roots) {
            count += countConcrete(root);
        }
        return count;
    }

    private static int countConcrete(RawElement raw) {
        int count = 1;
        for (RawElement child : raw.children()) {
            count += countConcrete(child);
        }
        return count;
    }

    private static List<ComponentTraceFrame> appendTrace(
            List<ComponentTraceFrame> trace, ComponentTraceFrame frame) {
        if (trace.size() >= 16) {
            throw tooLarge(frame.invocation(), "component trace exceeds the 16-frame limit");
        }
        List<ComponentTraceFrame> result = new ArrayList<>(trace.size() + 1);
        result.addAll(trace);
        result.add(frame);
        return List.copyOf(result);
    }

    private static RawElement copy(
            RawElement raw,
            Map<String, RawAttribute> attrs,
            String text,
            List<RawElement> children,
            List<ComponentTraceFrame> trace) {
        return new RawElement(raw.tag(), attrs, text, children, raw.origin(), trace);
    }

    private static MarkupException invalid(
            MarkupSourceLocation origin,
            String path,
            String message,
            String expected,
            String received,
            String attribute) {
        return diagnostic(
                MarkupException.Kind.INVALID_VALUE,
                origin,
                path,
                message,
                expected,
                received,
                attribute,
                List.of());
    }

    private static MarkupException tooLarge(MarkupSourceLocation origin, String message) {
        return diagnostic(
                MarkupException.Kind.TOO_LARGE,
                origin,
                origin.elementPath(),
                message,
                "bounded component expansion",
                "limit exceeded",
                "",
                List.of());
    }

    private static MarkupException diagnostic(
            MarkupException.Kind kind,
            MarkupSourceLocation origin,
            String path,
            String message,
            String expected,
            String received,
            String attribute,
            List<ComponentTraceFrame> trace) {
        return new MarkupException(
                kind,
                path,
                origin.line(),
                origin.column(),
                message,
                new MarkupDiagnosticContext(
                        origin.source(),
                        attribute,
                        expected,
                        received,
                        "",
                        "document rejected before Scene2D build",
                        trace));
    }

    private record Parameter(String name, boolean required, String defaultValue) {}

    private record Definition(
            String name,
            Map<String, Parameter> parameters,
            RawElement templateRoot,
            MarkupSourceLocation origin) {}
}
