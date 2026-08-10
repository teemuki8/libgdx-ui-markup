# Reusable Markup Components Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded, GL-free reusable markup components with parameters, slots, semantic/root defaults, concrete-tree expansion, actionable provenance, transactional preview reload, and harness-visible semantics.

**Architecture:** Hardened SAX parsing first produces an internal immutable raw tree. A GL-free `ComponentCompiler` expands explicit `<use>` invocations into concrete raw elements, then `ConcreteElementCompiler` applies the existing tag/attribute/ID contract and returns the public immutable `Element` tree plus provenance. Preview, IDEA, Scene2D, harness, and runtime consumers continue to operate on concrete actors only.

**Tech Stack:** Java 25, JDK SAX, immutable Java records/collections, Gradle Wrapper 9.6.1, JUnit 5, libGDX 1.14.2 Scene2D, Jackson in preview tests, Kotlin/IntelliJ Platform, Xvfb/LWJGL3.

## Global Constraints

- Run all Gradle work through `./gradlew`; project Java compiles with JDK 25, `-Xlint:all`, and `-Werror`.
- Do not use preview or incubator Java APIs.
- Parsing, component validation, substitution, slot expansion, suggestion calculation, and provenance are GL-free and immutable; no Actor, Stage, libGDX collection, or backend type crosses into the parse result.
- `MarkupBuilder.build` and every Actor/Stage mutation remain render-thread-only.
- Components expand to exactly one concrete actor root. Dynamic component tags, multiple-root fragments, external imports, filesystem discovery, scripts, loops, conditionals, expressions, reflection, and arbitrary Java invocation remain rejected.
- Parameter substitution is compile-time `${name}` text replacement only. `{player.health}` remains opaque and does not become gameplay authority or bypass concrete attribute validation.
- Keep existing parser APIs callable and preserve component-free `Element` trees, failure kinds, paths, lines, and columns.
- Enforce exact limits: 256 definitions, 64 parameters/component, 32 slots/component, 32 substitutions/value, expansion depth 16, 10,000 final concrete elements, 100,000 expansion visits, and 16 trace frames.
- Source names are nonblank, control-character-free, and at most 4,096 UTF-16 units. Status strings remain at most 2,000 UTF-16 units; component trace is at most 16 frames and 16,384 aggregate UTF-16 units.
- Definitions stay local to the watched UI document. Preview reload continues through the existing 300 ms debounce and candidate transaction, retaining the last-good scene/runtime after failure.
- GDXCSS resolves only the expanded concrete tree. Component scoping uses an explicit template-root class; caller classes merge and cannot erase it.
- Update `docs/guides/agentic-cookbook.md` in the same change as the public markup/status contract. Add ADR 0005. Do not publish a release without a separate explicit request.

## File and responsibility map

- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupSourceLocation.java`: bounded source/path/line/column value.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentTraceFrame.java`: one immutable component invocation frame.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ElementProvenance.java`: element origin, per-attribute origins, and bounded trace.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupDiagnosticContext.java`: optional structured error fields transported by `MarkupException`.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/RawElement.java`: package-private SAX/raw-expansion node and raw attribute values.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ConcreteElementCompiler.java`: one authority for existing concrete tag/attribute/text/root/ID validation.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java`: definitions, arguments, root overrides, substitution, slots, nesting, and expansion budgets.
- `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/NearestSuggestion.java`: bounded deterministic unique-nearest lookup.
- `MarkupParser.java`: bounded input/decode/SAX orchestration and the raw → component → concrete pipeline.
- `MarkupDocument.java`, `MarkupException.java`: backward-callable public document/diagnostic contracts.
- Preview `MarkupStatus.java`: schema-v3 wire projection; `PreviewApp` remains the candidate transaction owner.
- IDEA `MarkupStatusLine.kt` and new `MarkupStatusPresentation.kt`: schema-v3 parsing and actionable display text.
- `samples/signin.xml` / `signin.gdxcss`: executable component/slot/nested-use fixture used by harness E2E.

---

### Task 1: Immutable provenance and structured diagnostic primitives

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupSourceLocation.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentTraceFrame.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ElementProvenance.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupDiagnosticContext.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupDocument.java:1-15`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupException.java:1-70`
- Create test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupDiagnosticTest.java`

**Interfaces:**
- Produces: `MarkupSourceLocation(String source, String elementPath, int line, int column)` with `MAX_SOURCE_LENGTH = 4096`, package-visible `validateSource(String)`, and `memory(path, line, column)`.
- Produces: `ComponentTraceFrame(String component, MarkupSourceLocation invocation)`.
- Produces: `ElementProvenance(MarkupSourceLocation origin, Map<String, MarkupSourceLocation> attributeOrigins, List<ComponentTraceFrame> componentTrace)` and `locationFor(String attribute)`.
- Produces: `MarkupDiagnosticContext(String source, String attribute, String expected, String received, String suggestion, String consequence, List<ComponentTraceFrame> componentTrace)` plus `EMPTY` and `MAX_FIELD_LENGTH = 4096` validation for every non-source string.
- Produces: `MarkupDocument(Element root, int byteLength, String source, Map<String, ElementProvenance> provenance)` while retaining `MarkupDocument(Element, int)`, plus `provenanceFor(String path)`.
- Produces: the existing `MarkupException` constructor plus an overload ending in `MarkupDiagnosticContext context`; direct accessors `source()`, `attribute()`, `expected()`, `received()`, `suggestion()`, `consequence()`, and `componentTrace()`.

- [ ] **Step 1: Write failing immutability, validation, compatibility, and accessor tests**

Add tests that construct the wished-for API and assert copies, old constructors, source validation, and structured access:

```java
@Test
void structuredDiagnosticRetainsBoundedImmutableContext() {
    MarkupSourceLocation invocation =
            new MarkupSourceLocation("hud.xml", "ui/use", 18, 3);
    ComponentTraceFrame frame = new ComponentTraceFrame("HealthBar", invocation);
    MarkupDiagnosticContext context = new MarkupDiagnosticContext(
            "hud.xml", "value", "finite float", "fast", "",
            "document rejected before Scene2D build", List.of(frame));
    MarkupException failure = new MarkupException(
            MarkupException.Kind.INVALID_VALUE, "ui/table/progressbar", 9, 9,
            "invalid value for \"value\"", context);

    assertEquals("hud.xml", failure.source());
    assertEquals("value", failure.attribute());
    assertEquals("finite float", failure.expected());
    assertEquals("fast", failure.received());
    assertEquals(List.of(frame), failure.componentTrace());
    assertThrows(UnsupportedOperationException.class,
            () -> failure.componentTrace().add(frame));
}

@Test
void legacyConstructorsRemainCallable() {
    Element root = new Element("ui", null, null, null, null,
            Map.of(), List.of(), List.of(), 1, 1);
    MarkupDocument document = new MarkupDocument(root, 5);
    MarkupException failure = new MarkupException(
            MarkupException.Kind.INVALID_VALUE, "ui", 1, 1, "bad");
    assertEquals("<memory>", document.source());
    assertTrue(document.provenance().isEmpty());
    assertEquals("", failure.source());
    assertTrue(failure.componentTrace().isEmpty());
}

@Test
void sourceIdentityRejectsBlankControlAndOversizedValues() {
    assertThrows(IllegalArgumentException.class,
            () -> new MarkupSourceLocation(" ", "ui", 1, 1));
    assertThrows(IllegalArgumentException.class,
            () -> new MarkupSourceLocation("bad\nname", "ui", 1, 1));
    assertThrows(IllegalArgumentException.class,
            () -> new MarkupSourceLocation("x".repeat(4097), "ui", 1, 1));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupDiagnosticTest' \
  --warning-mode=fail
```

