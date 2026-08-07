# libgdx-ui-markup

Declarative Scene2D UI authoring for libGDX: an HTML-like XML dialect plus a bounded CSS
styling subset compiles into Scene2D actors with **semantics by construction**. The same source
that declares the UI declares its automation contract — `testId`, `role`, and `accessibleName`
come from the markup, so libgdx-ui-harness locators stop depending on inference.

## Modules

| Module | Responsibility |
|---|---|
| `libgdx-ui-markup` | Core: hardened XML parser → immutable model, CSS-subset parser, tag registry, render-thread builder, programmatic default Skin, `SemanticSink` SPI |
| `libgdx-ui-markup-harness` | Adapter: `HarnessSemanticSink` maps markup semantics into the harness `Semantics` facade; end-to-end test drives a markup UI through the harness MCP |
| `libgdx-ui-markup-runtime` | Adapter: `MarkupRuntimeSource` registers `data-runtime-entity` actors as libgdx-agent-runtime value sources with live widget state and native UI bindings |
| `libgdx-ui-markup-preview` | Standalone LWJGL3 app: hot-reloads `--ui`/`--css`, typed error overlay, CI flags, optional `--mcp` harness server |
| `libgdx-ui-markup-idea` | Thin IntelliJ plugin: "Markup Preview" tool window that launches the preview and shows live build status |

Layout is Scene2D-native via XML attributes (`expand`, `fill`, `align`, `colspan`, `pad`,
`space`, `grow`); CSS is a bounded styling subset compiled into a libGDX Skin. No CSS layout
engine, no full HTML.

## Quick start

`settings.gradle.kts` includes all four modules; `./gradlew` (Gradle 9.6.1) builds with JDK 25.

`ui` declares the tree; ids become harness test identifiers and actor names:

```xml
<ui>
  <table id="signin-panel" class="panel" width="500" height="300">
    <window id="signin-window" title="Sign in" expand="true" fill="true">
      <table id="signin-form">
        <row/>
        <label id="signin-title" class="title" text="Sign in"/>
        <row/>
        <label id="username-label" text="Username"/>
        <textfield id="username" label="Username" data-runtime-entity="user"/>
        <row/>
        <label id="password-label" text="Password"/>
        <textfield id="password" label="Password"/>
        <row/>
        <checkbox id="remember" text="Remember me" label="Remember me"/>
        <row/>
        <button id="save" class="primary" text="Save" name="Save" width="180" align="left"/>
      </table>
    </window>
  </table>
</ui>
```

`css` styles it (tag/class/id selectors with `:hover`, `:pressed`, `:checked`, `:disabled`):

```css
.panel { padding: 28px; }
.title { font-color: accent; }
button { padding: 12px; }
button.primary { background: accent; }
button.primary:hover { background: accent-over; }
button.primary:pressed { background: accent-down; }
textfield { background: field; padding: 8px; }
checkbox { font-color: text; }
checkbox:hover { font-color: accent; }
```

Run the live preview (hot reloads on file change, `esc` quits):

```
./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.css'
```

CI mode renders frames, writes a screenshot, exits 0 (or 2 with a typed error on stdout):

```
./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'
```

Programmatic build with a custom sink:

```java
Skin skin = DefaultSkin.create();
BuiltUi ui = MarkupBuilder.build(
        new MarkupParser().parse(xml),
        new CssParser().parse(css),
        skin,
        new NoopSink());            // HarnessSemanticSink for harness metadata
stage.addActor(ui.root());
```

Because `id` became a harness test identifier, the UI is drivable through the harness locator
API with no imperative metadata:

```java
Locator.testId("save")             // role=button, accessibleName=Save, testId=save
```

The full proof lives in `libgdx-ui-markup-harness`: an end-to-end test launches the preview with
`--mcp`, queries `role=button name=Save` (exactly one node, `testId=save`), clicks the checkbox
through the real input path, waits for the `checked` state, fills the `username` field and
compares it through `ui_runtime_compare` (status `EQUAL`, entity `user`/`value`), and captures
a PNG — all against `samples/signin.xml`, no imperative wiring.

