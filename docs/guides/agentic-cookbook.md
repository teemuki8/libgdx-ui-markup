# Agentic cookbook

This cookbook gives coding agents the shortest source-backed path through common
`libgdx-ui-markup` tasks. Start with the decision table, load only the recipe you need, and use
the linked implementation or test when you need the complete contract. Commands run from the
repository root.

The examples describe the current tested stack: libgdx-ui-markup 0.6.0, libGDX 1.14.2,
libgdx-ui-harness 1.2.0, and libgdx-agent-runtime 2.0.0 on Java 25.

## Choose a recipe

| Goal | Recipe |
|---|---|
| See an XML/CSS change and get a bounded screenshot | [Preview a document](#1-preview-a-document) |
| Validate untrusted markup without a GL context | [Parse without GL](#2-parse-without-gl) |
| Reuse a bounded local UI structure | [Define reusable components](#2a-define-reusable-components) |
| Add the UI to an application-owned Stage | [Build on the render thread](#3-build-on-the-render-thread) |
| Make a Table UI follow its parent or viewport | [Use responsive GDXCSS layout](#3a-use-responsive-gdxcss-layout) |
| Style paint, text, images, input, or Actor transforms | [Use bounded Scene2D styling](#3b-use-bounded-scene2d-styling) |
| Use structural selectors or design tokens | [Use selectors and tokens](#3c-use-structural-selectors-and-design-tokens) |
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
- Treat components as GL-free, document-local syntax that must disappear before the public
  concrete model. Do not expose template constructs to actors, semantics, or runtime state.
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

A successful run emits schema-v3 `markup-status` with only `schemaVersion`, `ok`, and the final
concrete `nodes` count, then exits 0. A markup or CSS failure emits `kind`, `source`,
`elementPath`, `line`, `column`, `attribute`, `expected`, `received`, `suggestion`,
`consequence`, `componentTrace`, and `message`; `--exit` exits 2. Every string is bounded to
2,000 UTF-16 units and the trace to 16 frames/16,384 aggregate UTF-16 units. CLI usage errors
exit 1 before creating a GL context.

Reload is transactional. A failed component expansion or concrete build leaves the last-good
actor tree, Skin, runtime registration, and harness session live. A later valid edit commits one
fully prepared fresh candidate; no partially expanded tree becomes visible.

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

Component definitions are indexed and expanded between the bounded SAX read and ordinary
concrete validation. `document.root()` is therefore still an immutable concrete `Element` tree;
`document.provenance()` maps its final paths to source locations and bounded component invocation
traces. Unknown tags and attributes, malformed XML, invalid values, oversized input, unknown CSS
properties, and unparseable selectors fail as `MarkupException` with a stable `Kind` and
structured source context. Do not catch that exception and retry with relaxed parsing.

Narrow verification:

```bash
./gradlew :libgdx-ui-markup:test --tests '*MarkupParserTest' --tests '*CssTest'
```

Reference: [`MarkupParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java),
[`CssParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java), and
[`MarkupException`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupException.java).

## 2a. Define reusable components

Use document-local components for repeated declarative actor structure. The optional
`<components>` block must be the first child of `<ui>`. Each definition has exactly one actor
root (or nested `<use>`), and each invocation remains explicit:

```xml
<ui>
  <components>
    <component name="Card">
      <param name="id" required="true"/>
      <param name="title" default="Untitled"/>
      <table class="card" id="${id}">
        <label id="${id}-title" text="${title}"/>
        <row/>
        <slot required="true"/>
        <row/>
        <table class="footer"><slot name="footer">
          <label text="No actions"/>
        </slot></table>
      </table>
    </component>
  </components>

  <use component="Card" id="inventory" title="Inventory"
       class="wide" data-screen="inventory">
    <fill><label id="inventory-count" text="3 items"/></fill>
    <fill slot="footer"><button id="close" text="Close"/></fill>
  </use>
</ui>
```

Parameter names and named slots match `[a-z][a-z0-9-]{0,63}`; component names match
`[A-Z][A-Za-z0-9]{0,63}`. A parameter can be required, have a literal default, or resolve to an
empty string. `${name}` substitution is lexical and textual. It runs in the template and fallback
content, while caller fill content retains its caller scope. Required slots cannot have fallback;
an optional omitted slot expands its fallback children or nothing.

Attributes on `<use>` are declared parameters, common actor root overrides, or `data-*` root
overrides. A name that is both a parameter and common override supplies both. `class` merges
caller tokens after template tokens and de-duplicates them; every other override replaces the
template-root value and then undergoes normal concrete-tag validation. Scope styles through the
template root's stable class:

```css
.card { padding: 16px; }
.card .footer { margin-top: 8px; }
.card.wide { width: 100%; }
```

Components are transparent to layout, CSS, semantics, harness queries, and runtime correlation.
They can carry an opaque runtime expression such as `{player.health}` only through an attribute
whose grammar allows it. Substituting that text into today's numeric-only `progressbar value`
still fails `INVALID_VALUE`; component interpolation is not runtime evaluation.

The exact component limits are:

| Resource | Limit |
|---|---:|
| definitions per document | 256 |
| parameters per component | 64 |
| slots per component | 32 |
| substitutions per attribute/text value | 32 |
| nested invocation depth | 16 |
| final concrete elements | 10,000 |
| expansion visits | 100,000 |
| diagnostic invocation-trace frames | 16 |

These sit on top of the existing 1,048,576-byte UTF-8 input, 10,000 raw-element, depth-64,
4,096-character attribute-value, 4,096-character text, 256-character XML-name, and 256 custom-tag
limits. Component files cannot import URLs, filesystem paths, packages, or other documents.
Dynamic element names, discovery, loops, conditionals, arithmetic, scripts, reflection, arbitrary
Java calls, multiple-root fragments, implicit ID namespaces, and runtime/gameplay-state ownership
are intentional non-goals.

A schema-v3 failure is actionable without parsing prose:

```text
markup-status: {"schemaVersion":3,"ok":false,"kind":"UNKNOWN_COMPONENT","source":"/app/ui.xml","elementPath":"ui/use","line":18,"column":31,"attribute":"component","expected":"one of [Card]","received":"Crd","suggestion":"Card","consequence":"document rejected before Scene2D build","componentTrace":[],"message":"unknown component \"Crd\""}
```

Use the committed component-backed sign-in fixture as the executable parse/build/harness recipe:

```java
MarkupDocument document = new MarkupParser().parse(Path.of("samples/signin.xml"));
CssDocument css = new CssParser().parse(Path.of("samples/signin.gdxcss"));
// Pass both immutable values to the render thread, then use recipe 3.
```

```bash
./gradlew :libgdx-ui-markup:test --tests '*MarkupParserTest' --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupBuilderTest' --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test \
  --tests '*MarkupHarnessEndToEndTest.markupDeclaredUiIsDrivableThroughTheHarnessMcp' \
  --warning-mode=fail
```

Reference: the executable [`signin.xml`](../../samples/signin.xml) /
[`signin.gdxcss`](../../samples/signin.gdxcss),
[`ComponentCompiler`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ComponentCompiler.java),
and [`MarkupParserTest`](../../libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java).

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

The builder receives only the expanded concrete tree; component definitions and invocations do
not create actors or hidden layout nodes. The application owns `skin` and must dispose it on the
render thread. Size the root to the viewport: harness actionability checks parent intersection,
so a zero-sized root makes children non-actionable even if they draw.

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

## 3b. Use bounded Scene2D styling

Emit only properties with a direct Scene2D conversion. This example is accepted as written:

```css
.panel {
  background: panel;
  background-color: rgba(24, 32, 38, 0.92);
  opacity: 0.98;
}

.title {
  font-family: inter;
  color: #f4f7ff;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.portrait {
  object-fit: cover;
  object-position: center top;
  pointer-events: none;
  scale: 1.05;
  rotate: -2deg;
  transform-origin: center bottom;
}
```

| Property | Accepted values | Conversion or restriction |
|---|---|---|
| `color`, `font-color` | short/long hex, `rgb`, `rgba`, `transparent`, Skin name | supported widget font-color field |
| `background-color` | same color grammar | cloned/tinted selected background, else Skin `white` |
| `font-family` | registered identifier | alias for `font`; later alias declaration wins |
| `white-space` | `normal`, `nowrap` | Label wrap on/off |
| `text-overflow` | `clip`, `ellipsis` | Label ellipsis off/on |
| `object-fit` | `contain`, `cover`, `fill`, `none` | Image `fit`, `fill`, `stretch`, `none` scaling |
| `object-position` | one keyword or horizontal then vertical | Image alignment |
| `opacity` | finite number from 0 through 1 | Actor alpha; RGB is retained |
| `pointer-events` | `auto`, `none` | `Touchable.enabled` / `disabled`; XML `focusable` wins |
| `scale` | one/two positive finite numbers | Actor X/Y scale; one value applies to both axes |
| `rotate` | finite number with `deg` | Actor rotation |
| `transform-origin` | one keyword or horizontal then vertical | Actor origin after its size is known |

Hex supports `#rgb`, `#rgba`, `#rrggbb`, and `#rrggbbaa`. RGB function channels are integers
from 0 through 255; `rgba` alpha is 0 through 1. Names are resolved only from the caller-owned
Skin on the render thread. `background-color` does not allocate a Texture: it clones/tints the
selected drawable or the Skin's `white` drawable. A missing/non-tintable base is a located
`UNRESOLVED_STYLE`, and shared Skin styles/drawables remain unchanged.

Text properties on non-Labels and object properties on non-Images are located `STYLE_ERROR`s.
Paint/input/transform properties are base-state only; do not put them in pseudo-state rules.
Actor scale/rotation never changes a Table Cell constraint. General transforms, translation,
skew, matrices, gradients, borders/radius, shadows, filters, blend modes, URLs, and web fonts
are intentionally unsupported.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test \
  --tests '*MarkupBuilderTest.backgroundColorTintsPerActorCloneWithoutMutatingSharedStyle' \
  --tests '*MarkupBuilderTest.objectFitAndPositionMapToImageScalingAndAlignment' \
  --tests '*MarkupBuilderTest.actorPaintInputAndTransformsApplyAfterKnownSize'
```

Reference: [`CssColor`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssColor.java),
[`CssParser`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java), and
[`MarkupBuilder`](../../libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java).

## 3c. Use structural selectors and design tokens

Use one global `:root` block and complete-value substitutions. Structural matching follows the
actual immutable markup ancestry, not the Actor tree after construction:

```css
:root { --surface: #182026; --full: 100%; }
#screen { width: var(--full); height: var(--full); }
table.shell > group.content label.title { background-color: var(--surface); }
```

Supported compounds combine an optional tag or `*`, one ID, and multiple classes. Separate
compounds with whitespace (descendant) or `>` (direct child); at most eight parts are allowed.
Comma groups are supported. `:active` normalizes to `:pressed`; `:focus` maps only TextField
focused background/font color. Pseudos occur only on the rightmost compound. Attribute and
sibling selectors, pseudo-elements, `:not()`, `:has()`, and other selector functions fail typed.

Variables are global, capped at 256, and resolve through at most 16 names. Forward references
work. `var(--name)` must be the complete property value; fallback arguments, mixed tokens,
cycles, and missing names fail before the target property's ordinary validation. The executable
recipe is `gdxcss-cookbook.xml` plus `gdxcss-cookbook.gdxcss` in core test resources.

Narrow verification:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup:test \
  --tests '*CssTest.structuralResolutionUsesAncestryChildAndDescendantSemantics' \
  --tests '*CssTest.rootVariablesResolveForwardReferencesBeforePropertyValidation' \
  --tests '*MarkupBuilderTest.documentedGdxcssCookbookFixtureParsesAndBuilds'
```

The complete property/value/limit matrix is in the README's “GDXCSS language contract”. Keep
that matrix, this recipe, and the executable fixture synchronized with every dialect change.

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

The executable proof uses the component-backed `samples/signin.xml`, queries
`role=button, name=Save`, asserts exactly one `testId=save`, clicks,
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

First declare the correlation in markup. A component may interpolate or carry this `data-*`
attribute, but expansion does not read or own its value. `data-runtime-property` defaults to
`value`:

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

The committed sign-in fixture expands to exactly 10 concrete actors; definitions and template
syntax are absent from query results and node counts. The repository intentionally does not ship
a shell MCP client. Copy the protocol request shapes from `MarkupMcpClient`, and use the
end-to-end test as the executable orchestration example. The preview advertises `snapshot`,
`query`, `action`, `wait`, `screenshot`, and `ui_runtime_compare` capabilities.

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

Custom tags accept the common actor attributes and bounded `data-*` properties. One parser accepts
at most 256 custom tags, and each custom tag, XML element, and XML attribute name is capped at 256
characters. Adding new tag-specific XML attributes changes the dialect and belongs in the core
`TagSpec` vocabulary with parser and builder tests; a factory cannot bypass parse-time validation.

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
| `DUPLICATE_COMPONENT` / `UNKNOWN_COMPONENT` | Fix the document-local definition/name; use the schema-v3 suggestion only when present. |
| `MISSING_PARAMETER` / `UNKNOWN_PARAMETER` | Compare the `<use>` attributes with declared parameters and permitted root overrides. |
| `DUPLICATE_SLOT` / `UNKNOWN_SLOT` / `MISSING_SLOT` | Match each direct `<fill>` to one declared default or named slot and satisfy required slots. |
| `COMPONENT_CYCLE` | Follow `componentTrace` and remove the direct or indirect recursive invocation. |
| `DUPLICATE_ID` / `MISSING_ATTRIBUTE` / `INVALID_VALUE` | Fix the exact element path; do not add inference or a default that weakens the dialect. |
| `TOO_LARGE` | Reduce the bounded input/tree or explicitly review the trust-boundary limit; do not retry unbounded. |
| `STYLE_ERROR` | Check the selector and property whitelist; there is no CSS layout engine or selector combinator support. |
| `UNRESOLVED_STYLE` | Check the requested Skin style, drawable, color, or font at the reported element. |
| Preview exit 1 | Fix CLI usage; `--ui` and `--css` are required. |
| Preview exit 2 | Parse schema-v3 `markup-status`; use source, expected/received, suggestion, consequence, and component trace. The last-good scene remains live during reload. |
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