Expected: compilation fails because the four records, document constructor/accessor, diagnostic overload, and accessors do not exist.

- [ ] **Step 3: Implement the four immutable records and compatibility constructors**

Use compact constructors that copy collections and validate nonnegative coordinates. Optional diagnostic strings normalize `null` to `""`; source validation is shared through `MarkupSourceLocation.validateSource(String)` rather than duplicated.

Core shapes:

```java
public record ComponentTraceFrame(String component, MarkupSourceLocation invocation) {
    public ComponentTraceFrame {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(invocation, "invocation");
    }
}

public record ElementProvenance(
        MarkupSourceLocation origin,
        Map<String, MarkupSourceLocation> attributeOrigins,
        List<ComponentTraceFrame> componentTrace) {
    public ElementProvenance {
        Objects.requireNonNull(origin, "origin");
        attributeOrigins = Map.copyOf(attributeOrigins);
        componentTrace = List.copyOf(componentTrace);
        if (componentTrace.size() > 16) {
            throw new IllegalArgumentException("componentTrace exceeds 16 frames");
        }
    }

    public MarkupSourceLocation locationFor(String attribute) {
        return attribute == null ? origin : attributeOrigins.getOrDefault(attribute, origin);
    }
}
```

Extend `MarkupDocument` with the four-component canonical record shape and retain the old descriptor:

```java
public MarkupDocument(Element root, int byteLength) {
    this(root, byteLength, "<memory>", Map.of());
}

public ElementProvenance provenanceFor(String path) {
    return provenance.get(path);
}
```

Add the eight new component kinds to `MarkupException.Kind` now so later parser tests compile:

```java
DUPLICATE_COMPONENT,
UNKNOWN_COMPONENT,
MISSING_PARAMETER,
UNKNOWN_PARAMETER,
DUPLICATE_SLOT,
UNKNOWN_SLOT,
MISSING_SLOT,
COMPONENT_CYCLE,
```

- [ ] **Step 4: Run focused and existing parser tests and verify GREEN**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupDiagnosticTest' \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail
```

Expected: PASS; existing callers compile unchanged.

- [ ] **Step 5: Commit the diagnostic foundation**

```bash
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupDiagnosticTest.java
git commit -m "feat: add structured markup provenance"
```

---

### Task 2: Split raw XML reading from concrete validation without changing behavior

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/RawElement.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ConcreteElementCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java:39-413`
- Modify test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java:1-430`

**Interfaces:**
- Consumes: Task 1 provenance records and legacy `TagSpec`/`ElementPathTracker` contracts.
- Produces package-private: `RawAttribute(String value, MarkupSourceLocation origin)`.
- Produces package-private: `RawElement(String tag, Map<String, RawAttribute> attrs, String text, List<RawElement> children, MarkupSourceLocation origin, List<ComponentTraceFrame> componentTrace)`.
- Produces package-private: `ConcreteElementCompiler(Set<String> extraTags, int maxElements)` with `MarkupDocument compile(RawElement root, int byteLength, String source)`.
- Produces public overload: `MarkupParser.parse(String xml, String sourceName)`.

- [ ] **Step 1: Add characterization tests before refactoring**

Pin the complete concrete tree and source behavior:

```java
@Test
void componentFreeDocumentKeepsItsConcreteShapeAndSemantics() {
    MarkupDocument document = parser.parse("""
            <ui>
              <table id="panel" class="Panel primary">
                <button id="save" text="Save" name="Save"/>
              </table>
            </ui>
            """, "screen.xml");
    Element panel = document.root().children().getFirst();
    Element save = panel.children().getFirst();
    assertEquals("screen.xml", document.source());
    assertEquals("panel", panel.id());
    assertEquals(List.of("panel", "primary"), panel.classes());
    assertEquals("Save", save.text());
    assertEquals("Save", save.name());
    assertEquals(3, save.line());
}

@Test
void pathParseUsesAbsoluteNormalizedSourceIdentity() throws Exception {
    Path file = tempDir.resolve("nested").resolve("..").resolve("screen.xml");
    Files.createDirectories(tempDir.resolve("nested"));
    Files.writeString(tempDir.resolve("screen.xml"), "<ui/>");
    MarkupDocument document = parser.parse(file);
    assertEquals(file.toAbsolutePath().normalize().toString(), document.source());
}
```

Retain the existing unknown tag/attribute, mixed text, duplicate ID, sibling path, size, UTF-8, DOCTYPE, and explicit-limit tests as the regression oracle.

- [ ] **Step 2: Run the characterization suite before refactoring**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail
```

Expected: the new source-name overload test fails to compile; all pre-existing parser tests remain green after temporarily excluding that method.

- [ ] **Step 3: Make SAX produce bounded raw nodes only**

Move tag vocabulary, required-attribute, value grammar, duplicate-ID, and final-root checks from `Handler` into `ConcreteElementCompiler`. Keep these checks in the SAX handler because they are input-boundary concerns independent of the dialect:

- raw element count before allocation;
- raw XML nesting depth;
- attribute length;
- text length and mixed-content rejection;
- one XML root;
- strict UTF-8 and external entity/DTD disabling.

Each raw attribute records the containing start-element location. `Handler.document()` returns the one `RawElement`, not `MarkupDocument`.

Use these package-private record shapes. Preserve raw attribute insertion order because the
existing parser's first failing attribute is part of compatibility:

```java
record RawAttribute(String value, MarkupSourceLocation origin) {
    RawAttribute {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(origin, "origin");
    }
}

record RawElement(
        String tag,
        Map<String, RawAttribute> attrs,
        String text,
        List<RawElement> children,
        MarkupSourceLocation origin,
        List<ComponentTraceFrame> componentTrace) {
    RawElement {
        Objects.requireNonNull(tag, "tag");
        attrs = Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        text = Objects.requireNonNull(text, "text");
        children = List.copyOf(children);
        Objects.requireNonNull(origin, "origin");
        componentTrace = List.copyOf(componentTrace);
    }
}
```

