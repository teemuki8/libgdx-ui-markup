# FreeType Font Size Implementation Plan

**Implementation status (2026-08-09):** Complete. All task checkboxes below reflect the
implemented and reviewed working tree; the final verification commands remain recorded in the
handoff rather than embedded as transient build output here.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute inline unless the user explicitly authorizes subagent delegation.

**Goal:** Make `font-size` a first-class, bounded XML/CSS styling feature and render it with exact-size `gdx-freetype` fonts that remain clear on LWJGL3 desktop and HiDPI displays.

**Architecture:** Keep font declarations in the existing GL-free immutable XML/CSS model as validated strings. On the render thread, a concrete `FreeTypeFontManager` attached to the owning `Skin` maps application-registered family names to trusted TTF resources, lazily generates and caches `BitmapFont` instances, and lets the `Skin` dispose every generated font exactly once. There is deliberately no font-renderer SPI, MSDF implementation seam, shader path, or runtime font path accepted from markup.

**Tech Stack:** Java 25, libGDX/Scene2D 1.14.2, `gdx-freetype` 1.14.2, LWJGL3 desktop natives, Gradle Wrapper 9.6.1, JUnit 5.10.2, Xvfb.

## Global Constraints

- V1 targets LWJGL3 desktop and Scene2D/Scene2D.UI.
- XML/CSS parsing remains GL-free; no `Actor`, `Stage`, libGDX collection, backend, `FileHandle`, or FreeType type enters `MarkupDocument`, `Element`, `CssDocument`, `CssRule`, or `ResolvedStyle`.
- All font generation, `Skin`, `Actor`, and `Stage` work runs on the libGDX render thread.
- Use only `gdx-freetype`; do not add MSDF, SDF, renderer interfaces, shader hooks, backend selection, or speculative extension points.
- Markup selects registered family names only. It never accepts filesystem paths, URLs, reflection targets, or unrestricted asset names.
- XML `font-size` is an integer from 4 through 256 inclusive. CSS accepts the same integer with optional `px`.
- A font size may not change in a pseudo-state; such CSS is a located `STYLE_ERROR`.
- One manager accepts at most 16 registered families, 2,048 configured BMP characters, and 64 distinct cached `(family, logicalSize)` fonts.
- The default glyph set is libGDX `DEFAULT_CHARS` plus en dash, em dash, curly quotes, ellipsis, and bullet. Applications needing another script must provide a bounded explicit glyph set when installing their manager.
- Generate a physical-pixel atlas at `round(logicalSize * rasterScale)`, then scale `BitmapFontData` back by `logicalSize / rasterSize` so Scene2D layout remains in logical units.
- `rasterScale` is finite and bounded to 1.0 through 4.0. The preview derives it from back-buffer size divided by logical window size.
- Generated fonts use `Hinting.AutoMedium`, kerning, gamma 1.8, render count 2, linear minification/magnification, no mipmaps, and non-incremental glyph generation.
- The bundled font is unmodified Inter Regular 4.1 under SIL OFL 1.1. Preserve its license beside the font in the published JAR.
- TTF fixture SHA-256: `40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82`.
- OFL file SHA-256: `262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a`.
- Every production behavior starts with a failing behavioral test and follows red-green-refactor.
- Diagnostics retain kind, element path, line, column, and actionable message.
- Use `./gradlew`, JDK 25, `-Xlint:all`, and warnings-as-errors.
- Do not commit or push unless the user separately asks for Git publication.

## Accepted Syntax and Precedence

```xml
<label id="title" font="inter" font-size="28" text="New campaign"/>
<button id="confirm" font-size="18" text="Confirm"/>
```

```css
label.title { font: inter; font-size: 28px; }
button { font-size: 18px; }
```

Resolution rules:

