# libgdx-ui-markup

Declarative Scene2D UI authoring for libGDX: an HTML-like XML dialect plus a bounded CSS
styling subset compiles into Scene2D actors with **semantics by construction**. The same source
that declares the UI declares its automation contract — `testId`, `role`, and `accessibleName`
come from the markup, so libgdx-ui-harness locators stop depending on inference.

## Modules

| Module | Responsibility |
|---|---|
| `libgdx-ui-markup` | Core: hardened XML parser → bounded component expansion → immutable concrete model, CSS-subset parser, tag registry, render-thread builder, programmatic default Skin, `SemanticSink` SPI |
| `libgdx-ui-markup-harness` | Adapter: `HarnessSemanticSink` maps markup semantics into the harness `Semantics` facade; end-to-end test drives a markup UI through the harness MCP |
| `libgdx-ui-markup-runtime` | Adapter: `MarkupRuntimeSource` registers `data-runtime-entity` actors with explicit value authority — authoritative (domain-supplied), bindings-only (app-owned entities), or widget-mirror (preview convenience, non-authoritative) — plus native UI bindings |
| `libgdx-ui-markup-preview` | Standalone LWJGL3 app: hot-reloads `--ui`/`--css`, typed error overlay, CI flags, optional `--mcp` harness server |
| `libgdx-ui-markup-idea` | Thin IntelliJ plugin: "Markup Preview" tool window that launches the preview and shows live build status |

Layout is Scene2D-native via XML attributes (`expand`, `fill`, `align`, `colspan`, `pad`,
`space`, `grow`) and bounded GDXCSS properties converted to Table/Cell constraints. There is no
browser layout engine and no full HTML dialect. New stylesheets use the `.gdxcss` extension so
tools and agents do not mistake the language for browser CSS; legacy `.css` paths remain
accepted.

## Quick start

`settings.gradle.kts` includes all four modules; `./gradlew` (Gradle 9.6.1) builds with JDK 25.

For task-oriented agent recipes—preview, parse/build, semantics, runtime values, harness MCP,
custom tags, and diagnosis—see [the agentic cookbook](docs/guides/agentic-cookbook.md).

### Ecosystem versions

**Current tested stack:** markup 0.5.0, harness 1.2.0, agent-runtime 2.0.0, and libGDX
1.14.2. This is the recommended combination for new applications.

**Minimum compatible stack:** harness 1.1.0 and agent-runtime 1.0.0. The release gate executes
the adapter tests against both exact profiles from separate strict lock files; dynamic dependency
versions are not used.

`ui` declares the tree; ids become harness test identifiers and actor names:

```xml
<ui>
  <table id="signin-panel" class="panel" width="500" height="300">
    <window id="signin-window" title="Sign in" expand="true" fill="true">
      <table id="signin-form">
        <row/>
        <label id="signin-title" class="title" font="inter" font-size="28" text="Sign in"/>
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

GDXCSS styles it (tag/class/id selectors with `:hover`, `:pressed`, `:checked`, `:disabled`):

```css
.panel { padding: 28px; }
.title { font-color: accent; }
button { padding: 12px; }
button.primary { background: accent; font-size: 20px; }
button.primary:hover { background: accent-over; }
button.primary:pressed { background: accent-down; }
textfield { background: field; padding: 8px; font-size: 18px; }
checkbox { font-color: text; font-size: 18px; }
checkbox:hover { font-color: accent; }
```

Run the live preview (hot reloads on file change, `esc` quits):

```
./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.gdxcss'
```

CI mode renders frames, writes a screenshot, exits 0 (or 2 with a typed error on stdout):

```
./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'
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

## Reusable markup components

Components are document-local, parse-time templates. Put one optional `<components>` block first
under `<ui>`, then invoke definitions explicitly with `<use>`. This complete example demonstrates
a required parameter, a literal default, interpolation, a required default slot, a named slot
with fallback, and caller overrides on the generated root:

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

`id="inventory"` is both the declared `id` parameter and a common root override. All common
actor attributes and `data-*` attributes supplied on `<use>` override the generated root.
`class` is the exception: caller tokens are appended and de-duplicated, so the final root keeps
the stable `card` class and also has `wide`. That stable class provides ordinary bounded GDXCSS
scoping; component names are not selectors:

```css
.card { padding: 16px; }
.card .footer { margin-top: 8px; }
.card.wide { width: 100%; }
```