- [ ] **Step 4: Implement the concrete compiler as the single existing-dialect authority**

`ConcreteElementCompiler.compileElement` must traverse with a fresh `ElementPathTracker`, validate in the same order as the old SAX handler, build the exact existing `Element`, and add provenance under the final path:

```java
private Element compileElement(RawElement raw) {
    String path = paths.enter(raw.tag());
    try {
        TagSpec spec = TagSpec.require(raw.tag(), extraTags, path,
                raw.origin().line(), raw.origin().column());
        LinkedHashMap<String, String> attrs = validateAttributes(raw, spec, path);
        requireAttributes(raw, spec, attrs, path);
        rejectDuplicateId(raw, attrs.get("id"), path);
        List<Element> children = raw.children().stream()
                .map(this::compileElement).toList();
        Element element = element(raw, attrs, children);
        provenance.put(path, provenance(raw, path));
        return element;
    } finally {
        paths.exit();
    }
}
```

Do not use streams if they obscure checked failure ordering; document order and the old first-failure order are authoritative.

- [ ] **Step 5: Wire the source-aware raw → concrete pipeline**

`parse(String)` delegates to `parse(xml, "<memory>")`. `parse(Path)` computes the absolute normalized source before opening the file. `parseUtf8` becomes:

```java
public MarkupDocument parse(String xml, String sourceName) {
    String source = MarkupSourceLocation.validateSource(sourceName);
    Objects.requireNonNull(xml, "xml");
    byte[] utf8 = xml.getBytes(StandardCharsets.UTF_8);
    requireInputLimit(utf8.length, source);
    return parseUtf8(utf8.length, xml, source);
}

private MarkupDocument parseUtf8(int byteLength, String xml, String source) {
    RawElement raw = readRaw(xml, source);
    return new ConcreteElementCompiler(extraTags, maxElements)
            .compile(raw, byteLength, source);
}
```

`parse(Path)` validates the normalized source string before `Files.newInputStream`, satisfying the
source trust boundary before content read or parse work.

Malformed SAX failures must construct structured exceptions with source but retain existing kind/path/line/column/message.

- [ ] **Step 6: Run parser and full core tests after refactoring**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail --rerun-tasks
./gradlew :libgdx-ui-markup:test --warning-mode=fail
```

Expected: PASS with no changes to existing component-free assertions.

- [ ] **Step 7: Commit the behavior-preserving parser split**

```bash
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/RawElement.java \
  libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ConcreteElementCompiler.java \
  libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java
git commit -m "refactor: split raw and concrete markup parsing"
```

---

### Task 3: Define and invoke components with parameters and root overrides

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java:28-150`
- Create test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java`

**Interfaces:**
- Consumes: Task 2 `RawElement` and `ConcreteElementCompiler`.
- Produces: public constants on `MarkupParser`: `MAX_COMPONENTS = 256`, `MAX_COMPONENT_PARAMETERS = 64`, `MAX_COMPONENT_SLOTS = 32`, `MAX_SUBSTITUTIONS_PER_VALUE = 32`, `MAX_COMPONENT_EXPANSION_DEPTH = 16`, `MAX_EXPANSION_WORK = 100_000`.
- Produces package-private: `ComponentCompiler(int maxFinalElements, int maxAttributeValue, int maxText)` with `RawElement expand(RawElement documentRoot)` so explicit parser limits remain authoritative after substitution.
- Produces package-private: `TagSpec.isCommonAttribute(String)` used only for `<use>` root overrides.

- [ ] **Step 1: Write failing happy-path parameter/root-override tests**

Create the test class with the parser used by every case:

```java
final class MarkupComponentParserTest {
    private final MarkupParser parser = new MarkupParser();
}
```

```java
@Test
void expandsParametersTextAndRootOverridesIntoOnlyConcreteElements() {
    MarkupDocument document = parser.parse("""
            <ui>
              <components>
                <component name="PrimaryButton">
                  <param name="id" required="true"/>
                  <param name="text" default="Continue"/>
                  <button class="component-button" id="${id}-template"
                          text="${text}" name="${text}" width="100"/>
                </component>
              </components>
              <use component="PrimaryButton" id="save" text="Save"
                   class="wide component-button" width="180" data-screen="menu"/>
            </ui>
            """);
    Element button = document.root().children().getFirst();
    assertEquals("button", button.tag());
    assertEquals("save", button.id());
    assertEquals("Save", button.text());
    assertEquals("180", button.attr("width"));
    assertEquals("menu", button.attr("data-screen"));
    assertEquals(List.of("component-button", "wide"), button.classes());
    assertTrue(document.root().children().stream()
            .noneMatch(child -> Set.of("components", "component", "param", "use")
                    .contains(child.tag())));
}

@Test
void defaultAndOptionalParametersResolveDeterministically() {
    MarkupDocument document = parser.parse("""
            <ui><components><component name="Badge">
              <param name="prefix"/>
              <param name="text" default="Ready"/>
              <label text="${prefix}${text}"/>
            </component></components><use component="Badge"/></ui>
            """);
    assertEquals("Ready", document.root().children().getFirst().text());
}
```

Add failing cases for component placement, PascalCase name grammar, duplicate definitions,
parameter grammar/duplicates, `required`+`default`, missing required parameter, unknown
component, unknown argument, invalid/multiple/zero roots, and root override invalid on the final
tag.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupComponentParserTest' \
  --warning-mode=fail
```

Expected: `UNKNOWN_TAG` for `<components>`/`<use>` or compilation failure for missing limit constants.

- [ ] **Step 3: Implement definition indexing and validation**

Use nested immutable records inside `ComponentCompiler`:

```java
private record Parameter(String name, boolean required, String defaultValue) {}

private record Definition(
        String name,
        Map<String, Parameter> parameters,
        RawElement templateRoot,
        MarkupSourceLocation origin) {}
```

Validate the `<ui>` placement before removing the `<components>` child. Index definitions in a `LinkedHashMap` for source-stable diagnostics. A `<table>` root bypasses expansion unless it contains a reserved component element, which fails located `INVALID_VALUE`.

- [ ] **Step 4: Implement bounded parameter substitution and root overrides**

Use the exact token pattern `\$\{([a-z][a-z0-9-]{0,63})}`. Scan left-to-right without regex replacement so replacement text containing `$` or backslashes stays literal. Count matches before each append and reject the 33rd with `TOO_LARGE`. Validate the expanded length after every append against the parser attribute/text maximum.

`expandUse` must:

1. resolve the definition;
2. partition attributes into `component`, parameters, common root overrides, and `data-*`;
3. resolve required/default/empty parameter values;
4. clone and substitute the template under a pushed trace frame;
5. require one expanded concrete root;
6. merge class tokens in first-seen order and override other root attributes with invocation-origin `RawAttribute` values.