1. An XML `font` attribute overrides CSS `font` for that element.
2. An XML `font-size` attribute overrides CSS `font-size` for that element.
3. When an effective `font-size` exists, the effective `font` value is a registered FreeType family; when omitted, the manager's default family is used.
4. When no effective `font-size` exists, existing `font` behavior remains: first resolve a named `BitmapFont` from the `Skin`, then fall back to the manager's family at the default logical size of 16.
5. The default skin registers family `inter`, uses Inter 16 for its widget styles, and retains the public `default-font` skin resource name for compatibility.
6. Only text-bearing built-ins accept XML font attributes: `label`, `button`, `checkbox`, `textfield`, `selectbox`, `window`, and `list`.

## File Map

- Modify `gradle/libs.versions.toml`: add `gdx-freetype` and `gdx-freetype-platform` aliases at version 1.14.2.
- Modify `libgdx-ui-markup/build.gradle.kts`: add the FreeType implementation and desktop-native runtime dependencies.
- Update every affected `gradle.lockfile`, `gradle/verification-metadata.xml`, and keyring only if the verified signer set changes.
- Create `libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-Regular.ttf`: bundled UI font.
- Create `libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-OFL.txt`: font license.
- Create `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/FreeTypeFontManager.java`: concrete registered-family manager, bounded cache, physical/logical scaling, and skin-owned lifecycle.
- Create `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/FreeTypeFontManagerTest.java`: render-thread cache, scaling, bounds, failure, and disposal tests.
- Modify `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java`: whitelist and validate XML `font`/`font-size` only on text-bearing tags.
- Modify `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`: whitelist, validate, and reject pseudo-state `font-size`.
- Modify `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/SkinStyleCompiler.java`: leave font selection to the per-actor resolved cascade when a size is involved; preserve old named-skin-font behavior without size.
- Modify `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`: combine XML/CSS precedence, resolve FreeType fonts, clone widget styles, and emit typed failures.
- Modify `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/DefaultSkin.java`: install the manager transactionally and use Inter 16 instead of `new BitmapFont()`.
- Modify `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java`: XML syntax and bounds tests.
- Modify `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`: CSS syntax, cascade, bound, and pseudo rejection tests.
- Modify `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`: resolved font behavior and located failure tests.
- Modify `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`: attach default FreeType fonts to custom skins and derive the raster scale for each candidate rebuild.
- Modify `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`: transactional candidate/font ownership coverage.
- Modify `samples/signin.xml` and `samples/signin.css`: exercise declared title/body/control font sizes through the real preview and harness fixtures.
- Modify `README.md` and `docs/guides/embedding.md`: document syntax, app registration, ownership, HiDPI, and glyph bounds with compilable examples.
- Create `docs/adr/0004-freetype-font-sizing.md`: record FreeType-only architecture and the explicit rejection of MSDF abstraction debt.
- Modify `libgdx-ui-markup-qualification/corpus/manifest.json` only through the calibration task if the verified font rendering changes measured thresholds beyond the repository's drift tolerance.

---

### Task 1: Add and verify the FreeType dependency and bundled Inter asset

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `libgdx-ui-markup/build.gradle.kts`
- Create: `libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-Regular.ttf`
- Create: `libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-OFL.txt`
- Modify: `libgdx-ui-markup/gradle.lockfile`
- Modify: other module lockfiles reached transitively
- Modify: `gradle/verification-metadata.xml`

**Interfaces:**
- Produces aliases `libs.gdx.freetype` and `libs.gdx.freetype.platform`.
- Produces classpath resources `META-INF/fonts/Inter-Regular.ttf` and `META-INF/fonts/Inter-OFL.txt`.

- [x] **Step 1: Add dependency aliases and module dependencies**

```toml
[libraries]
gdx-freetype = { module = "com.badlogicgames.gdx:gdx-freetype", version.ref = "gdx" }
gdx-freetype-platform = { module = "com.badlogicgames.gdx:gdx-freetype-platform", version.ref = "gdx" }
```

```kotlin
dependencies {
    api(libs.gdx)
    implementation(libs.gdx.freetype)
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
}
```

