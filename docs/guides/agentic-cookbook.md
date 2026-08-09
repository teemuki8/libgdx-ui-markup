# Agentic cookbook

This cookbook gives coding agents the shortest source-backed path through common
`libgdx-ui-markup` tasks. Start with the decision table, load only the recipe you need, and use
the linked implementation or test when you need the complete contract. Commands run from the
repository root.

The examples describe the current tested stack: libgdx-ui-markup 0.4.1, libGDX 1.14.2,
libgdx-ui-harness 1.2.0, and libgdx-agent-runtime 2.0.0 on Java 25.

## Choose a recipe

| Goal | Recipe |
|---|---|
| See an XML/CSS change and get a bounded screenshot | [Preview a document](#1-preview-a-document) |
| Validate untrusted markup without a GL context | [Parse without GL](#2-parse-without-gl) |
| Add the UI to an application-owned Stage | [Build on the render thread](#3-build-on-the-render-thread) |
| Make a Table UI follow its parent or viewport | [Use responsive GDXCSS layout](#3a-use-responsive-gdxcss-layout) |
| Give automation stable locators | [Declare semantics](#4-declare-semantics-for-strict-locators) |
| Compare displayed state with domain state | [Choose runtime value authority](#5-choose-runtime-value-authority) |
| Drive the preview through query/action/wait/screenshot | [Exercise the harness MCP path](#6-exercise-the-harness-mcp-path) |
| Add an application-specific actor tag | [Register a custom tag](#7-register-a-custom-tag) |
| Explain a typed failure without guessing | [Diagnose failures](#8-diagnose-failures) |

## Invariants agents must preserve

- Parse XML and CSS off the GL thread; the resulting model is immutable and GL-free.
- Create or mutate `Skin`, `Actor`, and `Stage` state only on the render thread.
- Call `MarkupBuilder.build(...)` only on the render thread.
- Treat unknown tags, attributes, CSS properties, and selectors as typed failures. Do not add a
  permissive fallback around the bounded dialect.
- Use markup-declared `id`/`name`/`label` semantics and strict locator resolution. A zero-match
  failure and a multiple-match failure are different defects and must remain distinct.
- Use authoritative runtime values when the assertion is meant to validate displayed domain
  state. Widget readback only validates the transport and correlation path.
- Use the Gradle Wrapper and JDK 25. Avoid preview or incubator Java APIs.

## 1. Preview a document

Use the preview before changing Java integration code. It exercises the real parser, CSS
compiler, Scene2D builder, and default Skin.

Start the hot-reloading window; edit either sample while it runs and press Escape to quit:

```bash
./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.gdxcss'
```

For a bounded agent or CI check, render five frames, write a PNG, and exit:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'
```

A successful run emits a schema-versioned `markup-status` JSON line and exits 0. A markup or CSS
failure emits a typed status with `kind`, `elementPath`, `line`, `column`, and `message`, and
`--exit` exits 2. CLI usage errors exit 1 before creating a GL context.

Reference: [`CliOptions`](../../libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/CliOptions.java),
[`MarkupStatus`](../../libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/MarkupStatus.java),
and the committed [`signin.xml`](../../samples/signin.xml) /
[`signin.gdxcss`](../../samples/signin.gdxcss). Use `.gdxcss` for new stylesheets; legacy `.css`
paths remain accepted by the parser, preview watcher, and IDEA sibling fallback.

## 2. Parse without GL

Use this recipe for validation, inspection, editor tooling, or preprocessing. Both parsers are
bounded, strict, and GL-free:

```java
MarkupDocument document = new MarkupParser().parse(xml);
CssDocument css = new CssParser().parse(stylesheet);
```

They also accept `Path` inputs and read at most their configured byte limit plus one sentinel:

```java
MarkupDocument document = new MarkupParser().parse(Path.of("ui/menu.xml"));
CssDocument css = new CssParser().parse(Path.of("ui/menu.gdxcss"));
```

Do not create a `Skin`, actor, Stage, backend, or libGDX collection in the parse phase. Pass the
immutable `MarkupDocument` and `CssDocument` to the render thread when it is time to build.

Unknown tags and attributes, malformed XML, invalid values, oversized input, unknown CSS
properties, and unparseable selectors fail as `MarkupException` with a stable `Kind` and source
location. Do not catch that exception and retry with relaxed parsing.

Narrow verification:

```bash
./gradlew :libgdx-ui-markup:test --tests '*MarkupParserTest' --tests '*CssTest'
```

Reference: [`MarkupParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java),
[`CssParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java), and
[`MarkupException`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupException.java).

## 3. Build on the render thread

Use this only from `ApplicationListener.create()`, `render()`, or another callback already owned
by the libGDX render thread. The parsed model may have been prepared elsewhere.

```java
// Render thread.
Skin skin = DefaultSkin.create();
BuiltUi built = MarkupBuilder.build(document, css, skin, new NoopSink());
built.root().setSize(
        stage.getViewport().getWorldWidth(),
        stage.getViewport().getWorldHeight());
stage.addActor(built.root());
```

The application owns `skin` and must dispose it on the render thread. Size the root to the
viewport: harness actionability checks parent intersection, so a zero-sized root makes children
non-actionable even if they draw.

For a reload, build a complete candidate actor tree and Skin before replacing the live tree.
Only after the Stage swap succeeds should the application remove the old root and dispose the old
Skin. Close an attached `MarkupRuntimeSource` before removing its actor tree, then register a new
source for the replacement. The preview's transactional rebuild is the canonical implementation.

Narrow render-thread verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupBuilderTest'
```

For production session, correlation, and loop-order wiring, continue with the
[embedding guide](embedding.md). Reference: [`MarkupBuilder`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java)
and [`PreviewApp.rebuild`](../../libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java).

## 3a. Use responsive GDXCSS layout

Use a Table/Cell hierarchy when dimensions must reflow. Percentages are live Scene2D `Value`s,
not pixels calculated once during the build:

```xml
<ui>
  <table id="screen">
    <table id="panel">
      <label id="title" text="Settings"/>
      <row/>
      <button id="save" text="Save"/>
    </table>
  </table>
</ui>
```

```css
#screen {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#panel {
  width: 100%;
  max-width: 960px;
  padding: 16px 24px;
  margin: 12px;
  row-gap: 12px;
}

#save {
  width: 100%;
  min-width: 160px;
  vertical-align: middle;
}
```

The exact `width: 100%; height: 100%` pair makes a Table with no containing Cell fill its parent.
Inside a Table, percentage width/height/min/max values follow the containing Table whenever it is
laid out at a new size. Size the application-owned root Group to the viewport as shown in recipe
3; no rebuild is required for an ordinary resize.

Use unitless pixels or `px`, percentages, or `auto` for `width`, `height`, `min-width`,
`min-height`, `max-width`, and `max-height`. `auto` removes the CSS constraint and uses the
actor's native Scene2D sizing. Numeric XML dimensions win over the corresponding CSS property.
Percent dimensions require a containing Table Cell, except for the full-parent Table pair above;
an unsupported context is a `STYLE_ERROR` located at the winning CSS rule.

Spacing is pixel-only. `padding` and `margin` accept one to four whitespace-separated values in
CSS order: `top right bottom left`; one value applies to every side, two to vertical/horizontal,
and three to top/horizontal/bottom. Legacy one/four comma forms remain accepted. Table padding is
internal; non-Table padding and every margin map to the containing Cell. `gap`, `row-gap`, and
`column-gap` set defaults on child Cells.

Use `display: none` to omit an actor, its subtree, its Cell, and its harness semantics. Use
`visibility: hidden` to keep layout and semantics while suppressing drawing. `overflow: hidden`
clips only Table/Window actors; another target fails with a located `STYLE_ERROR`.
`vertical-align: top | middle | bottom` aligns a Label and/or its containing Cell.
Dimensions, display/visibility, gaps, overflow, and vertical alignment are base-state only;
declaring them in `:hover`, `:pressed`, `:checked`, or `:disabled` rules is a typed error rather
than a silently ignored layout mutation.

Do not emit browser-relative units or functions. `em`, `rem`, viewport/physical units,
`calc()`, `min()`, `max()`, and `clamp()` are intentionally unsupported and fail in the GL-free
CSS parse. For deterministic diagnosis, report the exception `kind`, `elementPath`, `line`,
`column`, and `message`; do not retry with a guessed pixel fallback.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test \
  --tests '*MarkupBuilderTest.percentCellWidthTracksContainingTableResizeWithoutRebuild' \
  --tests '*MarkupBuilderTest.displayNoneOmitsActorAndSemanticsWhileVisibilityRetainsLayout'
```

Reference: [`CssLength`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssLength.java),
[`CssSpacing`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssSpacing.java), and
[`MarkupBuilder`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java).

## 4. Declare semantics for strict locators

Declare stable semantics in markup instead of inferring them later:

```xml
<button id="save" name="Save" label="Save changes" text="Save"/>
```

The build emits the following mapping:

| Markup source | Harness semantic |
|---|---|
| `id="save"` | actor name and exact `testId=save` |
| `name="Save"` | accessible name |
| `label="Save changes"` | label |
| `<button>` | canonical `role=button` |
| `data-owner="settings"` | semantic property `owner=settings` |

`role` is derived from the bounded tag vocabulary; it is not an accepted XML attribute. Choose
the semantic widget tag that matches the control. If an application-specific actor needs a role,
emit it through that tag's factory/semantic integration rather than adding an unrecognized
attribute.

Prefer the narrowest stable locator, usually the declared test id. Use the harness
`StrictResolution` policy for every action and comparison. Zero matches means the declared
contract is absent; multiple matches means it is ambiguous. Do not convert either result into
“pick the first actor.”

The executable proof queries `role=button, name=Save`, asserts exactly one `testId=save`, clicks,
waits, fills a text field, compares runtime state, and captures a screenshot through the real MCP
protocol.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test \
  --tests '*MarkupHarnessEndToEndTest.markupDeclaredUiIsDrivableThroughTheHarnessMcp'
```

Reference: [`MarkupBuilder.applySemantics`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java),
[`HarnessSemanticSink`](../../libgdx-ui-markup-harness/src/main/java/dev/gdx/markup/harness/HarnessSemanticSink.java),
and [`MarkupHarnessEndToEndTest`](../../libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java).

## 5. Choose runtime value authority

First declare the correlation in markup. `data-runtime-property` defaults to `value`:

```xml
<textfield id="username" name="Username" data-runtime-entity="user"/>
```

Create the source on the render thread. Prefer authoritative mode for production correctness
checks because its value comes from the domain model independently of the widget:

```java
MarkupRuntimeSource source = MarkupRuntimeSource.registerAuthoritative(
        runtime,
        document,
        built,
        APP_SESSION_ID,
        (entityId, propertyId, actor) ->
                () -> RuntimeValues.string(domainValue(entityId)));
```

The resolver runs during registration on the render thread. Its returned supplier runs on the
agent-runtime capture thread and must be thread-safe or capture stable state.

Use bindings-only mode when the application has already registered the entity and markup should
only correlate it with the actor:

```java
MarkupRuntimeSource source = MarkupRuntimeSource.registerBindings(
        runtime, document, built, APP_SESSION_ID);
```

Use widget-mirror mode only for preview or transport/correlation checks:

```java
MarkupRuntimeSource source = MarkupRuntimeSource.registerWidgetMirror(
        runtime, document, built, APP_SESSION_ID);
```

Widget mirror reads the displayed widget back as the runtime value. An `EQUAL` result therefore
cannot prove that the widget shows the correct domain value. The legacy `register(...)` method
delegates to widget mirror for compatibility; new code should choose a mode explicitly.

Own the returned source and close it before rebuilding or removing the actor tree:

```java
source.close();
```

Frame correlation is a second, mandatory contract. The correlation token passed to
`HarnessSemanticSink` must exactly equal the `UiFrameCorrelation` token recorded for every
rendered frame. Follow the [embedding guide](embedding.md) for that wiring and for the required
render-loop order.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test
```

Reference: [`MarkupRuntimeSource`](../../libgdx-ui-markup-runtime/src/main/java/dev/gdx/markup/runtime/MarkupRuntimeSource.java)
and [`RuntimeValueResolver`](../../libgdx-ui-markup-runtime/src/main/java/dev/gdx/markup/runtime/RuntimeValueResolver.java).

## 6. Exercise the harness MCP path

Start the preview in MCP mode when an agent needs protocol-level query, action, wait, screenshot,
or runtime comparison. The server uses stdio, so launch it through an MCP client rather than an
interactive terminal. First build the self-contained distribution:

```bash
./gradlew :libgdx-ui-markup-preview:installDist
```

Configure the MCP client to launch this executable and argument vector from the repository root:

```text
command: libgdx-ui-markup-preview/build/install/libgdx-ui-markup-preview/bin/libgdx-ui-markup-preview
args: --ui samples/signin.xml --css samples/signin.gdxcss --mcp
```

Do not put `./gradlew ... run` between the client and server: Gradle writes its own progress to
stdout, which is the MCP transport. The executable carries the required JVM/native-access options
without adding build-tool output to the protocol stream.

Use this bounded sequence:

1. Query a declared locator and require exactly one match.
2. Perform the action through the harness input path.
3. Wait on observable semantic state with a monotonic deadline; never sleep.
4. Call `ui_runtime_compare` when the element declares `data-runtime-entity`.
5. Capture a bounded in-memory screenshot.

The repository intentionally does not ship a shell MCP client. Copy the protocol request shapes
from `MarkupMcpClient`, and use the end-to-end test as the executable orchestration example. The
preview advertises `snapshot`, `query`, `action`, `wait`, `screenshot`, and
`ui_runtime_compare` capabilities.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test
```

Reference: [`MarkupMcpClient`](../../libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupMcpClient.java),
[`MarkupHarnessEndToEndTest`](../../libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java),
and [`PreviewMcp`](../../libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewMcp.java).

## 7. Register a custom tag

A custom tag needs both parse-time permission and a build-time factory. Missing either half fails
with a typed `UNKNOWN_TAG` diagnostic.

```java
Set<String> customTags = Set.of("inventory-slot");
MarkupDocument document = new MarkupParser(customTags).parse(xml);

MarkupRegistry registry = MarkupRegistry.defaultRegistry();
registry.register(
        "inventory-slot",
        (element, context) -> new Table(context.skin()));

BuiltUi built = MarkupBuilder.build(document, css, skin, sink, registry);
```

The factory runs during render-thread build. It may create/configure its actor from the immutable
`Element`, `BuildContext`, resolved style, and application-owned Skin. It must not touch the
Stage, run input, or start a second lifecycle. The builder still applies common attributes, CSS,
cell constraints, `id`/`name`/`label`, and `data-*` semantics.

Custom tags accept the common actor attributes and bounded `data-*` properties. Adding new
tag-specific XML attributes changes the dialect and belongs in the core `TagSpec` vocabulary with
parser and builder tests; a factory cannot bypass parse-time validation.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test \
  --tests '*MarkupBuilderTest.customFactoryExtendsTheVocabulary'
```

If Gradle reports that a narrowed wildcard matched no tests after test names change, run the
affected module suite instead:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test
```

Reference: [`MarkupRegistry`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupRegistry.java),
[`TagFactory`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagFactory.java), and
[`BuildContext`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/BuildContext.java).

## 8. Diagnose failures

Start from the typed result. Preserve its path and source location in the report; do not reduce it
to a bare exception message.

| Result | Check next |
|---|---|
| `MALFORMED_XML` | Inspect the reported line/column; confirm the input is strict UTF-8 and contains no DOCTYPE or external entity. |
| `UNKNOWN_TAG` / `UNKNOWN_ATTRIBUTE` | Compare the source with `TagSpec`; for a custom tag, verify both the parser allow-list and registry. |
| `DUPLICATE_ID` / `MISSING_ATTRIBUTE` / `INVALID_VALUE` | Fix the exact element path; do not add inference or a default that weakens the dialect. |
| `TOO_LARGE` | Reduce the bounded input/tree or explicitly review the trust-boundary limit; do not retry unbounded. |
| `STYLE_ERROR` | Check the selector and property whitelist; there is no CSS layout engine or selector combinator support. |
| `UNRESOLVED_STYLE` | Check the requested Skin style, drawable, color, or font at the reported element. |
| Preview exit 1 | Fix CLI usage; `--ui` and `--css` are required. |
| Preview exit 2 | Parse the emitted `markup-status` record and fix its typed diagnostic. |
| Runtime `MISSING` | Verify `data-runtime-entity`, the `HarnessSemanticSink`, and the UI binding. |
| Runtime `UNAVAILABLE` | Verify the runtime is started and the sink correlation token exactly matches the latest recorded `UiFrameCorrelation`. |
| Runtime `STALE` | Drain scheduled comparisons before advancing the deterministic clock; compare against the last correlated frame. |
| Runtime `MISMATCH` | In authoritative mode, inspect the displayed widget and domain supplier on the proven frame. |
| Runtime `AMBIGUOUS` | Make the markup id unique and preserve strict resolution; never choose the first match. |

Through `AgentRuntimeObservationSource`, correlation failures produce `UNAVAILABLE`; the source
does not emit unproven observations, so it does not produce `UNCORRELATED`. See the
[embedding status table](embedding.md#statuses-and-what-they-mean) before changing correlation or
loop-order code.

## Verification ladder

Run the narrowest recipe command first. When a change crosses boundaries, continue through the
affected suites in order:

```bash
./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test
./gradlew :libgdx-ui-markup-idea:buildPlugin
```

Use `xvfb-run -a ./gradlew build` only when the change warrants the full project gate. Record the
exact commands and results; compilation alone is not proof of a changed render or integration
path.

## Canonical source map

- Dialect and bounds: [`TagSpec`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java),
  [`MarkupParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java),
  [`CssParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java).
- Render-thread construction: [`MarkupBuilder`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java),
  [`PreviewApp`](../../libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java).
- Semantics and runtime: [`HarnessSemanticSink`](../../libgdx-ui-markup-harness/src/main/java/dev/gdx/markup/harness/HarnessSemanticSink.java),
  [`MarkupRuntimeSource`](../../libgdx-ui-markup-runtime/src/main/java/dev/gdx/markup/runtime/MarkupRuntimeSource.java),
  [embedding guide](embedding.md).
- Executable agent workflow: [`MarkupHarnessEndToEndTest`](../../libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java)
  and [`MarkupMcpClient`](../../libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupMcpClient.java).

When any public API, markup or CSS dialect rule, preview CLI option, semantic mapping, or
integration contract changes, update every affected recipe in this cookbook in the same change.