Add `TagSpec.isCommonAttribute` as:

```java
static boolean isCommonAttribute(String attribute) {
    return COMMON_KINDS.containsKey(attribute);
}
```

An attribute whose name is both a declared parameter and a common root override is deliberately
recorded in both sets; its one invocation value is available to `${name}` and is applied to the
expanded root.

- [ ] **Step 5: Insert component compilation between raw read and concrete validation**

```java
RawElement raw = readRaw(xml, source);
RawElement expanded = new ComponentCompiler(
        maxElements, maxAttributeValue, maxText).expand(raw);
return new ConcreteElementCompiler(extraTags, maxElements)
        .compile(expanded, byteLength, source);
```

- [ ] **Step 6: Run component, compatibility, and GL-free core tests**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupComponentParserTest' \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS; no Gdx application is created by these tests.

- [ ] **Step 7: Commit parameterized component expansion**

```bash
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java \
  libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java \
  libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java
git commit -m "feat: expand parameterized markup components"
```

---

### Task 4: Add named/default slots, nested components, cycles, and all expansion budgets

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java`
- Modify test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java`

**Interfaces:**
- Consumes: Task 3 `ComponentCompiler.expand` and parameter scope.
- Produces internal: `Slot(String name, boolean required, List<RawElement> fallback, MarkupSourceLocation origin)` and `ExpansionBudget` with `visit()`, `enterComponent()`, `addConcrete(int)`.
- Produces internal: `ExpansionScope(Definition definition, RawElement invocation, Map<String, String> parameters, Map<String, List<RawElement>> fills, List<ComponentTraceFrame> trace)` with `withoutCalleeParameters()` returning a copy whose parameter map is empty.
- Produces helpers: `invalid(origin, message)`, `missingSlot(invocation, name, trace)`, and `tooLarge(origin, message)` returning structured `MarkupException` values.
- Preserves: one public concrete root and lexical parameter scope.

- [ ] **Step 1: Write failing slot, nesting, cycle, and budget tests**

Include these positive cases:

```java
@Test
void fillsNamedAndDefaultSlotsAndUsesFallbacks() {
    MarkupDocument document = parser.parse("""
            <ui><components><component name="Panel">
              <param name="title" required="true"/>
              <table class="panel">
                <label text="${title}"/>
                <slot/>
                <slot name="footer"><label text="Default footer"/></slot>
              </table>
            </component></components>
            <use component="Panel" title="Inventory">
              <fill><label id="item" text="Potion"/></fill>
              <fill slot="footer"><button id="close" text="Close"/></fill>
            </use></ui>
            """);
    Element panel = document.root().children().getFirst();
    assertEquals(List.of("Inventory", "Potion", "Close"),
            panel.children().stream().map(Element::text).toList());
}

@Test
void nestedComponentsExpandDepthFirst() {
    MarkupDocument document = parser.parse(NESTED_COMPONENT_XML);
    assertEquals(List.of("label", "button"),
            document.root().children().getFirst().children().stream()
                    .map(Element::tag).toList());
}
```

Define the nested fixture used above:

```java
private static final String NESTED_COMPONENT_XML = """
        <ui><components>
          <component name="Action"><button text="Act"/></component>
          <component name="Card"><table><label text="Card"/><use component="Action"/></table></component>
        </components><use component="Card"/></ui>
        """;
```

Add one focused assertion for each failure: duplicate/default slot, unknown fill, duplicate fill,
missing required slot, required slot with fallback, raw child directly under `<use>`, direct cycle,
indirect cycle with `Menu -> MenuItem -> Menu`, depth 17, 257 components, 65 parameters, 33
slots, 33 substitutions, 10,001 final actors, and 100,001 expansion visits. Generate large XML
with bounded test helpers; do not hand-copy thousands of lines.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupComponentParserTest' \
  --warning-mode=fail
```

Expected: slot elements reach concrete validation as unknown tags; cycle/budget assertions fail.

- [ ] **Step 3: Validate slot declarations once per definition**

Walk each template in source order, index omitted names as `""`, reject the 33rd slot, and reject duplicate names. Required slots reject fallback children. Store the immutable slot map in `Definition`.

```java
private record Slot(
        String name,
        boolean required,
        List<RawElement> fallback,
        MarkupSourceLocation origin) {
    private Slot {
        fallback = List.copyOf(fallback);
        Objects.requireNonNull(origin, "origin");
        if (required && !fallback.isEmpty()) {
            throw invalid(origin, "required slot cannot declare fallback children");
        }
    }
}
```

- [ ] **Step 4: Expand fills with lexical scope**

At invocation, accept only direct `<fill>` children. Validate each fill's sole optional `slot` attribute, reject duplicates/unknown names, and require fills for required slots. During template cloning:

- a filled slot clones caller nodes without callee substitutions;
- a fallback clones template nodes with callee substitutions;
- an empty optional slot returns an empty list;
- nested `<use>` invokes recursively after the correct outer substitution has completed.

Use `List<RawElement> expandNode(...)` internally because slots expand to zero or many children; keep `expandUse(...)` enforcing one root.

```java
private List<RawElement> expandSlot(RawElement slot, ExpansionScope scope) {
    String name = attribute(slot, "name", "");
    List<RawElement> fill = scope.fills().get(name);
    if (fill != null) {
        return expandCallerNodes(fill, scope.withoutCalleeParameters());
    }
    Slot declared = scope.definition().slots().get(name);
    if (declared.required()) {
        throw missingSlot(scope.invocation(), name, scope.trace());
    }
    return expandTemplateNodes(declared.fallback(), scope);
}
```

- [ ] **Step 5: Add cycle and work budgets before recursion/allocation**

`ExpansionBudget` owns counters and a component stack. `enterComponent` checks depth before push and checks an existing name to produce `COMPONENT_CYCLE`. `visit` increments before processing every component/template/slot/fill/substituted node. `addConcrete` checks before appending final nodes. Every pop occurs in `finally`.

```java
private void visit(MarkupSourceLocation origin) {
    if (++visits > MarkupParser.MAX_EXPANSION_WORK) {
        throw tooLarge(origin, "component expansion exceeds the 100000-visit limit");
    }
}

private void addConcrete(MarkupSourceLocation origin) {
    if (++concreteElements > maxFinalElements) {
        throw tooLarge(origin, "expanded document exceeds the "
                + maxFinalElements + "-element limit");
    }
}
```

Cycle message construction is bounded by the 16-frame stack:

```java
String cycle = Stream.concat(stack.stream(), Stream.of(name))
        .collect(Collectors.joining(" -> "));