- [x] **Step 2: Copy the pinned local Fedora Inter 4.1 files into resources and verify identity**

```bash
install -Dm644 /usr/share/fonts/rsms-inter-fonts/Inter-Regular.ttf \
  libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-Regular.ttf
install -Dm644 /usr/share/licenses/rsms-inter-fonts/LICENSE.txt \
  libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-OFL.txt
sha256sum libgdx-ui-markup/src/main/resources/META-INF/fonts/Inter-*
```

Expected hashes are the two values in Global Constraints.

- [x] **Step 3: Bootstrap locks and verification metadata in one fresh-cache pass**

```bash
dependency_bootstrap_home="$(mktemp -d)"
GRADLE_USER_HOME="$dependency_bootstrap_home" ./gradlew --no-daemon \
  --write-locks --write-verification-metadata pgp,sha256 --export-keys \
  help resolveAndLockAll :libgdx-ui-markup-idea:buildPlugin \
  :libgdx-ui-markup-idea:unitTest :libgdx-ui-markup-preview:installDist javadoc \
  :libgdx-ui-markup:publishMavenJavaPublicationToCentralStagingRepository \
  :libgdx-ui-markup-runtime:publishMavenJavaPublicationToCentralStagingRepository \
  :libgdx-ui-markup-harness:publishMavenJavaPublicationToCentralStagingRepository
```

- [x] **Step 4: Review the generated trust diff**

Require file-exact entries for `gdx-freetype-1.14.2` and `gdx-freetype-platform-1.14.2-natives-desktop`, checksums on every new artifact, and signer `5F9B2AF3084E4EADB2F5AE1EB0F79A98780D77FA` only if the artifacts carry the same verified libGDX signature. Reject wildcard trust, ignored keys, and unscoped generated trust.

- [x] **Step 5: Prove strict non-writing resolution**

Run: `./gradlew resolveAndLockAll --warning-mode=fail`

Expected: `BUILD SUCCESSFUL`; no lock or verification file changes after the command.

---

### Task 2: Add GL-free XML and CSS font-size contracts

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/TagSpec.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`

**Interfaces:**
- Produces `TagSpec.MIN_FONT_SIZE == 4` and `TagSpec.MAX_FONT_SIZE == 256`.
- Produces validated XML attributes `font` and `font-size` on text-bearing tags.
- Produces CSS properties `font` and `font-size`, with `font-size` normalized as the original bounded string and interpreted later by the builder.

- [x] **Step 1: Write failing XML behavioral tests**

Add tests asserting:

```java
Element title = parser.parse("""
        <ui><label font="inter" font-size="28" text="Title"/></ui>
        """).root().children().get(0);
assertEquals("inter", title.attr("font"));
assertEquals("28", title.attr("font-size"));
```

Also assert `INVALID_VALUE` with the element path and coordinates for `font-size="3"`, `font-size="257"`, `font-size="16.5"`, and `font-size="16px"`; assert `UNKNOWN_ATTRIBUTE` for `font-size` on `image`, `slider`, and `table`.

- [x] **Step 2: Run the narrow XML tests and confirm RED**

Run: `./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupParserTest`

Expected: failures report unknown `font`/`font-size` attributes.

- [x] **Step 3: Implement the minimal XML whitelist and bound validator**

Add `ValueKind.FONT_SIZE`, parse an integer without units, and accept only 4 through 256. Add `font` as `TEXT` and `font-size` as `FONT_SIZE` to the seven text-bearing tag-specific maps, not to `COMMON`.

- [x] **Step 4: Run the XML tests and confirm GREEN**

Run: `./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupParserTest`

Expected: all `MarkupParserTest` tests pass.

- [x] **Step 5: Write failing CSS behavioral tests**

```java
CssDocument document = parser.parse("label.title { font: inter; font-size: 28px; }");
ResolvedStyle style = new CssStyleResolver(document)
        .resolve(element("label", null, List.of("title")));
