# gdx-ui-markup — standalone Scene2D UI builder (HTML-like XML + CSS) with IDEA live-preview plugin

## Context

The ask: would libgdx-ui-harness benefit from a UI builder that compiles HTML-like XML (or HTML) + CSS into libGDX Scene2D UIs, for humans and AI agents? **Answer: yes, as a separate library** (user's explicit choice) — not a harness module. The benefit lands on the harness's core contract: semantics-by-construction. A markup-declared UI gets `testId`/`role`/`accessibleName` from the source (`<button id="save">Save</button>` → `testId=save`, `role=button`, `accessibleName=Save`), so harness locators stop depending on inference. Agents get deterministic, diffable, familiar markup; humans get a declarative authoring format and a live-editing GUI. The libgdx-ui-harness repo is **not modified** in v1; integration flows through a published-harness dependency in a small adapter module.

**Agentic-workflow choices — which plan choices are best for AI agents, and why they are already the ones chosen:** (1) *semantics-by-construction* (Step 4 `SemanticSink`, Step 6 adapter) is the load-bearing choice: the harness locator contract (`role`/`accessibleName`/`testId`) becomes exact and derived from markup, so agent-written UIs are drivable through the existing 23 MCP tools with no extra metadata code and no heuristic inference; (2) *HTML-like XML + CSS styling* beats real HTML+CSS layout for agents: the syntax is equally familiar, but CSS box layout maps lossily to `Table`, so agent-generated layouts would render differently than intended and break snapshot/actionability determinism; (3) the *MCP-exposed preview* (`--mcp`, Step 5) is the agent's feedback loop — the same tool surface that tests games also drives the design-time preview (generate/edit markup → `ui_query`/`ui_action`/`ui_assert` → typed status line → iterate); (4) *reusing the existing harness locators* instead of inventing a markup-selector language keeps one agent mental model and matches the harness's Playwright-style role-locator guidance (`id`→`testId` already gives the `#save`-style mapping agents expect).

Prior art lessons (source-verified): gdx-lml (archived; crashinvaders fork maintenance-mode) proved the tag→provider-registry + per-tag-attribute-defaults design and the rule that **layout must ride libGDX's own Table/Cell engine**; every attempt at a CSS layout engine for Scene2D died. Skin JSON expresses styles only, never structure. This plan reuses the provider-registry lesson and Table/Cell layout, adds what prior art never had: a semantic sink so the harness sees exact semantics by construction.

Dialect (user's choice): **HTML-like XML + CSS for styling only**. Layout is Scene2D-native via XML attributes (`expand`, `fill`, `align`, `colspan`, `pad`, `space`); CSS is a bounded styling subset compiled into a libGDX Skin. No CSS layout engine, no full HTML.

## Deliverables (new repo, 4 modules)

| Module | Responsibility |
|---|---|
| `gdx-ui-markup` | Core builder: XML parser → immutable model; CSS-subset parser; tag registry; model→actor builder on the render thread; programmatic default Skin; `SemanticSink` SPI |
| `gdx-ui-markup-harness` | Optional adapter: `SemanticSink` → `harness-scene2d` `Semantics` (published `io.github.teemuki8:harness-scene2d:1.0.0`) + end-to-end test driving a markup-built UI through the harness MCP |
| `gdx-ui-markup-preview` | Standalone LWJGL3 app: loads `--ui`/`--css`, hot-reloads on file change, typed error overlay, CI flags (`--frames --screenshot --exit`), optional `--mcp` |
| `gdx-ui-markup-idea` | Thin IntelliJ plugin: "Markup Preview" tool window, launches the preview process, shows live build status. **No GL inside IDEA** — GLFW cannot embed cross-platform; a separate native window is the robust pattern |

Group `io.github.teemuki8`, license Apache-2.0 (copy text from `/home/tjaaskel/git/libgdx-ui-harness/LICENSE`), Java 25 baseline (required by the harness-scene2d dependency), libGDX 1.14.2, Gradle wrapper 9.6.1 (copy wrapper from the harness repo), packages `dev.gdx.markup.*`.

## Approach — ordered steps

### Step 1 — Scaffold the repo

Create `/home/tjaaskel/git/gdx-ui-markup` (sibling of libgdx-ui-harness; if a directory with that name already exists, build inside it).

- `git init`; `.gitignore` (build/, .gradle/, .idea/, out/, .kotlin/); `LICENSE` (Apache-2.0, copied from the harness repo); `README.md` (module table + 15-line quick start); `AGENTS.md` (adapt the harness's `/home/tjaaskel/git/libgdx-ui-harness/AGENTS.md` rules: Gradle wrapper only, Java 25, warnings-as-errors, red-green-refactor, render-thread confinement, bounded limits at trust boundaries, ADR requirement, verification ladder).
- `settings.gradle.kts`: `rootProject.name = "gdx-ui-markup"`; include `:gdx-ui-markup`, `:gdx-ui-markup-harness`, `:gdx-ui-markup-preview`, `:gdx-ui-markup-idea`.
- Root `build.gradle.kts`: Java 25 toolchain for the three JVM modules, `-Werror` for project code, JUnit 5 (5.10.x) everywhere, dependency versions copied from `/home/tjaaskel/git/libgdx-ui-harness/gradle/libs.versions.toml` (libGDX 1.14.2).
- `samples/signin.xml` + `samples/signin.css`: a sign-in form (window, two labels, two textfields, one checkbox, one save button) mirroring the harness `ReferenceScreen` sign-in panel — the canonical sample and E2E fixture.
- Module source dirs created empty with the Java 25 toolchain wired.
- Verify: `./gradlew help` succeeds; `./gradlew :gdx-ui-markup:compileJava` succeeds (empty module compiles).

### Step 2 — `gdx-ui-markup` core: markup model + strict validator (GL-free)

Package `dev.gdx.markup.core`.

- `MarkupDocument` — immutable element tree: `Element { String tag; String id; String name; String label; String text; Map<String,String> attrs; List<String> classes; List<Element> children; int line; int column; }`.
- Parser: JDK SAX (`XMLReader` + `Locator` for line/column). Harden: reject `DOCTYPE`, external entities/DTD (set `http://xml.org/sax/features/external-general-entities=false` and namespace/validation off), input > 1 MiB, element count > 10 000, depth > 64, attribute values > 4 KiB, text content > 4 KiB. Violations → `MarkupException` (typed, bounded; see below).
- `MarkupException`: `enum Kind { MALFORMED_XML, UNKNOWN_TAG, UNKNOWN_ATTRIBUTE, DUPLICATE_ID, MISSING_ATTRIBUTE, INVALID_VALUE, TOO_LARGE, STYLE_ERROR, UNRESOLVED_STYLE }` + `String elementPath` (e.g. `ui/table/button[2]`), `int line`, `int column`, `String message`. All diagnostics carry these; never a bare exception.
- Tag whitelist and per-tag attribute whitelist (below). `id` must be unique across the document. Unknown tag/attribute, missing required attribute, bad value → typed `MarkupException`.

Tag vocabulary (tag → actor, built in Step 4):

| tag | actor | notes |
|---|---|---|
| `ui` | — | document root, no actor |
| `table` | `Table` | default container; root `table` is the built root |
| `row` | — | `table.row()`; self-closing, no actor, no attrs |
| `stack` | `Stack` | |
| `group` | `Group` | |
| `scrollpane` | `ScrollPane` | exactly one child |
| `label` | `Label` | text from content or `text` attr |
| `button` | `TextButton` | text from content or `text` attr |
| `checkbox` | `CheckBox` | `checked` |
| `textfield` | `TextField` | `editable`, `text` |
| `selectbox` | `SelectBox` | `items="a,b,c"` |
| `slider` | `Slider` | `min`,`max`,`step`,`value` |
| `progressbar` | `ProgressBar` | `min`,`max`,`value` |
| `image` | `Image` | `drawable` (skin drawable name) |
| `window` | `Window` | `title` |
| `list` | `List` | `items="a,b,c"` |

Common attributes (valid on every actor tag): `id`, `name`, `label`, `class`, `style`, `disabled`, `visible`, `focusable`, `width`, `height`, `min-width`, `min-height`, `expand`, `fill`, `align`, `colspan`, `pad`, `pad-top`, `pad-right`, `pad-bottom`, `pad-left`, `space`, `grow`, `grow-x`, `grow-y`, `uniform`, `data-*` (any suffix → semantic property). `align`/`fill` values: `top|bottom|left|right|center` (combine with spaces, e.g. `align="center center"`); `expand`/`fill`/`grow` are booleans or `x`/`y`. Layout attributes only apply inside `table`; `width`/`height`/`min-*` are cell constraints in a table, fixed actor size otherwise.

- Tests (no GL): valid doc → correct tree; every error kind fires with correct line/column; duplicate id; bounds (1 MiB / 10k nodes / depth 64 / 4 KiB strings); DOCTYPE + external entity rejection; text-content capture.
- Verify: `./gradlew :gdx-ui-markup:test`.

### Step 3 — `gdx-ui-markup` core: CSS subset (GL-free)

Package `dev.gdx.markup.core.style`.

- `CssDocument` — rules `selector { prop: value; }`. Selectors: `tag`, `.class`, `#id`, `tag.class`, comma-separated lists. **No combinators/descendants in v1** (decision: classes on elements suffice; keeps the cascade deterministic). Pseudo-states on any selector: `:hover`, `:pressed`, `:checked`, `:disabled`.
- Property whitelist (bounded): `color`, `font`, `font-color`, `background`, `background-over`, `background-down`, `background-checked`, `background-disabled`, `padding`, `padding-top|right|bottom|left`, `margin`, `margin-top|right|bottom|left`, `width`, `height`, `min-width`, `min-height`, `text-align`, `visible`. Unknown property or unparseable selector → `MarkupException(STYLE_ERROR, "css", ruleIndex, 0, msg)`.
- Cascade: specificity `#id`(100) > `.class`(10) > `tag`(1); `tag.class` = 11; later rule wins ties. Applied per element → immutable `ResolvedStyle` (property → value map). Pseudo-states select style variants of the same element (e.g. `button:hover` contributes the hover variant, not a replacement).
- Mapping contract (used in Step 4): `padding*` → `Cell.pad*`, `margin*` → `Cell.space*`, `background*` → skin drawable/style fields per widget state, `color`/`font`/`font-color`/`text-align` → Label/Button styling, `width`/`height`/`min-*`/`visible` → actor size/visibility.
- Tests: selector matching matrix, specificity ordering, tie-break, pseudo-state selection, `tag.class`, invalid syntax/unknown property errors, comma-group rules.
- Verify: `./gradlew :gdx-ui-markup:test`.

### Step 4 — `gdx-ui-markup` core: builder on the render thread + registry + default Skin + semantic sink

Package `dev.gdx.markup.core`.

- `MarkupRegistry`: `void register(String tag, TagFactory)` — `TagFactory` creates the actor and applies attributes (reuses the gdx-lml provider-registry lesson; enables custom widgets without touching the core). Built-in factories for the Step 2 vocabulary. Registration is global per registry instance; a `MarkupRegistry.defaultRegistry()` exists.
- `SemanticSink` SPI — string-based, **no harness dependency** in core:
  ```java
  interface SemanticSink {
      void role(Actor actor, String role);
      void accessibleName(Actor actor, String name);
      void testId(Actor actor, String id);
      void label(Actor actor, String label);
      void property(Actor actor, String key, String value);
  }
  ```
  `NoopSink` (default): sets `actor.setName(id)` when `id` present; no-ops the rest. (Even harness-less apps get `findActor`-able names.)
- `MarkupBuilder.build(MarkupDocument, CssDocument, Skin skin, SemanticSink sink)` → `BuiltUi { Group root; List<Actor> actors; }`. **Must be called on the GL/render thread** (javadoc + AGENTS.md rule; the preview app calls it from `render()`). Two-phase parse→build keeps Step 2/3 GL-free and unit-testable.
- Skin handling: if the caller passes a `Skin`, use it; otherwise build a programmatic default — copy the pattern from `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceScreen.java` `createSkin()` (pixel texture + `BitmapFont` + hand-built styles for every vocabulary tag). CSS `background*`/`color`/`font` properties compile into the skin: named styles `tag`, `tag-over`, `tag-down`, `tag-checked`, `tag-disabled` per pseudo-state; per-actor overrides applied directly (e.g. `Label` color/font). Missing drawable/font name in the skin → `MarkupException(UNRESOLVED_STYLE, elementPath, line, column, ...)`.
- Cell mapping inside a table: `expand`→`Cell.expand`, `fill`→`Cell.fill`, `align`→`Cell.align`, `colspan`→`Cell.colspan`, `pad*`→`Cell.pad*`, `space*`→`Cell.space*`, `width/height/min-*`→cell size, `grow-x/grow-y`→`Cell.growX/growY`, `uniform`→`Cell.uniform`. `style` attr → named skin style for the widget type.
- Sink calls per element (this is the harness-benefit core): `id` → `testId` + `actor.setName`; `name` → `accessibleName`; `label` attr → `label`; tag → `role` with canonical strings `button, checkbox, textfield, selectbox, slider, progressbar, list, window` (table/stack/group/label/scrollpane/image emit no role); `data-*` → `property`.
- Bounds re-enforced at build (same constants as parse).
- Tests (need GL — run under Xvfb like the harness CI): build `samples/signin.xml`+CSS with default skin; assert tree shape (root table → window → expected children), button text, checkbox checked state, cell constraints (expand/fill/pad), sink recorded calls (fake sink), unknown style name → `UNRESOLVED_STYLE`, `row` outside table → typed error.
- Verify: `xvfb-run -a ./gradlew :gdx-ui-markup:test`.

### Step 5 — `gdx-ui-markup-preview`: LWJGL3 app with hot reload, CLI, and optional MCP

Package `dev.gdx.markup.preview`.

- `PreviewApp` — standard `Lwjgl3Application` (1280x720, "gdx-ui-markup preview"). Builds the scene from `--ui <file.xml> --css <file.css> [--skin <file.json>]` on the GL thread; renders it; `esc` quits.
- Hot reload: `java.nio.file.WatchService` on the files' directory, 300 ms debounce, rebuild on the GL thread. Parse/build errors render as a red overlay `Label` (`elementPath:line: message`) and print one bounded status line to stdout: `markup-status: {"ok":false,"message":"...","line":N,"column":N}`; success: `markup-status: {"ok":true,"nodes":N}`.
- CI flags: `--frames N --screenshot <path> --exit` (render N frames, save PNG, exit 0); on build error with `--exit`, exit code 2 after printing the typed message.
- `--mcp` flag: bind a `Scene2dSession` to the stage and start the harness MCP stdio server, mirroring `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java`'s wiring, using published `io.github.teemuki8:harness-mcp:1.0.0` + `harness-lwjgl3`. This makes the preview an agent-drivable harness target (the "for AI agents" story) and the E2E target for Step 6.
- Depends on `gdx-ui-markup`; ships `samples/` in its distribution.
- Verify: `xvfb-run -a ./gradlew :gdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'` → exit 0, `build/signin.png` exists. Error path: `--ui samples/broken.xml` (one unknown tag) → exit 2, stdout carries `markup-status` with the unknown tag and line.

### Step 6 — `gdx-ui-markup-harness`: semantic sink adapter + end-to-end proof

Package `dev.gdx.markup.harness`. Depends on `gdx-ui-markup` + published `io.github.teemuki8:harness-scene2d:1.0.0` (brings Java 25 + libGDX 1.14.2).

- `HarnessSemanticSink implements SemanticSink` — wraps the harness `Semantics` facade (`/home/tjaaskel/git/libgdx-ui-harness/harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Semantics.java`): string → `Role` by exact enum-member-name match (read the enum from the published jar); unknown string → skip role with a debug log (testId/name still drive locators). Calls `setTestId`, `setAccessibleName`, `setLabel`, `setRole`, `setProperty`.
- `MarkupHarnessEndToEndTest` (mirror `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/DeclarativeAssertionFixtureTest.java`): launch the preview distribution in a subprocess with `--ui samples/signin.xml --css samples/signin.css --mcp`; connect a minimal stdio JSON-RPC client (test code, mirrors `HarnessMcpClient`) and:
  1. `ui_query` locator `role=button` + `name=Save` → exactly one node, `testId == "save"` (proves semantics-by-construction);
  2. `ui_action` click on it → `ui_assert` the checkbox becomes `checked` (state change through the real input path);
  3. `ui_screenshot` returns a PNG.
- Verify: `xvfb-run -a ./gradlew :gdx-ui-markup-harness:test`.

### Step 7 — `gdx-ui-markup-idea`: thin IntelliJ plugin

Package `dev.gdx.markup.idea` (Kotlin). `org.jetbrains.intellij.platform` **2.18.1** (requires Gradle 9.0+ — wrapper is 9.6.1 ✓ — and IntelliJ Platform 2023.3+; baseline IDEA Community 2024.3, `sinceBuild 243`). Plugin JVM is IDEA's JBR (Java 21+), so this module compiles with its own toolchain — do not force Java 25 here.

- `src/main/resources/META-INF/plugin.xml`: toolWindow `id="Markup Preview"` (anchor right), one action "Launch markup preview" enabled for XML files.
- Tool window: plain Swing `JPanel` — status `JLabel` (last `markup-status` line, bounded), buttons Launch / Reload / Watch toggle. Status parsed from the preview process stdout (bounded read on a background thread; never block the UI thread).
- `PreviewProcessLauncher`: resolves the preview distribution bundled by the `preparePreview` task (copies `:gdx-ui-markup-preview:installDist` output into the plugin build dir at build time; resolves from the plugin's install path at runtime), launches `java -jar … --ui <current-file> --css <sibling .css if present>`, streams stdout for `markup-status:` lines to the tool window.
- Tests (pure Kotlin, no IDE runtime): status-line parser, launcher argument construction, watch-debounce logic.
- Verify: `./gradlew :gdx-ui-markup-idea:buildPlugin` → zip under `build/distributions`. Manual (documented in README): install the zip in IDEA, open `samples/signin.xml`, Launch → native window shows the form; change button text in the XML → preview updates within ~1 s; break the XML → red overlay + tool-window status with element path and line.

### Step 8 — ADR + README

- `docs/adr/0001-declarative-scene-authoring.md` in the new repo: dialect decision (HTML-like XML + CSS-styling-only; no CSS layout engine), two-phase parse→build (GL-free model, render-thread build), string-based `SemanticSink` SPI + harness adapter mapping, bounded limits, prior-art lessons (gdx-lml provider-registry pattern reused; Table/Cell is the layout engine). Required by the new repo's own AGENTS.md rule (lasting decisions need an ADR).
- README quick start: the sample XML + CSS, run the preview, and a harness locator snippet (`Locator.testId("save")`) showing the integration contract.
- Verify: ADR has no placeholders; README code blocks compile (they are the sample files).

## Critical files & anchors

- `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceScreen.java` — `createSkin()` (programmatic pixel-texture+font skin pattern) and `tag()` (semantics calls the SPI replaces).
- `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` — the MCP/Scene2dSession wiring `--mcp` mirrors.
- `/home/tjaaskel/git/libgdx-ui-harness/harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/DeclarativeAssertionFixtureTest.java` — subprocess-launch + MCP-client E2E pattern to mirror.
- `/home/tjaaskel/git/libgdx-ui-harness/harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Semantics.java` — the API `HarnessSemanticSink` targets (`setTestId/setAccessibleName/setLabel/setRole/setProperty`).
- `/home/tjaaskel/git/libgdx-ui-harness/gradle/libs.versions.toml` — exact libGDX/JUnit versions to copy.

## Verification

Ladder (narrowest first), all from `/home/tjaaskel/git/gdx-ui-markup`:

1. GL-free core: `./gradlew :gdx-ui-markup:test` (Steps 2–3 land without Xvfb).
2. Render-thread core: `xvfb-run -a ./gradlew :gdx-ui-markup:test` (Step 4 builder tests need a GL context; same Xvfb approach as harness CI).
3. Preview smoke — NEW behavior proof: `xvfb-run -a ./gradlew :gdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'` → exit 0 + PNG; `--ui samples/broken.xml` → exit 2 + typed `markup-status` on stdout.
4. Harness E2E — the "does the harness benefit" proof: `xvfb-run -a ./gradlew :gdx-ui-markup-harness:test` → MCP client locates `role=button name=Save` with `testId=save`, clicks it, asserts the state change, captures a screenshot — all against a UI declared in markup, no imperative wiring.
5. Plugin: `./gradlew :gdx-ui-markup-idea:buildPlugin` + the documented manual IDEA session (launch, hot-reload on edit, error display on breakage).
6. Full: `xvfb-run -a ./gradlew build` with `-Werror` on all four modules.

## Assumptions & contingencies

- Repo path `/home/tjaaskel/git/gdx-ui-markup`. If it already exists or the user prefers another location, build there — nothing else changes.
- Group `io.github.teemuki8`, Apache-2.0, Java 25 baseline (forced by the harness-scene2d dependency). Publishing to Maven Central is out of v1; tests use `publishToMavenLocal` if a dependency must resolve locally.
- IDEA plugin coordinates: `org.jetbrains.intellij.platform` 2.18.1; if it misbehaves with the pinned toolchain, fall back to 2.10.5 (2025 baseline, same API surface for this feature set). Kotlin version: latest stable compatible with the plugin (resolve mechanically at build).
- If the harness `Role` enum lacks a builder-emitted role string, `HarnessSemanticSink` skips that role — locators still resolve via testId/name; the E2E test asserts testId/name/state, not the skipped role.
- If LWJGL3 under this machine's NVIDIA driver fails headless, tests use `xvfb-run` exactly like the harness CI; interactive preview is a real window and unaffected.
- "or similar convenient UI" fallback: if the IDEA plugin proves unworkable in the user's environment, the preview app is the UI — open the XML in any editor, watch-mode hot reload gives the same live editing loop with zero feature loss (same core, no plugin needed).