## Agent runtime values

An element with `data-runtime-entity` becomes a live value source in
[libgdx-agent-runtime](https://github.com/teemuki8/libgdx-agent-runtime) (published
`io.github.teemuki8:agent-runtime-core:1.0.0`): the widget's current state is published under
the property named by `data-runtime-property` (default `value`), typed by widget
(`TextField` text, `CheckBox` checked, `Slider`/`ProgressBar` value, `SelectBox`/`List`
selection, `Label` text), and a native `UiBinding` links the entity to the actor's control id
in the preview session, so `uiToRuntime`/`runtimeToUi` resolve the correlation. The preview
prints a bounded registration line (`markup-runtime: {"entities":N,"bindings":N}`) and records
a `UiFrameCorrelation` for every rendered frame.

```xml
<textfield id="username" label="Username" data-runtime-entity="user"/>
```

The three-library story is complete against published artifacts: markup ↔ agent-runtime and
markup ↔ harness (both on published jars), and agent-runtime ↔ harness through the
`ui_runtime_compare` tool shipped in harness 1.1.0 (`harness-agent-runtime`). The preview's
MCP session advertises the runtime-compare capability, `data-runtime-entity` actors carry the
harness runtime binding with the frame-correlation token `markup-preview-frame`, and the E2E
asserts the correlated `EQUAL` comparison (ADR 0002).

## Qualification

`libgdx-ui-markup-qualification` marks up recreations of well-made game UIs and measures how
closely their rendered structure matches the real screenshots. The corpus manifest pins each
entry to its published source (Hades boon panel, Slay the Spire shop, Battle for Wesnoth
gameplay) with a license note; images are fetched at test time into a gitignored cache and
never redistributed. Each recreation is rendered by the real preview binary and compared with
a tolerance-aware structural Dice score (80×45 variance cells, one-cell dilation); per-entry
thresholds are measured baselines that guard regressions. Entries whose reference cannot be
fetched are reported skipped; at least one must be scored:

```
xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test
```

The bounded report lands at
`libgdx-ui-markup-qualification/build/qualification/output/report.json`; the corpus and
recreations live in `libgdx-ui-markup-qualification/corpus/` (ADR 0003). Thresholds are
calibrated, not hand-tuned: after changing a recreation or the corpus, run
`./gradlew :libgdx-ui-markup-qualification:calibrateQualification` (rewrites thresholds at
65% of measured) and commit the refreshed manifest. The test fails when a committed threshold
drifted more than 10% from the current measurement, so CI itself flags when re-calibration is
due.

## IDEA plugin

Build the plugin zip, then install it from the IDE (Settings → Plugins → gear → Install Plugin
from Disk):

```
./gradlew :libgdx-ui-markup-idea:buildPlugin
# zip: libgdx-ui-markup-idea/build/distributions/libgdx-ui-markup-idea-<version>.zip
```

Manual session: open `samples/signin.xml`, open the "Markup Preview" tool window (right
anchor), press **Launch** — a native window shows the sign-in form. Edit button text in the
XML → the preview updates within about a second (the preview hot-reloads; the **Watch** toggle
adds a plugin-side polled reload). Break the XML → a red overlay shows `elementPath:line:
message` and the tool window status turns red with the same text.

## Verification ladder

From the repository root:

1. GL-free core: `./gradlew :libgdx-ui-markup:test`
2. Render-thread builder: `xvfb-run -a ./gradlew :libgdx-ui-markup:test`
3. Preview smoke: `xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'`
4. Agent-runtime source: `xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test`
5. Harness E2E: `xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test`
6. Plugin: `./gradlew :libgdx-ui-markup-idea:buildPlugin` plus the manual IDEA session above
7. Full: `xvfb-run -a ./gradlew build`

## License

Apache-2.0. See [LICENSE](LICENSE). Architecture decisions: `docs/adr/`.