assertEquals("inter", style.get("font"));
assertEquals("28px", style.get("font-size"));
```

Also assert `STYLE_ERROR` at the property coordinates for sizes 3, 257, 16.5, negative, non-numeric, and for `label:hover { font-size: 20px; }`.

- [x] **Step 6: Run the narrow CSS tests and confirm RED**

Run: `./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssTest`

Expected: `font-size` is unknown.

- [x] **Step 7: Implement the minimal CSS property kind and pseudo rejection**

Add `PropertyKind.FONT_SIZE`, accept integer `4..256` with optional `px`, and reject a declaration when any selector in its rule has a pseudo-state. Report the property token line/column, not the selector or rule start.

- [x] **Step 8: Run all GL-free core tests**

Run: `./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupParserTest --tests dev.gdx.markup.core.style.CssTest`

Expected: all selected tests pass.

---

### Task 3: Implement the concrete, bounded FreeType manager

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/FreeTypeFontManager.java`
- Create: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/FreeTypeFontManagerTest.java`

**Interfaces:**
- Produces `FreeTypeFontManager.installDefault(Skin skin, float rasterScale)`.
- Produces `FreeTypeFontManager.install(Skin skin, String defaultFamily, Map<String, FileHandle> families, String characters, float rasterScale)`.
- Produces `BitmapFont font(String family, int logicalSize)` and `BitmapFont defaultFont()`; installation eagerly seeds the default family at logical size 16.
- Produces package-visible `static FreeTypeFontManager optional(Skin skin)` for builder integration.
- Produces package-visible seams accepting smaller cache/family limits for boundary tests, without changing production constants.

- [x] **Step 1: Write failing render-thread manager tests**

Inside `GdxTestHost.run(...)`, cover:

```java
Skin skin = new Skin();
FreeTypeFontManager fonts = FreeTypeFontManager.installDefault(skin, 1f);
BitmapFont first = fonts.font("inter", 18);
BitmapFont again = fonts.font("inter", 18);
BitmapFont larger = fonts.font("inter", 28);
assertSame(first, again);
assertNotSame(first, larger);
assertEquals(Texture.TextureFilter.Linear,
        first.getRegions().first().getTexture().getMinFilter());
assertEquals(Texture.TextureFilter.Linear,
        first.getRegions().first().getTexture().getMagFilter());
```

Also test logical metrics at raster scales 1 and 2, absent family, invalid constructor bounds, duplicate installation, cache saturation through a package-visible limit of 2, idempotent manager disposal, rejection after disposal, and `Skin.dispose()` disposing manager-owned generators and every generated font once.

- [x] **Step 2: Run the manager tests and confirm RED**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.FreeTypeFontManagerTest`

Expected: compilation fails because `FreeTypeFontManager` does not exist.

- [x] **Step 3: Implement construction and ownership**

Use a final concrete class implementing `Disposable`. Validate the immutable family map and glyph string before creating generators. If generator creation fails part-way, dispose already-created generators in reverse order. Seed the default family at logical size 16 during installation and register it exactly once as `default-font` when that name is free, otherwise under its reserved per-key name. Attach the manager to the skin under a private reserved name and register every later generated font exactly once under a reserved per-key resource name. Installation must roll back both manager and seed font if either skin registration fails. The manager disposes generators only; the skin disposes generated `BitmapFont` resources.

- [x] **Step 4: Implement bounded lazy generation**

Use a private immutable key:

```java
private record FontKey(String family, int logicalSize) {}
```

For a cache miss, compute `rasterSize = Math.round(logicalSize * rasterScale)`, configure `FreeTypeFontParameter` with the Global Constraints, generate the font, call `font.getData().setScale(logicalSize / (float) rasterSize)`, disable bitmap markup, then register/cache it transactionally. A failed generation or registration must dispose its font and leave cache size unchanged.

