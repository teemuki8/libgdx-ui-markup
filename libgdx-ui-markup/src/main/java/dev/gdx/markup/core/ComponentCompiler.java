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
    private ExpansionBudget budget;

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
        budget = new ExpansionBudget(maxFinalElements);
        budget.addConcrete(documentRoot.origin(), documentRoot.componentTrace());

        List<RawElement> body = new ArrayList<>();
        for (RawElement child : documentRoot.children()) {
            if (child != components) {
                body.add(expandCallerNode(child, documentRoot.componentTrace(), false));
            }
        }
        return copy(
                documentRoot,
                documentRoot.attrs(),
                documentRoot.text(),
                body,
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
                            "",
                            List.of());
                }
                block = child;
            }
        }
        return block;
    }

    private void indexDefinitions(RawElement block) {
        requireOnlyAttributes(block, Set.of(), List.of());
        if (!block.text().isEmpty()) {
            throw invalid(
                    block.origin(),
                    block.origin().elementPath(),
                    "<components> accepts only <component> children",
                    "component definitions",
                    "text content",
                    "",
                    List.of());
        }
        for (RawElement child : block.children()) {
            if (!"component".equals(child.tag())) {
                throw invalid(
                        child.origin(),
                        child.origin().elementPath(),
                        "<components> accepts only <component> children",
                        "component",
                        child.tag(),
                        "",
                        List.of());
            }
            if (definitions.size() >= MarkupParser.MAX_COMPONENTS) {
                throw tooLarge(
                        child.origin(),
                        "document exceeds the " + MarkupParser.MAX_COMPONENTS
                                + "-component limit",
                        List.of());
            }
            Definition definition = definition(child);
            if (definitions.putIfAbsent(definition.name(), definition) != null) {
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
        requireOnlyAttributes(component, Set.of("name"), List.of());
        RawAttribute nameAttribute = requireAttribute(component, "name", List.of());
        String name = nameAttribute.value();
        if (!COMPONENT_NAME.matcher(name).matches()) {
            throw invalid(
                    nameAttribute.origin(),
                    component.origin().elementPath(),
                    "invalid component name \"" + name + "\"",
                    "[A-Z][A-Za-z0-9]{0,63}",
                    name,
                    "name",
                    List.of());
        }

        LinkedHashMap<String, Parameter> parameters = new LinkedHashMap<>();
        RawElement templateRoot = null;
        for (RawElement child : component.children()) {
            if ("param".equals(child.tag()) && templateRoot == null) {
                if (parameters.size() >= MarkupParser.MAX_COMPONENT_PARAMETERS) {
                    throw tooLarge(
                            child.origin(),
                            "component \"" + name + "\" exceeds the "
                                    + MarkupParser.MAX_COMPONENT_PARAMETERS + "-parameter limit",
                            List.of());
                }
                Parameter parameter = parameter(child);
                if (parameters.putIfAbsent(parameter.name(), parameter) != null) {
                    throw invalid(
                            child.origin(),
                            child.origin().elementPath(),
                            "duplicate parameter \"" + parameter.name() + "\"",
                            "a component-unique parameter name",
                            parameter.name(),
                            "name",
                            List.of());
                }
            } else {
                if (templateRoot != null) {
                    throw invalid(
                            child.origin(),
                            component.origin().elementPath(),
                            "component \"" + name + "\" must declare exactly one template root",
                            "zero or more parameters followed by one actor root",
                            "multiple roots or a late parameter",
                            "",
                            List.of());
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
                    "",
                    List.of());
        }
        if (INVALID_COMPONENT_ROOTS.contains(templateRoot.tag())) {
            throw invalid(
                    templateRoot.origin(),
                    templateRoot.origin().elementPath(),
                    "<" + templateRoot.tag() + "> cannot be a component root",
                    "one actor tag or nested use",
                    templateRoot.tag(),
                    "",
                    List.of());
        }

        Map<String, Parameter> immutableParameters =
                Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        validateReferences(templateRoot, immutableParameters);
        Map<String, Slot> slots = indexSlots(templateRoot);
        return new Definition(name, immutableParameters, slots, templateRoot, component.origin());
    }

    private Parameter parameter(RawElement raw) {
        requireOnlyAttributes(raw, Set.of("name", "required", "default"), List.of());
        if (!raw.children().isEmpty() || !raw.text().isEmpty()) {
            throw invalid(
                    raw.origin(), raw.origin().elementPath(), "<param> must be empty",
                    "an empty parameter declaration", "content", "", List.of());
        }
        RawAttribute nameAttribute = requireAttribute(raw, "name", List.of());
        String name = nameAttribute.value();
        if (!PARAMETER_NAME.matcher(name).matches()) {
            throw invalid(
                    nameAttribute.origin(), raw.origin().elementPath(),
                    "invalid parameter name \"" + name + "\"",
                    "[a-z][a-z0-9-]{0,63}", name, "name", List.of());
        }
        boolean required = booleanAttribute(raw, "required", false, List.of());
        RawAttribute defaultAttribute = raw.attrs().get("default");
        String defaultValue = defaultAttribute == null ? null : defaultAttribute.value();
        if (required && defaultValue != null) {
            throw invalid(
                    raw.origin(), raw.origin().elementPath(),
                    "required parameter \"" + name + "\" cannot declare a default",
                    "required or default, not both", "required and default", "default", List.of());
        }
        if (defaultValue != null && SUBSTITUTION.matcher(defaultValue).find()) {
            throw invalid(
                    defaultAttribute.origin(), raw.origin().elementPath(),
                    "parameter default cannot contain component substitution",
                    "literal default text", defaultValue, "default", List.of());
        }
        return new Parameter(name, required, defaultValue);
    }

    private Map<String, Slot> indexSlots(RawElement templateRoot) {
        LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();
        indexSlots(templateRoot, slots);
        return Collections.unmodifiableMap(slots);
    }

    private void indexSlots(RawElement raw, Map<String, Slot> slots) {
        if ("slot".equals(raw.tag())) {
            requireOnlyAttributes(raw, Set.of("name", "required"), List.of());
            if (!raw.text().isEmpty()) {
                throw invalid(
                        raw.origin(), raw.origin().elementPath(), "<slot> accepts actor children only",
                        "fallback elements", "text content", "", List.of());
            }
            String name = attribute(raw, "name", "");
            if (!name.isEmpty() && !PARAMETER_NAME.matcher(name).matches()) {
                throw invalid(
                        raw.origin(), raw.origin().elementPath(),
                        "invalid slot name \"" + name + "\"",
                        "[a-z][a-z0-9-]{0,63}", name, "name", List.of());
            }
            boolean required = booleanAttribute(raw, "required", false, List.of());
            if (required && !raw.children().isEmpty()) {
                throw invalid(
                        raw.origin(), raw.origin().elementPath(),
                        "required slot cannot declare fallback children",
                        "required slot or fallback children, not both",
                        "required and fallback", "required", List.of());
            }
            if (slots.size() >= MarkupParser.MAX_COMPONENT_SLOTS) {
                throw tooLarge(
                        raw.origin(),
                        "component exceeds the " + MarkupParser.MAX_COMPONENT_SLOTS
                                + "-slot limit",
                        List.of());
            }
            Slot slot = new Slot(name, required, raw.children(), raw.origin());
            if (slots.putIfAbsent(name, slot) != null) {
                throw diagnostic(
                        MarkupException.Kind.DUPLICATE_SLOT,
                        raw.origin(), raw.origin().elementPath(),
                        "duplicate slot \"" + displaySlot(name) + "\"",
                        "a component-unique slot name", displaySlot(name), "name", List.of());
            }
        }
        for (RawElement child : raw.children()) {
            indexSlots(child, slots);
        }
    }

    private RawElement expandCallerNode(
            RawElement raw, List<ComponentTraceFrame> trace, boolean countVisit) {
        if ("use".equals(raw.tag())) {
            return expandUse(withTrace(raw, trace));
        }
        if (DEFINITION_TAGS.contains(raw.tag())
                || "slot".equals(raw.tag())
                || "fill".equals(raw.tag())) {
            throw invalid(
                    raw.origin(), raw.origin().elementPath(),
                    "<" + raw.tag() + "> is not valid in ordinary document content",
                    "ordinary actor content or <use>", raw.tag(), "", trace);
        }
        if (countVisit) {
            budget.visit(raw.origin(), trace);
        }
        budget.addConcrete(raw.origin(), trace);
        List<RawElement> children = new ArrayList<>(raw.children().size());
        for (RawElement child : raw.children()) {
            children.add(expandCallerNode(child, trace, countVisit));
        }
        return copy(raw, raw.attrs(), raw.text(), children, trace);
    }

    private RawElement expandUse(RawElement invocation) {
        budget.visit(invocation.origin(), invocation.componentTrace());
        RawAttribute componentAttribute =
                requireAttribute(invocation, "component", invocation.componentTrace());
        Definition definition = definitions.get(componentAttribute.value());
        if (definition == null) {
            throw diagnostic(
                    MarkupException.Kind.UNKNOWN_COMPONENT,
                    componentAttribute.origin(), invocation.origin().elementPath(),
                    "unknown component \"" + componentAttribute.value() + "\"",
                    NearestSuggestion.expected(definitions.keySet()),
                    componentAttribute.value(), "component",
                    NearestSuggestion.unique(componentAttribute.value(), definitions.keySet())
                            .orElse(""),
                    invocation.componentTrace());
        }

        budget.enterComponent(definition.name(), invocation);
        try {
            List<ComponentTraceFrame> trace = appendTrace(
                    invocation.componentTrace(),
                    new ComponentTraceFrame(definition.name(), invocation.origin()));
            Map<String, String> parameters = parameterValues(definition, invocation, trace);
            Map<String, List<RawElement>> fills = fills(definition, invocation, trace);
            ExpansionScope scope =
                    new ExpansionScope(definition, invocation, parameters, fills, trace);
            List<RawElement> roots = expandTemplateNode(definition.templateRoot(), scope);
            if (roots.size() != 1) {
                throw invalid(
                        invocation.origin(), invocation.origin().elementPath(),
                        "component \"" + definition.name()
                                + "\" must expand to exactly one actor root",
                        "exactly one actor root", Integer.toString(roots.size()), "component", trace);
            }
            return applyOverrides(roots.getFirst(), rootOverrides(definition, invocation, trace));
        } finally {
            budget.exitComponent();
        }
    }

    private Map<String, String> parameterValues(
            Definition definition, RawElement invocation, List<ComponentTraceFrame> trace) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : invocation.attrs().entrySet()) {
            String attributeName = entry.getKey();
            if ("component".equals(attributeName)) {
                continue;
            }
            boolean parameter = definition.parameters().containsKey(attributeName);
            boolean override = isRootOverride(attributeName);
            if (!parameter && !override) {
                Set<String> candidates = invocationNames(definition);
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        entry.getValue().origin(), invocation.origin().elementPath(),
                        "unknown parameter or root override \"" + attributeName + "\"",
                        NearestSuggestion.expected(candidates), attributeName,
                        attributeName,
                        NearestSuggestion.unique(attributeName, candidates).orElse(""), trace);
            }
            if (parameter) {
                values.put(attributeName, entry.getValue().value());
            }
        }
        for (Parameter parameter : definition.parameters().values()) {
            if (values.containsKey(parameter.name())) {
                continue;
            }
            if (parameter.defaultValue() != null) {
                values.put(parameter.name(), parameter.defaultValue());
            } else if (parameter.required()) {
                throw diagnostic(
                        MarkupException.Kind.MISSING_PARAMETER,
                        invocation.origin(), invocation.origin().elementPath(),
                        "component \"" + definition.name() + "\" requires parameter \""
                                + parameter.name() + "\"",
                        "required parameter \"" + parameter.name() + "\"", "",
                        parameter.name(), trace);
            } else {
                values.put(parameter.name(), "");
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private Map<String, RawAttribute> rootOverrides(
            Definition definition, RawElement invocation, List<ComponentTraceFrame> trace) {
        LinkedHashMap<String, RawAttribute> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : invocation.attrs().entrySet()) {
            String name = entry.getKey();
            if (!"component".equals(name) && isRootOverride(name)) {
                overrides.put(name, entry.getValue());
            } else if (!"component".equals(name)
                    && !definition.parameters().containsKey(name)) {
                Set<String> candidates = invocationNames(definition);
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        entry.getValue().origin(), invocation.origin().elementPath(),
                        "unknown parameter or root override \"" + name + "\"",
                        NearestSuggestion.expected(candidates), name, name,
                        NearestSuggestion.unique(name, candidates).orElse(""), trace);
            }
        }
        return overrides;
    }

    private Map<String, List<RawElement>> fills(
            Definition definition, RawElement invocation, List<ComponentTraceFrame> trace) {
        if (!invocation.text().isEmpty()) {
            throw invalid(
                    invocation.origin(), invocation.origin().elementPath(),
                    "<use> children must be supplied through <fill>",
                    "direct <fill> children", "text content", "", trace);
        }
        LinkedHashMap<String, List<RawElement>> fills = new LinkedHashMap<>();
        for (RawElement fill : invocation.children()) {
            if (!"fill".equals(fill.tag())) {
                throw invalid(
                        fill.origin(), invocation.origin().elementPath(),
                        "<use> children must be direct <fill> elements",
                        "fill", fill.tag(), "", trace);
            }
            budget.visit(fill.origin(), trace);
            requireOnlyAttributes(fill, Set.of("slot"), trace);
            if (!fill.text().isEmpty()) {
                throw invalid(
                        fill.origin(), fill.origin().elementPath(),
                        "<fill> accepts actor children only",
                        "actor elements", "text content", "", trace);
            }
            String name = attribute(fill, "slot", "");
            if (!name.isEmpty() && !PARAMETER_NAME.matcher(name).matches()) {
                throw invalid(
                        fill.origin(), fill.origin().elementPath(),
                        "invalid fill slot name \"" + name + "\"",
                        "[a-z][a-z0-9-]{0,63}", name, "slot", trace);
            }
            if (!definition.slots().containsKey(name)) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_SLOT,
                        fill.origin(), fill.origin().elementPath(),
                        "unknown slot \"" + displaySlot(name) + "\"",
                        NearestSuggestion.expected(definition.slots().keySet()),
                        displaySlot(name), "slot",
                        NearestSuggestion.unique(name, definition.slots().keySet()).orElse(""),
                        trace);
            }
            if (fills.putIfAbsent(name, fill.children()) != null) {
                throw diagnostic(
                        MarkupException.Kind.DUPLICATE_SLOT,
                        fill.origin(), fill.origin().elementPath(),
                        "duplicate fill for slot \"" + displaySlot(name) + "\"",
                        "at most one fill per slot", displaySlot(name), "slot", trace);
            }
        }
        for (Slot slot : definition.slots().values()) {
            if (slot.required() && !fills.containsKey(slot.name())) {
                throw diagnostic(
                        MarkupException.Kind.MISSING_SLOT,
                        invocation.origin(), invocation.origin().elementPath(),
                        "component \"" + definition.name() + "\" requires slot \""
                                + displaySlot(slot.name()) + "\"",
                        "required slot fill", "", "slot", trace);
            }
        }
        return Collections.unmodifiableMap(fills);
    }

    private List<RawElement> expandTemplateNode(RawElement raw, ExpansionScope scope) {
        if ("slot".equals(raw.tag())) {
            budget.visit(raw.origin(), scope.trace());
            return expandSlot(raw, scope);
        }
        if ("use".equals(raw.tag())) {
            RawElement invocation = substituteTree(raw, scope.parameters(), scope.trace());
            return List.of(expandUse(invocation));
        }
        if (DEFINITION_TAGS.contains(raw.tag()) || "fill".equals(raw.tag())) {
            throw invalid(
                    raw.origin(), raw.origin().elementPath(),
                    "<" + raw.tag() + "> is not valid at this template position",
                    "an actor, slot, or nested use", raw.tag(), "", scope.trace());
        }

        budget.visit(raw.origin(), scope.trace());
        budget.addConcrete(raw.origin(), scope.trace());
        LinkedHashMap<String, RawAttribute> attrs = substituteAttributes(
                raw.attrs(), scope.parameters(), scope.trace());
        String text = substitute(
                raw.text(), scope.parameters(), maxText, raw.origin(), scope.trace());
        List<RawElement> children = new ArrayList<>();
        for (RawElement child : raw.children()) {
            children.addAll(expandTemplateNode(child, scope));
        }
        return List.of(copy(raw, attrs, text, children, scope.trace()));
    }

    private List<RawElement> expandSlot(RawElement raw, ExpansionScope scope) {
        String name = attribute(raw, "name", "");
        List<RawElement> fill = scope.fills().get(name);
        if (fill != null) {
            List<RawElement> expanded = new ArrayList<>();
            for (RawElement child : fill) {
                expanded.add(expandCallerNode(child, scope.trace(), true));
            }
            return expanded;
        }
        Slot declared = scope.definition().slots().get(name);
        if (declared == null) {
            throw diagnostic(
                    MarkupException.Kind.UNKNOWN_SLOT,
                    raw.origin(), raw.origin().elementPath(),
                    "unknown slot declaration \"" + displaySlot(name) + "\"",
                    NearestSuggestion.expected(scope.definition().slots().keySet()),
                    displaySlot(name), "name",
                    NearestSuggestion.unique(name, scope.definition().slots().keySet()).orElse(""),
                    scope.trace());
        }
        if (declared.required()) {
            throw diagnostic(
                    MarkupException.Kind.MISSING_SLOT,
                    scope.invocation().origin(), scope.invocation().origin().elementPath(),
                    "component \"" + scope.definition().name() + "\" requires slot \""
                            + displaySlot(name) + "\"",
                    "required slot fill", "", "slot", scope.trace());
        }
        List<RawElement> expanded = new ArrayList<>();
        for (RawElement child : declared.fallback()) {
            expanded.addAll(expandTemplateNode(child, scope));
        }
        return expanded;
    }

    private RawElement substituteTree(
            RawElement raw,
            Map<String, String> parameters,
            List<ComponentTraceFrame> trace) {
        Map<String, RawAttribute> attrs =
                substituteAttributes(raw.attrs(), parameters, trace);
        String text = substitute(raw.text(), parameters, maxText, raw.origin(), trace);
        List<RawElement> children = new ArrayList<>(raw.children().size());
        for (RawElement child : raw.children()) {
            children.add(substituteTree(child, parameters, trace));
        }
        return copy(raw, attrs, text, children, trace);
    }

    private LinkedHashMap<String, RawAttribute> substituteAttributes(
            Map<String, RawAttribute> raw,
            Map<String, String> parameters,
            List<ComponentTraceFrame> trace) {
        LinkedHashMap<String, RawAttribute> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, RawAttribute> entry : raw.entrySet()) {
            RawAttribute attribute = entry.getValue();
            String value = substitute(
                    attribute.value(), parameters, maxAttributeValue, attribute.origin(), trace);
            attrs.put(entry.getKey(), new RawAttribute(value, attribute.origin()));
        }
        return attrs;
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
                                + "-substitution limit",
                        List.of());
            }
            String name = matcher.group(1);
            if (!parameters.containsKey(name)) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        origin, origin.elementPath(),
                        "unknown parameter reference \"" + name + "\"",
                        NearestSuggestion.expected(parameters.keySet()), name, name,
                        NearestSuggestion.unique(name, parameters.keySet()).orElse(""), List.of());
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
                                + "-substitution limit",
                        trace);
            }
            appendBounded(result, value, position, matcher.start(), maxLength, origin, trace);
            String replacement = parameters.get(matcher.group(1));
            if (replacement == null) {
                throw diagnostic(
                        MarkupException.Kind.UNKNOWN_PARAMETER,
                        origin, origin.elementPath(),
                        "unknown parameter reference \"" + matcher.group(1) + "\"",
                        NearestSuggestion.expected(parameters.keySet()), matcher.group(1),
                        matcher.group(1),
                        NearestSuggestion.unique(matcher.group(1), parameters.keySet()).orElse(""),
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
                    origin, origin.elementPath(),
                    "expanded value exceeds the " + maxLength + "-character limit",
                    "at most " + maxLength + " characters",
                    Integer.toString(result.length() + end - start), "", trace);
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
        if (!value.isBlank()) {
            for (String token : value.strip().split("\\s+")) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static boolean isRootOverride(String attribute) {
        return TagSpec.isCommonAttribute(attribute) || attribute.startsWith("data-");
    }

    private static Set<String> invocationNames(Definition definition) {
        LinkedHashSet<String> names = new LinkedHashSet<>(definition.parameters().keySet());
        names.addAll(TagSpec.commonAttributeNames());
        names.add("data-*");
        return names;
    }

    private static String attribute(RawElement raw, String name, String defaultValue) {
        RawAttribute attribute = raw.attrs().get(name);
        return attribute == null ? defaultValue : attribute.value();
    }

    private static RawAttribute requireAttribute(
            RawElement raw, String name, List<ComponentTraceFrame> trace) {
        RawAttribute attribute = raw.attrs().get(name);
        if (attribute == null) {
            throw diagnostic(
                    MarkupException.Kind.MISSING_ATTRIBUTE,
                    raw.origin(), raw.origin().elementPath(),
                    "<" + raw.tag() + "> requires attribute \"" + name + "\"",
                    "required attribute \"" + name + "\"", "", name, trace);
        }
        return attribute;
    }

    private static boolean booleanAttribute(
            RawElement raw,
            String name,
            boolean defaultValue,
            List<ComponentTraceFrame> trace) {
        RawAttribute attribute = raw.attrs().get(name);
        if (attribute == null) {
            return defaultValue;
        }
        if (!"true".equals(attribute.value()) && !"false".equals(attribute.value())) {
            throw invalid(
                    attribute.origin(), raw.origin().elementPath(),
                    "invalid value for \"" + name + "\"",
                    "true or false", attribute.value(), name, trace);
        }
        return Boolean.parseBoolean(attribute.value());
    }

    private static void requireOnlyAttributes(
            RawElement raw, Set<String> allowed, List<ComponentTraceFrame> trace) {
        for (String attribute : raw.attrs().keySet()) {
            if (!allowed.contains(attribute)) {
                throw invalid(
                        raw.attrs().get(attribute).origin(), raw.origin().elementPath(),
                        "unknown attribute \"" + attribute + "\" on <" + raw.tag() + ">",
                        allowed.isEmpty() ? "no attributes" : String.join(", ", allowed),
                        attribute, attribute, trace);
            }
        }
    }

    private static void rejectReservedTree(RawElement raw) {
        if (DEFINITION_TAGS.contains(raw.tag())
                || "use".equals(raw.tag())
                || "slot".equals(raw.tag())
                || "fill".equals(raw.tag())) {
            throw invalid(
                    raw.origin(), raw.origin().elementPath(),
                    "<" + raw.tag() + "> requires an <ui> document component context",
                    "a concrete actor tag", raw.tag(), "", List.of());
        }
        for (RawElement child : raw.children()) {
            rejectReservedTree(child);
        }
    }

    private static List<ComponentTraceFrame> appendTrace(
            List<ComponentTraceFrame> trace, ComponentTraceFrame frame) {
        if (trace.size() >= MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH) {
            throw tooLarge(
                    frame.invocation(),
                    "component trace exceeds the "
                            + MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH + "-frame limit",
                    trace);
        }
        List<ComponentTraceFrame> result = new ArrayList<>(trace.size() + 1);
        result.addAll(trace);
        result.add(frame);
        return List.copyOf(result);
    }

    private static RawElement withTrace(
            RawElement raw, List<ComponentTraceFrame> trace) {
        return copy(raw, raw.attrs(), raw.text(), raw.children(), trace);
    }

    private static RawElement copy(
            RawElement raw,
            Map<String, RawAttribute> attrs,
            String text,
            List<RawElement> children,
            List<ComponentTraceFrame> trace) {
        return new RawElement(raw.tag(), attrs, text, children, raw.origin(), trace);
    }

    private static String displaySlot(String name) {
        return name.isEmpty() ? "<default>" : name;
    }

    private static MarkupException invalid(
            MarkupSourceLocation origin,
            String path,
            String message,
            String expected,
            String received,
            String attribute,
            List<ComponentTraceFrame> trace) {
        return diagnostic(
                MarkupException.Kind.INVALID_VALUE,
                origin, path, message, expected, received, attribute, trace);
    }

    private static MarkupException tooLarge(
            MarkupSourceLocation origin, String message, List<ComponentTraceFrame> trace) {
        return diagnostic(
                MarkupException.Kind.TOO_LARGE,
                origin, origin.elementPath(), message,
                "bounded component expansion", "limit exceeded", "", trace);
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
        return diagnostic(
                kind, origin, path, message, expected, received, attribute, "", trace);
    }

    private static MarkupException diagnostic(
            MarkupException.Kind kind,
            MarkupSourceLocation origin,
            String path,
            String message,
            String expected,
            String received,
            String attribute,
            String suggestion,
            List<ComponentTraceFrame> trace) {
        return new MarkupException(
                kind,
                path,
                origin.line(),
                origin.column(),
                message,
                new MarkupDiagnosticContext(
                        origin.source(), attribute, expected, received, suggestion,
                        "document rejected before Scene2D build", trace));
    }

    private record Parameter(String name, boolean required, String defaultValue) {}

    private record Slot(
            String name,
            boolean required,
            List<RawElement> fallback,
            MarkupSourceLocation origin) {
        private Slot {
            fallback = List.copyOf(fallback);
            Objects.requireNonNull(origin, "origin");
        }
    }

    private record Definition(
            String name,
            Map<String, Parameter> parameters,
            Map<String, Slot> slots,
            RawElement templateRoot,
            MarkupSourceLocation origin) {}

    private record ExpansionScope(
            Definition definition,
            RawElement invocation,
            Map<String, String> parameters,
            Map<String, List<RawElement>> fills,
            List<ComponentTraceFrame> trace) {}

    private static final class ExpansionBudget {
        private final int maxFinalElements;
        private final List<String> componentStack = new ArrayList<>();
        private int visits;
        private int concreteElements;

        private ExpansionBudget(int maxFinalElements) {
            this.maxFinalElements = maxFinalElements;
        }

        private void visit(
                MarkupSourceLocation origin, List<ComponentTraceFrame> trace) {
            if (++visits > MarkupParser.MAX_EXPANSION_WORK) {
                throw tooLarge(
                        origin,
                        "component expansion exceeds the " + MarkupParser.MAX_EXPANSION_WORK
                                + "-visit limit",
                        trace);
            }
        }

        private void addConcrete(
                MarkupSourceLocation origin, List<ComponentTraceFrame> trace) {
            if (++concreteElements > maxFinalElements) {
                throw tooLarge(
                        origin,
                        "expanded document exceeds the " + maxFinalElements + "-element limit",
                        trace);
            }
        }

        private void enterComponent(String name, RawElement invocation) {
            if (componentStack.contains(name)) {
                List<String> cycle = new ArrayList<>(componentStack.size() + 1);
                cycle.addAll(componentStack);
                cycle.add(name);
                throw diagnostic(
                        MarkupException.Kind.COMPONENT_CYCLE,
                        invocation.origin(), invocation.origin().elementPath(),
                        "component cycle: " + String.join(" -> ", cycle),
                        "an acyclic component graph", String.join(" -> ", cycle),
                        "component", invocation.componentTrace());
            }
            if (componentStack.size() >= MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH) {
                throw tooLarge(
                        invocation.origin(),
                        "component expansion exceeds the "
                                + MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH + "-level limit",
                        invocation.componentTrace());
            }
            componentStack.add(name);
        }

        private void exitComponent() {
            componentStack.removeLast();
        }
    }
}
