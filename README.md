# gdx-ui-markup

Declarative Scene2D UI authoring for libGDX: an HTML-like XML dialect plus a bounded CSS
styling subset compiles into Scene2D actors with **semantics by construction**. The same source
that declares the UI declares its automation contract — `testId`, `role`, and `accessibleName`
come from the markup, so libgdx-ui-harness locators stop depending on inference.

## Modules

| Module | Responsibility |
|---|---|
| `gdx-ui-markup` | Core: hardened XML parser → immutable model, CSS-subset parser, tag registry, render-thread builder, programmatic default Skin, `SemanticSink` SPI |
| `gdx-ui-markup-harness` | Adapter: `HarnessSemanticSink` maps markup semantics into the harness `Semantics` facade; end-to-end test drives a markup UI through the harness MCP |
| `gdx-ui-markup-preview` | Standalone LWJGL3 app: hot-reloads `--ui`/`--css`, typed error overlay, CI flags, optional `--mcp` harness server |
| `gdx-ui-markup-idea` | Thin IntelliJ plugin: "Markup Preview" tool window that launches the preview and shows live build status |

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
        <textfield id="username" label="Username"/>
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
./gradlew :gdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.css'
```

CI mode renders frames, writes a screenshot, exits 0 (or 2 with a typed error on stdout):

```
./gradlew :gdx-ui-markup-preview:run \
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

The full proof lives in `gdx-ui-markup-harness`: an end-to-end test launches the preview with
`--mcp`, queries `role=button name=Save` (exactly one node, `testId=save`), clicks the checkbox
through the real input path, waits for the `checked` state, and captures a PNG — all against
`samples/signin.xml`, no imperative wiring.

## IDEA plugin

Build the plugin zip, then install it from the IDE (Settings → Plugins → gear → Install Plugin
from Disk):

```
./gradlew :gdx-ui-markup-idea:buildPlugin
# zip: gdx-ui-markup-idea/build/distributions/gdx-ui-markup-idea-0.1.0.zip
```

Manual session: open `samples/signin.xml`, open the "Markup Preview" tool window (right
anchor), press **Launch** — a native window shows the sign-in form. Edit button text in the
XML → the preview updates within about a second (the preview hot-reloads; the **Watch** toggle
adds a plugin-side polled reload). Break the XML → a red overlay shows `elementPath:line:
message` and the tool window status turns red with the same text.

## Verification ladder

From the repository root:

1. GL-free core: `./gradlew :gdx-ui-markup:test`
2. Render-thread builder: `xvfb-run -a ./gradlew :gdx-ui-markup:test`
3. Preview smoke: `xvfb-run -a ./gradlew :gdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'`
4. Harness E2E: `xvfb-run -a ./gradlew :gdx-ui-markup-harness:test`
5. Plugin: `./gradlew :gdx-ui-markup-idea:buildPlugin` plus the manual IDEA session above
6. Full: `xvfb-run -a ./gradlew build`

## License

Apache-2.0. See [LICENSE](LICENSE). Architecture decisions: `docs/adr/`.