Expansion is GL-free and produces only ordinary concrete elements. `MarkupDocument.root()`,
`MarkupBuilder`, `SemanticSink`, the harness, and runtime integration never see `<component>`,
`<use>`, `<slot>`, or `<fill>`. Parameter substitution is textual and then the normal target-tag
grammar is authoritative. In particular, components preserve an opaque future runtime expression
such as `{player.health}` only where its target attribute permits that text; substituting it into
today's numeric-only `progressbar value` fails with `INVALID_VALUE`. Components do not evaluate
runtime expressions.

Component compilation adds these exact limits to the parser's existing 1 MiB UTF-8 input,
10,000 raw-element, depth-64, 4,096-character attribute, and 4,096-character text limits:

| Resource | Limit |
|---|---:|
| component definitions per document | 256 |
| parameters per component | 64 |
| slots per component | 32 |
| substitutions per attribute or text value | 32 |
| nested component expansion depth | 16 |
| final concrete elements | 10,000 |
| total expansion visits | 100,000 |
| diagnostic invocation-trace frames | 16 |

Definitions cannot import files, URLs, packages, or other documents. There is deliberately no
component discovery, dynamic tag syntax, loop, conditional, arithmetic, script, reflection,
multiple-root fragment, implicit ID namespace, runtime-state ownership, or arbitrary Java call.
This no-import boundary keeps one watched XML file reproducible and prevents component expansion
from becoming an unrestricted filesystem or execution surface.

The executable component-backed reference is [`samples/signin.xml`](samples/signin.xml), styled
by [`samples/signin.gdxcss`](samples/signin.gdxcss). Parsing and building it uses the same public
API shown above; the preview and harness commands in this README exercise that exact fixture.

Preview diagnostics use `markup-status` schema 3. A component failure is one bounded JSON line,
for example (line and column depend on the source):

```text
markup-status: {"schemaVersion":3,"ok":false,"kind":"UNKNOWN_COMPONENT","source":"/app/ui.xml","elementPath":"ui/use","line":18,"column":31,"attribute":"component","expected":"Card","received":"Crd","suggestion":"Card","consequence":"document rejected before Scene2D build","componentTrace":[],"message":"unknown component \"Crd\""}
```

Each string is capped at 2,000 UTF-16 units; component traces are capped at 16 frames and 16,384
aggregate UTF-16 units. Success carries only schema, `ok`, and the concrete actor count, for
example `markup-status: {"schemaVersion":3,"ok":true,"nodes":10}`. Hot reload is
transactional: a rejected candidate leaves the last-good actor tree, Skin, runtime registration,
and harness session live; a later valid edit replaces them with one fully built fresh candidate.

## Responsive GDXCSS layout

Dimensions accept non-negative unitless pixels, `px`, percentages, and `auto`. A percentage on
an actor in a Table is a live Scene2D `Value` evaluated against the containing Table, so
`width: 100%` follows later Table and viewport resizes without rebuilding. A Table outside a
Cell may use the exact `width: 100%; height: 100%` pair to fill its parent. Other percentage
dimensions outside a Cell are located `STYLE_ERROR`s; they are never frozen to a build-time
pixel value.

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
#screen { width: 100%; height: 100%; }
#panel { width: 100%; max-width: 960px; padding: 16px 24px; gap: 12px; }
#save { width: 100%; min-width: 160px; }
```

`min-width`, `min-height`, `max-width`, and `max-height` use the same values in Cells; `auto`
restores Scene2D's native constraint. `padding` is internal for Table/Window actors and Cell
padding for other actors, while `margin` maps to containing-Cell space. Shorthands use CSS
top/right/bottom/left ordering with one to four whitespace-separated pixel values; legacy
one/four comma forms remain accepted. Table `gap`, `row-gap`, and `column-gap` become child-Cell
spacing defaults.

The same bounded conversion supports `display: none`, `visibility: hidden`,
`overflow: hidden` on Tables, and `vertical-align: top | middle | bottom`. The legacy
`visible: true | false` property remains available, but `visibility` wins when both CSS
properties are declared. Numeric XML dimensions continue to override the corresponding CSS
property.

Unsupported browser units and functions—including `em`, `rem`, `vw`, `vh`, `calc()`, `min()`,
`max()`, and `clamp()`—fail during the GL-free CSS parse with the property location.

## Common GDXCSS styling

Colors accept `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, integer-channel `rgb(...)`,
integer-channel `rgba(..., alpha)` with alpha from 0 through 1, `transparent`, or a color name
registered in the caller-owned Skin. `background-color` tints a cloned `background` drawable;
without one it clones the Skin's `white` drawable. The source drawable and shared widget style
are never mutated. A missing or non-tintable base is a located `UNRESOLVED_STYLE`.