- [x] **Step 5: Run manager tests and confirm GREEN**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.FreeTypeFontManagerTest`

Expected: all selected tests pass with no native-access warning promoted by project code.

- [x] **Step 6: Run Javadoc and warning gates for the public API**

Run: `./gradlew :libgdx-ui-markup:compileJava :libgdx-ui-markup:javadoc --warning-mode=fail`

Expected: `BUILD SUCCESSFUL` with `-Werror` and Javadoc `Werror` active.

---

### Task 4: Resolve XML/CSS sizes into per-actor Scene2D styles

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/SkinStyleCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/DefaultSkin.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Consumes `FreeTypeFontManager.optional(Skin)` and `font(family, logicalSize)`.
- Preserves `MarkupBuilder.build(MarkupDocument, CssDocument, Skin, SemanticSink)`.
- Preserves named `Skin` `BitmapFont` behavior when no size is declared.

- [x] **Step 1: Write failing builder tests**

Cover the following behaviors on the render thread:

```java
Skin skin = DefaultSkin.create();
BuiltUi ui = MarkupBuilder.build(
        parser.parse("<ui><label id=\"small\" font-size=\"14\" text=\"A\"/>"
                + "<label id=\"large\" font=\"inter\" font-size=\"28\" text=\"B\"/></ui>"),
        css.parse(""), skin, new NoopSink());
Label small = (Label) ui.root().findActor("small");
Label large = (Label) ui.root().findActor("large");
assertTrue(large.getStyle().font.getLineHeight() > small.getStyle().font.getLineHeight());
```

Also assert:

- same family/size shares one `BitmapFont` identity;
- XML font and size each override CSS independently;
- class-only CSS `font-size` applies;
- `font` without `font-size` still resolves a named skin font;
- `font-size` without an attached manager is `UNRESOLVED_STYLE` at the element;
- unknown family is `UNRESOLVED_STYLE` at the element;
- the 65th distinct family/size is `TOO_LARGE` at the triggering element;
- label, button, checkbox, textfield (including message font), selectbox (including dropdown list), window title, and list receive the generated font without mutating shared styles;
- a failed build leaves the candidate skin responsible for all generated resources.

- [x] **Step 2: Run builder tests and confirm RED**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupBuilderTest`

Expected: parsed attributes are not reflected in widget fonts.

- [x] **Step 3: Make per-actor font resolution authoritative**

Stop compiling `font` directly into tag styles in `SkinStyleCompiler`; `MarkupBuilder` already has the fully resolved per-element cascade and must own font selection. Compute effective family and size with XML-over-CSS precedence once per actor. Clone the actor's style before mutation. If a size exists, resolve the family through `FreeTypeFontManager`; otherwise resolve the named skin font first and then a registered family at size 16.

- [x] **Step 4: Apply generated fonts to all text-bearing style fields**

Update copied styles without mutating nested shared state:

- `LabelStyle.font`
- `TextButtonStyle.font` and `CheckBoxStyle.font`
- `TextFieldStyle.font` and `messageFont`
- `SelectBoxStyle.font` plus a copied `ListStyle.font`
- `WindowStyle.titleFont`
- `ListStyle.font`

Do not change fonts for slider, progress bar, image, table, group, stack, or scroll pane. Retain the existing located unsupported-state behavior.

- [x] **Step 5: Install FreeType transactionally in the default skin**

After the pixel texture is owned by the candidate `Skin`, install the default manager, obtain its already registered Inter 16 through `defaultFont()`, and build styles from it. Do not add the font to the skin a second time. If any later operation fails, dispose the whole candidate skin so the pixel texture, manager/generators, and generated fonts are released. Preserve the injected pixel/texture factory ownership tests.

- [x] **Step 6: Run builder and default-skin tests and confirm GREEN**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupBuilderTest --tests dev.gdx.markup.core.DefaultSkinPaletteTest --tests dev.gdx.markup.core.FreeTypeFontManagerTest`

Expected: all selected tests pass.

- [x] **Step 7: Run the full core suite**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup:test`