```

If stream use makes stack order unclear, build the string with an indexed loop over a copied list; outermost-to-innermost order is required.

- [ ] **Step 6: Run component and parser suites**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupComponentParserTest' \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS for every positive and boundary case; the core test JVM remains GL-free.

- [ ] **Step 7: Commit slots and bounded nesting**

```bash
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java
git commit -m "feat: add bounded component slots and nesting"
```

---

### Task 5: Propagate actionable component provenance and deterministic suggestions

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/NearestSuggestion.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ConcreteElementCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupException.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupDocument.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java`
- Modify test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java`
- Modify test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupDiagnosticTest.java`

**Interfaces:**
- Produces package-private: `NearestSuggestion.unique(String received, Collection<String> candidates)` returning `Optional<String>`.
- Produces package-private: `TagSpec.expected(ValueKind kind)` returning the stable grammar phrase used by structured diagnostics.
- Preserves public: final concrete path in `MarkupException.elementPath()` and origin/trace in structured fields.

- [ ] **Step 1: Write failing provenance and suggestion tests**

```java
@Test
void substitutedConcreteFailureCarriesTemplateOriginAndInvocationTrace() {
    MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
            <ui><components><component name="HealthBar">
              <param name="current" required="true"/>
              <progressbar min="0" max="100" value="${current}"/>
            </component></components>
            <table><use component="HealthBar" current="fast"/></table></ui>
            """, "hud.xml"));
    assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
    assertEquals("ui/table/progressbar", failure.elementPath());
    assertEquals("hud.xml", failure.source());
    assertEquals("value", failure.attribute());
    assertEquals("finite float", failure.expected());
    assertEquals("fast", failure.received());
    assertEquals("document rejected before Scene2D build", failure.consequence());
    assertEquals(List.of("HealthBar"), failure.componentTrace().stream()
            .map(ComponentTraceFrame::component).toList());
    assertEquals(3, failure.line(), "the template attribute origin is reported");
}

@Test
void uniqueNearestSuggestionUsesTheSpecifiedThresholdAndTieRule() {
    assertEquals(Optional.of("HealthBar"), NearestSuggestion.unique(
            "HealthBr", List.of("HealthBar", "ManaBar")));
    assertEquals(Optional.empty(), NearestSuggestion.unique(
            "Cat", List.of("Bat", "Hat")));
    assertEquals(Optional.empty(), NearestSuggestion.unique(
            "unrelated", List.of("HealthBar")));
}
```

Also assert invocation-root override errors use the `<use>` attribute origin, slot-fill errors use
caller origin, fallback errors use template origin, and nested traces are outermost-first and
capped. Component-free parser diagnostics retain their old kind/path/line/column while gaining
`<memory>` or caller-supplied source and structured attribute/expected/received fields where known;
exceptions built through the legacy constructor retain empty optional context.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupComponentParserTest' \
  --tests 'dev.gdx.markup.core.MarkupDiagnosticTest' \
  --warning-mode=fail
```

Expected: context/suggestion assertions fail because component compilation has not projected them.

- [ ] **Step 3: Implement deterministic bounded Levenshtein lookup**

Sort a defensive candidate copy lexicographically. Compute rows using two `int[]` arrays bounded
by names of at most 64 characters. Threshold is exactly:

```java
int threshold = Math.max(1, Math.min(3, received.length() / 3));
```

Track the best distance and whether it ties. Return a value only when the unique best is within
threshold. Do not allocate or retain a full distance matrix.

- [ ] **Step 4: Preserve origin per raw attribute and append trace frames while cloning**

Template attributes retain template origins. Root overrides retain invocation origin. Caller fill
nodes retain caller origins. Each nested invocation appends one immutable `ComponentTraceFrame`;
reject before frame 17. `ConcreteElementCompiler` writes `ElementProvenance` under the final path
and, on failure, chooses `raw.attrs().get(attribute).origin()` before the element origin.

```java
private RawAttribute substituteAttribute(
        RawAttribute attribute, Map<String, String> parameters) {
    return new RawAttribute(
            substitute(attribute.value(), parameters, attribute.origin()),
            attribute.origin());
}

private static List<ComponentTraceFrame> appendTrace(
        List<ComponentTraceFrame> trace, ComponentTraceFrame frame) {
    if (trace.size() >= MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH) {
        throw tooLarge(frame.invocation(), "component trace exceeds 16 frames");
    }
    ArrayList<ComponentTraceFrame> appended = new ArrayList<>(trace);
    appended.add(frame);
    return List.copyOf(appended);
}
```

Add `TagSpec.expected(ValueKind)` as an exhaustive switch (`BOOLEAN` → `true or false`,
`FLOAT` → `finite float`, `FONT_SIZE` → `integer from 4 through 256`, and equivalently exact
phrases for every existing kind). `ConcreteElementCompiler` uses this method rather than parsing
the existing validation-message prose.

- [ ] **Step 5: Populate exact structured component failures**

For each typed failure, set:

- `source`: chosen origin source;
- `attribute`: `component`, parameter name, `slot`, or final concrete attribute;
- `expected`: exact grammar or sorted valid-name set, bounded;
- `received`: exact bounded source value;
- `suggestion`: `NearestSuggestion.unique(...).orElse("")`;
- `consequence`: `document rejected before Scene2D build`;
- trace: current outermost-first frames.

Keep prose messages concise and do not duplicate path or line/column already in fields.

Construct failures through one exact helper so every caller supplies the same semantic
consequence and immutable trace:

```java
private static MarkupException failure(
        MarkupException.Kind kind,
        String finalPath,
        MarkupSourceLocation origin,
        String attribute,
        String expected,
        String received,
        String suggestion,
        List<ComponentTraceFrame> trace,
        String message) {
    MarkupDiagnosticContext context = new MarkupDiagnosticContext(
            origin.source(), attribute, expected, received, suggestion,
            "document rejected before Scene2D build", trace);
    return new MarkupException(kind, finalPath, origin.line(), origin.column(), message, context);
}
```

- [ ] **Step 6: Run all GL-free core tests and Javadocs**

```bash
./gradlew :libgdx-ui-markup:test :libgdx-ui-markup:javadoc \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS with no warnings.

- [ ] **Step 7: Commit actionable component diagnostics**

```bash
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupComponentParserTest.java \
  libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupDiagnosticTest.java
git commit -m "feat: add actionable component diagnostics"
```

---