```css
.card {
  background: panel;
  background-color: rgba(24, 32, 38, 0.9);
  opacity: 0.95;
}
.copy { font-family: inter; white-space: normal; text-overflow: ellipsis; }
.art { object-fit: cover; object-position: right bottom; }
```

`font-family` is the standard alias for `font`; if both occur, the later declaration wins.
`white-space: normal | nowrap` and `text-overflow: clip | ellipsis` apply only to Labels.
`object-fit: contain | cover | fill | none` and `object-position` apply only to Images;
positions use one keyword or horizontal-then-vertical keywords.

Every Actor supports `opacity: 0..1`, `pointer-events: auto | none`, one/two-number `scale`,
finite `rotate` values with a required `deg` suffix, and keyword `transform-origin`. These are
base-state properties; transforms alter Actor painting/input state and never Table/Cell layout
constraints. Explicit markup `focusable` remains authoritative over `pointer-events`.

There is deliberately no support for gradients, borders/radius, shadows, filters, blend modes,
general `transform` functions, translation, skew, matrices, URLs, or web fonts. Unknown and
incompatible properties fail typed instead of being approximated.

## GDXCSS language contract

| Area | Supported contract |
|---|---|
| Selectors | `*`; tag, `#id`, any number of `.classes`, tag+ID/classes; descendant and `>` child; comma groups |
| Pseudos | `:hover`, `:pressed`, `:active` (alias), `:checked`, `:disabled`; `:focus` only for TextField focused background/font color |
| Dimensions | `width`, `height`, `min-*`, `max-*`: non-negative unitless/`px`, `%`, or `auto` |
| Spacing/layout | `padding[-side]`, `margin[-side]`, `gap`, `row-gap`, `column-gap`, `display`, `visibility`, `visible`, `overflow`, `vertical-align` |
| Widget style | `background` plus `-over/-down/-checked/-disabled`, `background-color`, `color`, `font-color`, `font`/`font-family`, `font-size`, `text-align` |
| Text/Image | `white-space`, `text-overflow`, `object-fit`, `object-position` with the target restrictions above |
| Actor | `opacity`, `pointer-events`, `scale`, `rotate`, `transform-origin` |
| Tokens | one `:root` block, at most 256 `--names`; complete-value `var(--name)`, depth at most 16 |

Selectors are capped at 256 characters, 64 selectors per comma group, 4,096 total selectors,
and eight compound parts per selector. Stylesheets are capped at 262,144 UTF-8 bytes, 2,048
rules, and 128 declarations per rule. Cascade matching also has per-resolution and per-build
comparison limits. Attribute/sibling selectors, pseudo-elements, selector functions,
inheritance, fallback or mixed-token `var()`, `!important`, at-rules/media queries, flex/grid,
floats, positioning, browser-relative units/functions, and arbitrary resource loading are not
supported.

Legacy `.css` paths and the legacy one/four-value comma spacing form remain accepted. New files
use `.gdxcss`; new spacing uses CSS-order whitespace values. Nested Table `padding` is internal;
use `margin` for external Cell space.

## Exact-size fonts

The default skin bundles Inter and rasterizes each requested logical size with `gdx-freetype`.
XML accepts integer `font-size` values from 4 through 256; CSS accepts the same integer with an
optional `px` suffix. XML overrides CSS independently for `font` and `font-size`. A declaration
with a size resolves `font` as a registered FreeType family (default `inter`); without a size,
named `BitmapFont` resources in the Skin retain precedence for compatibility.

Applications can register a bounded custom family set on the render thread. The application
chooses the `FileHandle`s and glyph set—markup never supplies a path:

```java
Skin skin = new Skin(Gdx.files.internal("ui/skin.json"));
FreeTypeFontManager.install(
        skin,
        "game-ui",
        Map.of("game-ui", Gdx.files.internal("fonts/GameUi-Regular.ttf")),
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;!?-",
        2f); // physical backing-buffer density; logical layout sizes do not change

BuiltUi ui = MarkupBuilder.build(document, css, skin, sink);
// ... use the UI ...
skin.dispose(); // owns generated BitmapFonts; the attached manager owns its generators
```