Expected: all core tests pass.

---

### Task 5: Integrate exact-size fonts into preview, sample, harness, and packaging

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Modify: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`
- Modify: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewStartScriptTest.java`
- Modify: `samples/signin.xml`
- Modify: `samples/signin.css`

**Interfaces:**
- Consumes `FreeTypeFontManager.installDefault(Skin, rasterScale)` for a user-provided JSON skin.
- Produces `PreviewApp.rasterScale(int logicalWidth, int logicalHeight, int backBufferWidth, int backBufferHeight)` as a package-visible pure calculation seam.

- [x] **Step 1: Write failing preview tests**

Assert that raster scale returns 1 for equal dimensions, 2 for a 2x backing buffer, takes the larger axis when ratios differ, and clamps to 1..4. In a render-thread candidate test, assert both default and custom skins contain an attached manager and that candidate rollback/commit keeps the prior skin live while disposing only candidate resources.

- [x] **Step 2: Run preview tests and confirm RED**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test --tests dev.gdx.markup.preview.PreviewAppTest`

Expected: the raster-scale seam and custom-skin manager are absent.

- [x] **Step 3: Install fonts for every candidate skin**

For the default path, call a `DefaultSkin.create(rasterScale)` overload. For `--skin`, load the JSON skin first and then call `FreeTypeFontManager.installDefault(customSkin, rasterScale)`. Keep the manager inside the candidate skin so the existing transactional `Candidate.close()` and committed-skin retirement remain the only lifecycle owners.

- [x] **Step 4: Put declared sizes in the canonical sample**

Use XML attributes for semantic one-off sizes and CSS for repeated sizes. The title must be 28, field/checkbox body text 18, and primary button 20. Avoid adding weight/style support.

- [x] **Step 5: Verify preview distribution contains Java/native/font artifacts**

Extend packaging assertions to require:

- `gdx-freetype-1.14.2.jar`;
- `gdx-freetype-platform-1.14.2-natives-desktop.jar`;
- `META-INF/fonts/Inter-Regular.ttf` and `META-INF/fonts/Inter-OFL.txt` inside the core JAR.

- [x] **Step 6: Run preview tests and smoke paths**

Run:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin-freetype.png --exit'
test -s build/signin-freetype.png
```

Expected: tests pass, status is `ok`, and the PNG exists and is non-empty.

- [x] **Step 7: Exercise the typed preview error path**

Create a temporary XML fixture containing `<label font-size="257" text="bad"/>`, run the packaged preview with `--exit`, and assert exit code 2 plus `INVALID_VALUE`, element path, line, and column in the bounded status. Remove the temporary fixture afterward.