### Task 6: Publish schema-v3 preview diagnostics and prove transactional component reload

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/MarkupStatus.java:1-160`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java:139-220`
- Modify test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/MarkupStatusTest.java:1-190`
- Modify test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java:220-275`
- Modify test child: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewTestChild.java:55-360`

**Interfaces:**
- Consumes: Task 5 structured `MarkupException` and component trace.
- Produces: `MarkupStatus.SCHEMA_VERSION = 3` with fields `source`, `attribute`, `expected`, `received`, `suggestion`, `consequence`, and `List<ComponentTraceFrame> componentTrace`.
- Preserves: `MarkupStatus.ok(int)`, `error(MarkupException)`, `error(String)`, and `terminal(String)` factories.

- [ ] **Step 1: Write failing schema-v3 JSON tests**

Extend `MarkupStatusTest` to assert the exact failure shape:

```java
@Test
void componentFailureJsonCarriesBoundedStructuredContext() throws Exception {
    MarkupException failure = componentFailure();
    JsonNode node = JSON.readTree(MarkupStatus.error(failure).json());
    assertEquals(3, node.path("schemaVersion").asInt());
    assertEquals("hud.xml", node.path("source").asText());
    assertEquals("value", node.path("attribute").asText());
    assertEquals("finite float", node.path("expected").asText());
    assertEquals("fast", node.path("received").asText());
    assertEquals("HealthBar", node.path("componentTrace").get(0)
            .path("component").asText());
    assertEquals("ui/use", node.path("componentTrace").get(0)
            .path("elementPath").asText());
}
```

Define the fixture exception in the same test class:

```java
private static MarkupException componentFailure() {
    ComponentTraceFrame frame = new ComponentTraceFrame(
            "HealthBar", new MarkupSourceLocation("hud.xml", "ui/use", 18, 3));
    MarkupDiagnosticContext context = new MarkupDiagnosticContext(
            "hud.xml", "value", "finite float", "fast", "",
            "document rejected before Scene2D build", List.of(frame));
    return new MarkupException(MarkupException.Kind.INVALID_VALUE,
            "ui/table/progressbar", 9, 9, "invalid value", context);
}
```

Add constructor invariant tests for success carrying no error context, generic errors carrying
empty context, per-string surrogate-safe truncation, 16-frame limit, and 16,384-unit trace aggregate.

- [ ] **Step 2: Run status tests and verify RED**

```bash
./gradlew :libgdx-ui-markup-preview:test \
  --tests 'dev.gdx.markup.preview.MarkupStatusTest' \
  --warning-mode=fail
```

Expected: schema is 2 and structured JSON fields are absent.

- [ ] **Step 3: Extend the immutable status record and JSON ordering**

Project `MarkupException` fields directly in `error(MarkupException)`. Preserve deterministic
field order:

```text
schemaVersion, ok, kind, source, elementPath, line, column, attribute,
expected, received, suggestion, consequence, componentTrace, message
```

Each trace JSON object orders `component, source, elementPath, line, column`. Success still emits
only `schemaVersion`, `ok`, and `nodes`. Generic/terminal failures emit empty structured strings
and an empty trace. Serialize the populated `LinkedHashMap` directly; do not wrap it in
`Map.copyOf`, whose iteration order is not the wire-order contract.

- [ ] **Step 4: Add a real component-failure/recovery child scenario**

Add `COMPONENT_GOOD_UI` containing a component-generated `textfield` with
`data-runtime-entity="user"`, and `COMPONENT_BAD_UI` invoking the same component with an unknown
parameter. The `component-reload` scenario must:

1. commit good actor, Skin, and runtime registration;
2. write bad markup and call `app.rebuild()`;
3. assert overlay visible and the exact old Skin/actor/runtime owner retained;
4. write corrected markup and rebuild;
5. assert overlay hidden and a fresh scene/runtime owner committed;
6. render and exit through observable frame state, without sleeps.

Add the parent test:

```java
@Test
@Timeout(120)
void componentExpansionFailureKeepsLastGoodRuntimeAndRecovers() throws Exception {
    requireGl();
    Path ui = tempDir.resolve("component-reload.xml");
    Path css = tempDir.resolve("component-reload.gdxcss");
    Files.writeString(css, "/* component reload */", StandardCharsets.UTF_8);
    try (PreviewTestProcess child = PreviewTestProcess.launch(
            "component-reload", ui, css, null, Duration.ofSeconds(60))) {
        int exit = child.await();
        String stderr = child.stderr();
        assertEquals(0, exit, stderr);
        assertTrue(child.stdout().contains("preview-child: component-reload ok"));
        assertTrue(stderr.contains("\"schemaVersion\":3"), stderr);
        assertTrue(stderr.contains("\"kind\":\"UNKNOWN_PARAMETER\""), stderr);
        assertTrue(stderr.contains("\"received\":\"vale\""), stderr);
        assertTrue(stderr.contains("\"suggestion\":\"value\""), stderr);
    }
}
```

The bad fixture passes `vale="B"` to a component declaring `value`, yielding the deterministic
suggestion asserted above. The child creates `PreviewApp` with `--mcp` so the good component owns
a real runtime registration. Schema assertions stay in the parent because `PreviewTestProcess`
owns and captures the child stderr stream.

- [ ] **Step 5: Run focused preview tests under Xvfb**

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test \
  --tests 'dev.gdx.markup.preview.MarkupStatusTest' \
  --tests 'dev.gdx.markup.preview.PreviewAppTest.componentExpansionFailureKeepsLastGoodRuntimeAndRecovers' \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS; child exits cleanly and no GL state enters the parent test JVM.

- [ ] **Step 6: Commit schema-v3 preview projection and reload proof**

```bash
git add libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview \
  libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview
git commit -m "feat: publish component diagnostics in preview status"
```

---

### Task 7: Parse and display schema-v3 diagnostics in the IntelliJ plugin

**Files:**
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupStatusLine.kt:1-180`
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupStatusPresentation.kt`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupPreviewPanel.kt:100-123`
- Modify test: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/MarkupStatusLineParserTest.kt:1-155`
- Create test: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/MarkupStatusPresentationTest.kt`

**Interfaces:**
- Produces Kotlin: `MarkupStatusTraceFrame(component, source, elementPath, line, column)`.
- Extends `MarkupStatusLine` with six context strings and `componentTrace: List<MarkupStatusTraceFrame>`.
- Produces: `MarkupStatusPresentation.text(MarkupStatusLine): String`, bounded by panel to 300 characters.

- [ ] **Step 1: Write failing parser and presentation tests**