The family set is capped at 16, the glyph string at 2,048 BMP characters, and the per-Skin
family/size cache at 64 fonts. `DefaultSkin.create(rasterScale)` performs the same setup for
bundled Inter. Accessibility scaling should rebuild the markup UI with changed logical sizes so
Scene2D layout reflows; it should not magnify the finished Stage.

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

An element with `data-runtime-entity` becomes a value source in
[libgdx-agent-runtime](https://github.com/teemuki8/libgdx-agent-runtime) (current tested artifact
`io.github.teemuki8:agent-runtime-core:2.0.0`) with an explicitly chosen value authority. The
property is named by `data-runtime-property` (default `value`), and a native `UiBinding` links
the entity to the actor's control id in the session, so `uiToRuntime`/`runtimeToUi` resolve the
correlation. Three registration modes on `MarkupRuntimeSource` share one transactional
preflight/commit pipeline:

- `registerAuthoritative(runtime, document, ui, session, resolver)` — the resolver supplies
  every property value from the domain model; a missing supplier fails preflight without
  mutation, and a UI/domain divergence is observable as `MISMATCH`.
- `registerBindings(runtime, document, ui, session)` — installs the UI correlations only, so
  markup can bind an entity the application already registered, with no widget supplier.
- `registerWidgetMirror(runtime, document, ui, session)` — reads the widget's live state back
  (typed by widget: `TextField` text, `CheckBox` checked, `Slider`/`ProgressBar` value,
  `SelectBox`/`List` selection, `Label` text). The preview selects this mode explicitly; it
  validates transport/correlation only and **cannot detect a UI displaying the wrong model
  value**. The 0.2.x `register(...)` delegates here for compatibility.

The preview prints a bounded registration line naming the mode, e.g.
`markup-runtime: {"mode":"widget-mirror","entities":N,"bindings":N}`, and records a
`UiFrameCorrelation` for every rendered frame.

```xml
<textfield id="username" label="Username" data-runtime-entity="user"/>
```

The three-library story is complete against published artifacts: markup ↔ agent-runtime and
markup ↔ harness (both on published jars), and agent-runtime ↔ harness through the
`ui_runtime_compare` tool shipped in harness (`harness-agent-runtime`, current tested version
1.2.0). The preview's
MCP session advertises the runtime-compare capability, `data-runtime-entity` actors carry the
harness runtime binding with the frame-correlation token `markup-preview-frame`, and the E2E
asserts the correlated `EQUAL` comparison (ADR 0002).

Production embedding into an application-owned game (your own `Stage`, session, and
correlation token — not the preview): see [docs/guides/embedding.md](docs/guides/embedding.md)
for the full render-thread wiring, the per-frame `UiFrameCorrelation` recording, and the
`ui_runtime_compare` loop-order contract.

## Qualification

`libgdx-ui-markup-qualification` marks up recreations of well-made game UIs and measures how
closely their rendered structure matches the real screenshots. The corpus manifest pins each
entry to either a committed, fully owned reference (the agentic-palisade "Skirmish
Configuration" screen from libgdx-ui-harness) or a published source (Hades boon panel, Slay
the Spire shop, Battle for Wesnoth gameplay) with a license note; remote images are fetched
at test time over pinned TLS into a bounded per-run in-memory cache, authenticated against
the committed SHA-256/byte/media-type identity, and never redistributed — no remote bytes are
ever written to disk. Each recreation is rendered by
the real preview binary and compared with a tolerance-aware structural Dice score (80×45
variance cells, one-cell dilation); per-entry thresholds are measured baselines that guard
regressions. Entries whose reference cannot be resolved are reported skipped locally; with
`-PstrictQualification=true` (CI) any skip is a failure:

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
adds a plugin-side polled reload). Break the XML → a red overlay and the tool window show the
schema-v3 source/line/column, typed message, expected/received values, suggestion, consequence,
and bounded component invocation trace when those fields apply. The last-good preview remains
interactive until a valid edit commits a fresh candidate.

## Verification ladder

From the repository root:

1. GL-free core: `./gradlew :libgdx-ui-markup:test`
2. Render-thread builder: `xvfb-run -a ./gradlew :libgdx-ui-markup:test`
3. Preview smoke: `xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'`
4. Agent-runtime source: `xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test`
5. Harness E2E: `xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test`
6. Plugin: `./gradlew :libgdx-ui-markup-idea:buildPlugin` plus the manual IDEA session above
7. Full: `xvfb-run -a ./gradlew build`

## License

Apache-2.0. See [LICENSE](LICENSE). Architecture decisions: `docs/adr/`.
Production embedding: `docs/guides/embedding.md`.