- [x] **Step 8: Run harness E2E**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test`

Expected: the markup-declared UI remains queryable/actionable through MCP and screenshot capture succeeds with FreeType text.

---

### Task 6: Document the lasting decision and public embedding contract

**Files:**
- Create: `docs/adr/0004-freetype-font-sizing.md`
- Modify: `README.md`
- Modify: `docs/guides/embedding.md`

**Interfaces:**
- Documents the exact XML/CSS contract and concrete `FreeTypeFontManager` setup.

- [x] **Step 1: Write ADR 0004**

Record Status `Accepted`, Context, Decision, Consequences, and Rejected Alternatives. State explicitly that exact-size FreeType rasterization fits static Scene2D UI and accessibility reflow; MSDF/SDF and a renderer SPI are rejected because continuous game-world zoom is not a requirement and the shader/batching/asset-pipeline cost would be deliberate technical debt.

- [x] **Step 2: Update README syntax and examples**

Show `font="inter" font-size="28"`, CSS `font-size: 18px`, the 4..256 bounds, XML-over-CSS precedence, the bundled default, custom family registration from application-owned `FileHandle`s, and disposal through `Skin.dispose()`.

- [x] **Step 3: Update embedding lifecycle guidance**

Document that parsing remains GL-free; manager installation, build, resize-triggered rebuild, and disposal are render-thread work. Explain that an accessibility scale is applied by rebuilding requested logical sizes and layout, not by scaling the Stage or retaining an MSDF hook.

- [x] **Step 4: Verify public API examples and documentation**

Run: `./gradlew javadoc :libgdx-ui-markup:compileTestJava --warning-mode=fail`

Expected: warning-free success. Run `rg -n "MSDF|SDF|TODO|TBD" README.md docs/adr/0004-freetype-font-sizing.md docs/guides/embedding.md`; MSDF/SDF may appear only in the ADR's explicitly rejected alternative, and no placeholders may appear.

---

### Task 7: Recalibrate visual qualification and run the complete gate

**Files:**
- Possibly modify through task output: `libgdx-ui-markup-qualification/corpus/manifest.json`
- Review all files in `git diff`

**Interfaces:**
- Produces a verified FreeType rendering baseline across core, preview, harness, qualification, and IDEA packaging.

- [x] **Step 1: Run qualification before calibration and record the effect**

Run: `xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test`

Expected: either all entries pass within current thresholds or the test specifically reports threshold drift caused by the new font rendering.

- [x] **Step 2: Calibrate the changed renderer and verify the calibrated gate**

Run:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:calibrateQualification
xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test
```

Expected: calibration writes only measured thresholds in the corpus manifest; the subsequent qualification test passes. Review every threshold delta against the generated report rather than accepting it blindly.

- [x] **Step 3: Run runtime and IDEA plugin gates**

Run:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test
./gradlew :libgdx-ui-markup-idea:buildPlugin
```

Expected: both commands succeed; the plugin archive contains the preview distribution with both FreeType JARs.

- [x] **Step 4: Run strict supply-chain resolution and the full build**

Run:

```bash
./gradlew resolveAndLockAll --warning-mode=fail
xvfb-run -a ./gradlew build --warning-mode=fail
```

Expected: both commands succeed without modifying locks or verification metadata.

- [x] **Step 5: Inspect the generated screenshot**

Open `build/signin-freetype.png` and verify title, body, field, checkbox, and button text are sharp, uncropped, and visibly ordered by their declared sizes. This visual check supplements but does not replace automated proof.

- [x] **Step 6: Review the complete diff**

Run:

```bash
git diff --check
git status --short
git diff --stat
git diff -- gradle/libs.versions.toml '*/gradle.lockfile' gradle/verification-metadata.xml
rg -n -i "msdf|distance.?field|shader" libgdx-ui-markup/src libgdx-ui-markup-preview/src \
  gradle/libs.versions.toml
```

Expected: no whitespace errors, every change traces to FreeType font sizing, trust metadata is file-exact, and production code contains no MSDF/SDF/shader/backend abstraction.

- [x] **Step 7: Apply the repository code-review checklist**

Check acceptance criteria, GL/render-thread confinement, cache/resource ownership, typed diagnostics, custom-skin behavior, bounds, dependency provenance, public API compatibility, and screenshot/layout regressions. Fix any defect and rerun the narrowest affected test followed by the full gate.

## Completion Criteria

- XML and CSS can declare a font family and integer size, with deterministic precedence and typed bounded failures.
- The default preview and default skin render Inter through `gdx-freetype`, not `new BitmapFont()`.
- Exact-size fonts are cached, shared, and disposed exactly once with the owning skin.
- HiDPI atlases use physical pixels while Scene2D layout stays in logical units.
- Custom skins receive the concrete FreeType manager without accepting font paths from markup.
- The preview success/error paths, harness MCP E2E, qualification, runtime tests, IDEA plugin build, strict dependency resolution, Javadocs, and full build all pass.
- Inter and FreeType dependencies are present in distributable artifacts with reviewed licenses, hashes, locks, and verification metadata.
- No MSDF/SDF renderer, shader hook, backend SPI, or deliberate future seam exists in production code.