```kotlin
@Test
fun parsesSchemaThreeComponentContextAndTrace() {
    val parsed = MarkupStatusLineParser.parse(
        "markup-status: {\"schemaVersion\":3,\"ok\":false,"
            + "\"kind\":\"INVALID_VALUE\",\"source\":\"hud.xml\","
            + "\"elementPath\":\"ui/table/progressbar\",\"line\":9,\"column\":9,"
            + "\"attribute\":\"value\",\"expected\":\"finite float\","
            + "\"received\":\"fast\",\"suggestion\":\"\","
            + "\"consequence\":\"document rejected before Scene2D build\","
            + "\"componentTrace\":[{\"component\":\"HealthBar\","
            + "\"source\":\"hud.xml\",\"elementPath\":\"ui/use\","
            + "\"line\":18,\"column\":3}],\"message\":\"invalid value\"}")
    assertEquals(3, parsed?.schemaVersion)
    assertEquals("hud.xml", parsed?.source)
    assertEquals("value", parsed?.attribute)
    assertEquals("HealthBar", parsed?.componentTrace?.single()?.component)
}

@Test
fun presentsSourceExpectedReceivedSuggestionAndTrace() {
    val text = MarkupStatusPresentation.text(componentFailureStatus())
    assertTrue(text.contains("hud.xml:9:9"))
    assertTrue(text.contains("value"))
    assertTrue(text.contains("finite float"))
    assertTrue(text.contains("fast"))
    assertTrue(text.contains("HealthBar"))
}
```

Define the presentation fixture with named arguments so its fields stay consistent with the
schema-v3 model:

```kotlin
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
```

Change the existing future-schema test to use version 4; schema 2 becomes unsupported with the
same actionable update message.

- [ ] **Step 2: Run IDEA focused tests and verify RED**

```bash
./gradlew :libgdx-ui-markup-idea:test \
  --tests 'dev.gdx.markup.idea.MarkupStatusLineParserTest' \
  --tests 'dev.gdx.markup.idea.MarkupStatusPresentationTest' \
  --warning-mode=fail
```

Expected: schema 3 is reported unsupported and the new fields/types do not compile.

- [ ] **Step 3: Extend the bounded scanner for one array of flat objects**

Keep the no-dependency scanner but add bracket/string/escape-aware token extraction. `value` must
track quoted strings plus `{}`/`[]` nesting before treating comma or closing brace as the end.
`objectArray(json, "componentTrace")` accepts at most 16 objects and returns `null` on malformed,
negative-location, missing-field, or extra-nesting input. It uses the existing `string`/`int`
helpers on each isolated object.

```kotlin
private fun trace(json: String): List<MarkupStatusTraceFrame>? {
    val objects = objectArray(json, "componentTrace", maxObjects = 16) ?: return null
    val frames = objects.map { objectJson ->
        MarkupStatusTraceFrame(
            component = string(objectJson, "component") ?: return null,
            source = string(objectJson, "source") ?: return null,
            elementPath = string(objectJson, "elementPath") ?: return null,
            line = int(objectJson, "line") ?: return null,
            column = int(objectJson, "column") ?: return null,
        )
    }
    return frames.takeIf { frames.sumOf { it.source.length + it.elementPath.length } <= 16_384 }
}
```

Reject any schema-v3 string longer than 2,000 UTF-16 units before constructing the status model;
the parser must not trust that a manually launched child obeyed preview bounds.

- [ ] **Step 4: Implement pure actionable presentation and wire the panel**

Presentation order is source location, raw message, expected/received, suggestion when nonempty,
consequence when nonempty, and `via Component at source:line:column` frames. Success remains
`ok (N actors)`. `MarkupPreviewPanel.showStatus` becomes:

```kotlin
private fun showStatus(parsed: MarkupStatusLine) {
    setStatus(
        MarkupStatusPresentation.text(parsed).take(MAX_STATUS_LENGTH),
        parsed.ok,
    )
}
```

- [ ] **Step 5: Run IDEA check and plugin build**

```bash
./gradlew :libgdx-ui-markup-idea:check \
  :libgdx-ui-markup-idea:buildPlugin \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS; bundled preview and IDEA JAR are present in the plugin ZIP.

- [ ] **Step 6: Commit IDEA schema-v3 support**

```bash
git add libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea \
  libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea
git commit -m "feat: display component diagnostics in idea"
```

---

### Task 8: Prove Scene2D/harness integration and publish the component contract

**Files:**
- Modify test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`
- Modify test: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java:49-140`
- Modify test helper: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/PreviewProcess.java:25-115`
- Modify fixture: `samples/signin.xml`
- Modify fixture: `samples/signin.gdxcss`
- Modify: `README.md`
- Modify: `docs/guides/agentic-cookbook.md`
- Create: `docs/adr/0005-parse-time-markup-components.md`

**Interfaces:**
- Consumes: Tasks 3-7 public syntax and schema-v3 contract.
- Produces: one executable canonical sample used by preview and harness E2E.
- Preserves: existing sample actor IDs/count (10), strict role/name/testId queries, widget-mirror runtime comparison, and screenshot size.

- [ ] **Step 1: Add a real Xvfb builder test for native layout, CSS, and semantics**

Add a recording sink and component-backed HUD:

```java
@Test
void componentGeneratedHudUsesConcreteCssLayoutAndSemantics() throws Exception {
    GdxTestHost.run(() -> {
        Skin skin = DefaultSkin.create();
        try {
            FakeSink sink = new FakeSink();
            MarkupDocument document = markup.parse(COMPONENT_HUD_XML, "hud.xml");
            BuiltUi built = MarkupBuilder.build(document,
                    css.parse(".health-bar progressbar { width: 100%; }"),
                    skin, sink);
            Table health = built.root().findActor("player-health");
            ProgressBar bar = built.root().findActor("player-health-bar");
            assertNotNull(health);
            assertNotNull(bar);
            assertEquals("progressbar", sink.roles.get("player-health-bar"));
            Value prefWidth = health.getCell(bar).getPrefWidth();
            health.setSize(400f, 100f);
            assertEquals(400f, prefWidth.get(bar), 0.1f,
                    "the percentage remains a live Scene2D cell value");
        } finally {
            skin.dispose();
        }
    });
}
```

Use the test's existing recording-sink conventions rather than adding a second semantic test
framework. Run the test before any fixture/doc edits; it should pass only if component expansion
is truly transparent to builder/CSS/semantics.

Add `ProgressBar` and `Value` imports and define the referenced fixture in the test class:

```java
private static final String COMPONENT_HUD_XML = """
        <ui><components><component name="HealthBar">
          <param name="id" required="true"/>
          <param name="current" required="true"/>
          <table class="health-bar">
            <progressbar id="${id}-bar" min="0" max="100" value="${current}"/>
          </table>
        </component></components>
        <use component="HealthBar" id="player-health" current="72"/>
        </ui>
        """;
```

- [ ] **Step 2: Convert the sign-in sample to nested components and slots without changing actors**

Define three components inside `<components>`:

- `SigninDialog`: a `window.signin-dialog` root with title parameter and required default slot;
- `RuntimeTextField`: required `id`, `label`, and `entity` parameters, emitting the existing
  `textfield data-runtime-entity` actor;
- `PrimaryButton`: required `id`, `text`, and `name`, emitting `button.primary`.

Invoke `SigninDialog` around a default `<fill>`, then nested-use `RuntimeTextField` for `username`
and `PrimaryButton` for `save`. Preserve IDs `signin-panel`, `signin-window`, `signin-form`,
`signin-title`, `username-label`, `username`, `password-label`, `password`, `remember`, `save`,
the actor count 10, and visible text. Add `.signin-dialog` to the existing GDXCSS without changing
the screenshot palette/layout.

Use this complete executable fixture shape:

```xml
<ui>
  <components>
    <component name="SigninDialog">
      <param name="title" default="Sign in"/>
      <window class="signin-dialog" title="${title}">
        <table id="signin-form"><slot required="true"/></table>
      </window>
    </component>
    <component name="RuntimeTextField">
      <param name="id" required="true"/>
      <param name="label" required="true"/>
      <param name="entity" required="true"/>
      <textfield id="${id}" label="${label}" data-runtime-entity="${entity}"/>
    </component>
    <component name="PrimaryButton">
      <param name="id" required="true"/>
      <param name="text" required="true"/>
      <param name="name" required="true"/>
      <button id="${id}" class="primary" text="${text}" name="${name}"/>
    </component>
  </components>

  <table id="signin-panel" class="panel" width="500" height="300">
    <use component="SigninDialog" id="signin-window" title="Sign in"
         expand="true" fill="true">
      <fill>
        <row/>
        <label id="signin-title" class="title" font="inter" font-size="28" text="Sign in"/>
        <row/>
        <label id="username-label" text="Username"/>
        <use component="RuntimeTextField" id="username" label="Username" entity="user"/>
        <row/>
        <label id="password-label" text="Password"/>
        <textfield id="password" label="Password"/>
        <row/>
        <checkbox id="remember" text="Remember me" label="Remember me"/>
        <row/>
        <use component="PrimaryButton" id="save" text="Save" name="Save"
             width="180" align="left"/>
      </fill>
    </use>
  </table>
</ui>
```

Replace the broad `.title` color rule with the component-scoped equivalent so the rendered style
is unchanged while the sample proves concrete ancestry scoping:

```css
.signin-dialog .title { font-color: accent; }
```

- [ ] **Step 3: Strengthen harness assertions around component-generated actors**

Keep the existing role/name Save query, checkbox action/wait, username fill/runtime compare, and
screenshot. The existing runtime-compare assertions continue to prove entity `user`; strengthen
`PreviewProcess.awaitOkStatus` to require `"nodes":10` instead of accepting any `ok:true`, proving
definitions/uses/slots never enter the actor tree.

- [ ] **Step 4: Run builder, preview smoke, and harness E2E**

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupBuilderTest.componentGeneratedHudUsesConcreteCssLayoutAndSemantics' \
  --warning-mode=fail --rerun-tasks

xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin-components.png --exit'

xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test \
  --tests 'dev.gdx.markup.harness.MarkupHarnessEndToEndTest' \
  --warning-mode=fail --rerun-tasks
```

Expected: preview prints schema-v3 success with 10 nodes; PNG is 1280×720 RGBA; harness query/action/wait/runtime-compare/screenshot all pass.

- [ ] **Step 5: Write ADR 0005 and update every affected public recipe**

ADR sections must be Context, Decision, Component grammar/bounds, Provenance/diagnostics,
Consequences, and Rejected alternatives. README and cookbook must include:

- complete parameter/default/interpolation example;
- named/default/required/fallback slot example;
- root override/class merge behavior;
- CSS scoping via root class;
- runtime-expression transparency and numeric validation warning;
- all exact bounds;
- schema-v3 diagnostic example and recovery behavior;
- explicit non-goals and no-import security boundary;
- compilable parse/build/harness recipes referencing the executable sign-in fixture.

- [ ] **Step 6: Run documentation-sensitive tests and diff checks**

```bash
./gradlew :libgdx-ui-markup:test \
  :libgdx-ui-markup-preview:test \
  :libgdx-ui-markup-harness:test \
  :libgdx-ui-markup-idea:check \
  --warning-mode=fail
git diff --check
```

Expected: PASS and no stale preview `markup-status` schema-v2/current-dialect claims outside
historical release notes and already-completed historical plans. Qualification report schema 2 is
an unrelated protocol and remains unchanged.

- [ ] **Step 7: Commit integration evidence and documentation**

```bash
git add libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java \
  libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java \
  libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/PreviewProcess.java \
  samples/signin.xml samples/signin.gdxcss README.md \
  docs/guides/agentic-cookbook.md docs/adr/0005-parse-time-markup-components.md
git commit -m "docs: publish reusable markup components"
```

---

### Task 9: Completion audit, exact-head review, and integration handoff

**Files:**
- Inspect only unless a failing gate identifies an in-scope correction.

**Interfaces:**
- Consumes: all prior task commits and the approved design spec.
- Produces: recorded requirement-by-requirement evidence and a reviewed exact branch head; no release or publication.

- [ ] **Step 1: Run the approved narrow GL-free parser gate**

```bash
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS.

- [ ] **Step 2: Run affected Scene2D, preview, and harness suites under Xvfb**

```bash
xvfb-run -a ./gradlew \
  :libgdx-ui-markup:test \
  :libgdx-ui-markup-preview:test \
  :libgdx-ui-markup-harness:test \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS with no warnings, leaked child processes, or hidden GL skips on Linux/Xvfb.

- [ ] **Step 3: Run IDEA check and packaging**

```bash
./gradlew :libgdx-ui-markup-idea:check \
  :libgdx-ui-markup-idea:buildPlugin \
  --warning-mode=fail --rerun-tasks
```

Expected: PASS and plugin ZIP contains both IDEA JAR and bundled preview distribution.

- [ ] **Step 4: Run the complete repository gate**

```bash
xvfb-run -a ./gradlew build \
  --warning-mode=fail --console=plain
git diff --check
```

Expected: all tasks succeed; diff check is silent.

- [ ] **Step 5: Audit all thirteen acceptance criteria against authoritative evidence**

Inspect the final concrete parse tree, every bound test, schema-v3 JSON, IDEA presentation test,
component reload child assertions, sample PNG metadata, harness E2E output, README, cookbook, ADR,
and `git status`. Record each criterion as proven or continue fixing; absence of a failure is not
proof.

- [ ] **Step 6: Review the exact branch head before integration**

```bash
git status --short --branch
git log --oneline --decorate origin/main..HEAD
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git rev-parse HEAD
```

Use `requesting-code-review` on this exact SHA. Resolve every actionable finding, rerun the
affected narrow gate, and repeat exact-head review after any new commit.

- [ ] **Step 7: Choose integration through the finishing workflow**

Use `finishing-a-development-branch` only after all gates and exact-head review pass. Offer merge,
PR, keep, or discard choices as that skill requires. Do not push directly to `main`, merge, open a
PR, publish artifacts, tag, or release without the corresponding explicit authorization.
