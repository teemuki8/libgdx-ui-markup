# GDXCSS Language Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish `.gdxcss` as the canonical stylesheet identity and add responsive sizing plus the bounded, useful CSS-to-Scene2D conversions specified in the GDXCSS design.

**Architecture:** Preserve the GL-free parser/resolver and render-thread builder boundary. Parse reusable typed scalar values in `dev.gdx.markup.core.style`, carry resolved declarations immutably, and create libGDX `Value`, Drawable, widget, Cell, and Actor mutations only inside `MarkupBuilder`/`SkinStyleCompiler`. Deliver four sequential pull requests from reconciled `main`, reviewing and merging each exact CI-green head before starting the next.

**Tech Stack:** Java 25, Kotlin/JBR 21 for the IDEA plugin, Gradle Wrapper 9.6.1, libGDX 1.14.2 Scene2D, JUnit 5, IntelliJ Platform 2024.3, LWJGL3/Xvfb.

## Global Constraints

- The canonical stylesheet extension is `.gdxcss`; `.css` remains accepted and watched.
- No Actor, Stage, libGDX collection, Drawable, or backend type enters the GL-free parse model.
- All Actor/Stage reads and mutations, Scene2D `Value` construction, and drawable tinting run on the render thread.
- Unknown or incompatible syntax fails with located typed diagnostics; never silently ignore it.
- Every parser and resolver operation retains existing byte/rule/declaration/selector/work limits and adds the bounds in the design.
- Table/Cell remains the only layout engine; do not add flexbox, grid, DOM, Yoga, or browser dependencies.
- Production behavior changes begin with a failing behavioral test and end with focused plus affected-suite verification.
- Every public dialect change updates `docs/guides/agentic-cookbook.md` in the same pull request.
- Use only the Gradle Wrapper and JDK 25 for Java modules; warnings in project Java code remain errors.
- Do not use Java preview or incubator APIs.

---

## Pull Request 1 — GDXCSS identity and migration

### Task 1: Preview extension compatibility

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/CliOptions.java`
- Modify: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewTestProcess.java`
- Modify: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`
- Modify: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/CliOptionsTest.java`

**Interfaces:**
- Consumes: `CliOptions.css(): Path` and the existing preview watcher.
- Produces: `PreviewApp.isWatchedSource(Path): boolean`, recognizing `.xml`, `.gdxcss`, and `.css`; unchanged `--css` CLI option accepting either extension.

- [ ] **Step 1: Write failing watcher and CLI tests**

Add pure assertions equivalent to:

```java
assertTrue(PreviewApp.isWatchedSource(Path.of("screen.gdxcss")));
assertTrue(PreviewApp.isWatchedSource(Path.of("screen.css")));
assertTrue(PreviewApp.isWatchedSource(Path.of("screen.xml")));
assertFalse(PreviewApp.isWatchedSource(Path.of("screen.scss")));
assertEquals(Path.of("samples/signin.gdxcss"), CliOptions.parse(new String[] {
        "--ui", "samples/signin.xml", "--css", "samples/signin.gdxcss"
}).css());
```

- [ ] **Step 2: Run the focused tests and observe the missing helper/failing extension behavior**

Run:

```text
./gradlew :libgdx-ui-markup-preview:test --tests dev.gdx.markup.preview.CliOptionsTest --tests dev.gdx.markup.preview.PreviewAppTest
```

Expected: FAIL because `.gdxcss` is not recognized by the watcher helper contract.

- [ ] **Step 3: Centralize watched-source detection**

Add a package-private helper:

```java
static boolean isWatchedSource(Path name) {
    String value = name.toString();
    return value.endsWith(".xml") || value.endsWith(".gdxcss") || value.endsWith(".css");
}
```

Use it from the watcher and update CLI usage text to `<file.gdxcss>` while retaining the option name `--css`.

- [ ] **Step 4: Run focused preview tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add libgdx-ui-markup-preview
git commit -m "feat: recognize GDXCSS preview sources"
```

### Task 2: IDEA sibling resolution and file identity

**Files:**
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/GdxCssLanguage.kt`
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/GdxCssFileType.kt`
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/GdxCssLexer.kt`
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/GdxCssSyntaxHighlighter.kt`
- Modify: `libgdx-ui-markup-idea/src/main/resources/META-INF/plugin.xml`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/PreviewProcessLauncher.kt`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupPreviewPanel.kt`
- Modify: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/PreviewProcessLauncherTest.kt`
- Create: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/GdxCssLexerTest.kt`

**Interfaces:**
- Consumes: `PreviewProcessLauncher.siblingCss(Path): Path?` call sites.
- Produces: the same public method, now preferring `.gdxcss`; `GdxCssLanguage`,
  `GdxCssFileType`, and a dependency-free bounded syntax highlighter for selectors, properties,
  values, comments, braces, colons, and semicolons.

- [ ] **Step 1: Write failing sibling-priority tests**

```kotlin
Files.writeString(dir.resolve("signin.css"), "")
Files.writeString(dir.resolve("signin.gdxcss"), "")
assertEquals(dir.resolve("signin.gdxcss"), PreviewProcessLauncher.siblingCss(ui))
Files.delete(dir.resolve("signin.gdxcss"))
assertEquals(dir.resolve("signin.css"), PreviewProcessLauncher.siblingCss(ui))
```

- [ ] **Step 2: Run the pure plugin unit test and observe `.css` winning**

```text
./gradlew :libgdx-ui-markup-idea:unitTest --tests dev.gdx.markup.idea.PreviewProcessLauncherTest
```

Expected: FAIL on the preference assertion.

- [ ] **Step 3: Implement deterministic sibling lookup**

Resolve candidates in this exact order:

```kotlin
val stem = ui.fileName.toString().substringBeforeLast('.')
return sequenceOf("$stem.gdxcss", "$stem.css")
    .map(ui::resolveSibling)
    .firstOrNull(Files::isRegularFile)
```

Update missing-file UI text to name both extensions.

- [ ] **Step 4: Register the dependency-free GDXCSS file type and highlighter**

Implement a non-stateful `LanguageFileType`/`Language` pair named `GDXCSS`, extension `gdxcss`.
Implement a bounded `LexerBase` state machine that recognizes comments, selector text, property
names, values, and delimiters without parsing or validating the dialect. Register the file type
and `lang.syntaxHighlighterFactory` in `plugin.xml`; do not add the unavailable IntelliJ Ultimate
CSS plugin as a dependency. Test representative tokens and lexer termination on malformed input.

- [ ] **Step 5: Verify unit and packaged plugin behavior**

```text
./gradlew :libgdx-ui-markup-idea:unitTest :libgdx-ui-markup-idea:buildPlugin :libgdx-ui-markup-idea:verifyPluginPackaging
```

Expected: PASS and the distribution zip contains the plugin plus preview distribution.

- [ ] **Step 6: Commit**

```text
git add libgdx-ui-markup-idea
git commit -m "feat: prefer GDXCSS in the IDEA preview"
```

### Task 3: Canonical repository migration and cookbook

**Files:**
- Rename: `samples/signin.css` to `samples/signin.gdxcss`
- Rename: `libgdx-ui-markup-preview/src/test/resources/asymmetric-top-bottom.css` to `asymmetric-top-bottom.gdxcss`
- Rename: `libgdx-ui-markup-qualification/corpus/shared.css` to `shared.gdxcss`
- Modify: current source, tests, manifest/configuration, `README.md`, `docs/guides/agentic-cookbook.md`, and `docs/adr/0001-declarative-scene-authoring.md` references found by `rg '\.css'`
- Preserve: historical release notes and completed superpowers plans/specs except the current design/plan

**Interfaces:**
- Consumes: file-path constants and CLI examples.
- Produces: canonical executable `.gdxcss` fixtures while retaining at least one temporary `.css` compatibility fixture in a test.

- [ ] **Step 1: Rename canonical files with `mv` and update executable references**

Use explicit paths, then update only live source/docs. Do not bulk-rewrite historical release evidence.

- [ ] **Step 2: Add a legacy-extension compatibility test**

Create a temporary `legacy.css`, parse it through the preview/core path, and assert successful
build. The test proves extension compatibility is a caller convention, not a parser restriction.

- [ ] **Step 3: Update the cookbook with an identity recipe**

State: “Use `.gdxcss` for new stylesheets; `.css` remains accepted.” Include the canonical
preview command and IDEA sibling preference.

- [ ] **Step 4: Verify all migrated consumers**

```text
./gradlew :libgdx-ui-markup:test :libgdx-ui-markup-preview:test :libgdx-ui-markup-qualification:test :libgdx-ui-markup-idea:unitTest
rg -n "samples/signin\.css|shared\.css|asymmetric-top-bottom\.css" README.md docs/guides libgdx-ui-markup-* samples
git diff --check
```

Expected: Gradle succeeds; `rg` returns no live references; diff check succeeds.

- [ ] **Step 5: Commit**

```text
git add README.md docs/guides docs/adr samples libgdx-ui-markup-preview libgdx-ui-markup-qualification
git commit -m "docs: make GDXCSS the canonical stylesheet"
```

### Task 4: Publish, review, and merge PR 1

**Files:** no source changes unless review or CI finds a defect.

**Interfaces:**
- Consumes: the PR 1 branch commits.
- Produces: merged `.gdxcss` identity on `main` with green exact-head checks.

- [ ] **Step 1: Run PR-level verification**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test :libgdx-ui-markup-qualification:test
./gradlew :libgdx-ui-markup-idea:buildPlugin :libgdx-ui-markup-idea:verifyPluginPackaging
git diff --check origin/main...HEAD
```

- [ ] **Step 2: Push and open a ready PR**

Use title `feat: establish GDXCSS stylesheet identity`; include design, compatibility, commands,
and migration impact in the body.

- [ ] **Step 3: Review the remote PR**

Confirm base/head/file list, inspect the complete patch and comments, and record findings. Do not
self-approve. Fix real findings test-first and push a new head.

- [ ] **Step 4: Wait for required checks on the reviewed SHA and merge**

Merge only the reviewed head SHA with the repository's established merge method. Verify `MERGED`,
fetch `origin/main`, and fast-forward local `main` before starting PR 2.

---

## Pull Request 2 — Responsive lengths and layout properties

### Task 5: Typed bounded lengths and CSS spacing grammar

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssLength.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssSpacing.java`
- Create: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssLengthTest.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/ResolvedStyle.java`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`

**Interfaces:**
- Produces: package-private sealed `CssLength` with `Pixels(float)`, `Percent(float)`, and `Auto`; `CssLength.parse(String, boolean allowAuto)`; immutable `CssSpacing(top,right,bottom,left)`; `ResolvedStyle.lengthValue(String)` and `spacing(String)`.

- [ ] **Step 1: Write failing pure value tests**

Cover `12`, `12px`, `100%`, `0%`, `auto`, decimals, non-finite/negative values, unsupported units,
and CSS 1/2/3/4-value spacing plus legacy comma forms. Assert typed parser failures retain the
property line and column.

- [ ] **Step 2: Run the GL-free tests and observe rejected `%`/`auto` and shorthand values**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssLengthTest --tests dev.gdx.markup.core.style.CssTest
```

- [ ] **Step 3: Implement minimal immutable parsing**

Use anchored patterns and finite range checks. Normalize percent to a ratio only in
`CssLength.Percent`; retain the original property string in public `CssRule.properties()` for
source compatibility.

- [ ] **Step 4: Add property whitelist entries**

Add `max-width`, `max-height`, `gap`, `row-gap`, `column-gap`, `display`, `visibility`,
`overflow`, and `vertical-align` with closed value enums. Reject dimension and `display` in
pseudo-state rules.

- [ ] **Step 5: Run the focused GL-free suite and commit**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssLengthTest --tests dev.gdx.markup.core.style.CssTest
git add libgdx-ui-markup/src/main libgdx-ui-markup/src/test
git commit -m "feat: parse responsive GDXCSS lengths"
```

### Task 6: Responsive Scene2D Cell conversion

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Consumes: `ResolvedStyle.lengthValue(String)`.
- Produces: render-thread conversion from `CssLength` to `Value.Fixed` or
  `Value.percentWidth/percentHeight(percent, containingTable)`.

- [ ] **Step 1: Write a failing resize behavioral test**

Build `<table><label id="wide"/></table>` with `#wide { width: 100%; }`, size/layout the Table at
320 then 640, and assert the label/cell width follows both sizes without rebuilding. Add min/max
percent tests and an unsupported top-level Label percentage diagnostic.

- [ ] **Step 2: Run the focused builder test under Xvfb and observe fixed-value rejection**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupBuilderTest
```

- [ ] **Step 3: Convert cell dimensions on the render thread**

Add axis-aware conversion:

```java
private Value value(CssLength length, Table containingTable, Axis axis) {
    return switch (length) {
        case CssLength.Pixels pixels -> Value.Fixed.valueOf(pixels.value());
        case CssLength.Percent percent -> axis == Axis.X
                ? Value.percentWidth(percent.ratio(), containingTable)
                : Value.percentHeight(percent.ratio(), containingTable);
        case CssLength.Auto ignored -> axis == Axis.X ? Value.prefWidth : Value.prefHeight;
    };
}
```

Apply XML constraints after CSS so existing XML-over-CSS precedence remains true. Do not create
`Value` objects during parsing.

- [ ] **Step 4: Support full-parent top-level Tables**

When a Table has no Cell parent and resolves both width and height to exactly 100%, call
`setFillParent(true)`. Reject other percentage combinations with a located `STYLE_ERROR`.

- [ ] **Step 5: Normalize nested Table padding**

Apply CSS padding internally to Table/Window actors and do not also apply it to their containing
Cell. Continue applying CSS padding to the Cell for non-Table actors. Add a regression test showing
`margin` supplies the external spacing.

- [ ] **Step 6: Run focused tests and commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.MarkupBuilderTest
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java
git commit -m "feat: map relative sizes to Scene2D Values"
```

### Task 7: Gaps, display, visibility, overflow, and vertical alignment

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`
- Modify: `docs/guides/agentic-cookbook.md`
- Modify: `README.md`

**Interfaces:**
- Produces: Table gap defaults, build omission for `display:none`, retained layout for
  `visibility:hidden`, Table clipping, and Label/Cell vertical alignment.

- [ ] **Step 1: Write failing behavioral and incompatible-target tests**

Assert Table cell spaces for all gap variants, absence from the parent actor tree for
`display:none`, invisible-but-present actor for `visibility:hidden`, Table clipping for
`overflow:hidden`, and located failure for `label { overflow: hidden; }`.

- [ ] **Step 2: Run focused tests and observe failures**

Use the Task 6 focused command.

- [ ] **Step 3: Implement base-state conversions**

Resolve `display` before attaching/building parent children; do not emit semantics for omitted
actors. Apply Table defaults before children are added. Normalize `middle` to Scene2D `center`.

- [ ] **Step 4: Add responsive cookbook recipes**

Include an executable Table example with `width: 100%`, resize semantics, supported contexts,
`auto`, max constraints, standard spacing, and exact failure guidance for unsupported units.

- [ ] **Step 5: Verify core and preview smoke, then commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'
git add libgdx-ui-markup README.md docs/guides/agentic-cookbook.md
git commit -m "feat: add bounded GDXCSS layout properties"
```

### Task 8: Publish, review, and merge PR 2

**Files:** no planned source changes; review findings must name their exact test and production
files before remediation.

- [ ] **Step 1: Run PR-level verification**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test :libgdx-ui-markup-preview:test
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test
git diff --check origin/main...HEAD
```

- [ ] **Step 2: Push and open the ready PR**

Use title `feat: add responsive GDXCSS layout`; include percent contexts, compatibility correction,
test commands, and screenshot evidence.

- [ ] **Step 3: Review and remediate**

Review percent evaluation after a second layout size, XML precedence, bounds, typed diagnostics,
and cookbook accuracy. Reproduce every real finding with a failing test, apply the smallest fix,
and push a new head.

- [ ] **Step 4: Merge and reconcile**

Wait for required checks on the reviewed SHA, merge that SHA, verify `MERGED`, fetch
`origin/main`, and fast-forward local `main` before creating PR 3's branch.

---

## Pull Request 3 — Visual, text, image, and Actor properties

### Task 9: Expanded color grammar and background tint

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssColor.java`
- Create: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssColorTest.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/BuildContext.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/SkinStyleCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Produces: GL-free immutable RGBA/named `CssColor`; `background-color`; render-thread
  `Skin.newDrawable(baseName, color)` without allocating a Texture.

- [ ] **Step 1: Write failing color grammar tests**

Cover every accepted form and reject malformed hex, out-of-range channels, unsupported
percent/function syntax, and trailing tokens at exact declaration coordinates.

- [ ] **Step 2: Run focused GL-free tests and observe failures**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssColorTest --tests dev.gdx.markup.core.style.CssTest
```

- [ ] **Step 3: Implement bounded color parsing and builder resolution**

Resolve named values from the Skin only on the render thread. For `background-color`, tint the
resolved `background` drawable or `white`; preserve state-specific drawable mapping and fail
`UNRESOLVED_STYLE` when no tintable base exists.

- [ ] **Step 4: Add render-thread background tests**

Assert per-actor cloned/tinted styles do not mutate shared Skin styles and a custom Skin without
`white` fails at the element location.

- [ ] **Step 5: Verify and commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test
git add libgdx-ui-markup/src/main libgdx-ui-markup/src/test
git commit -m "feat: add web-shaped GDXCSS colors"
```

### Task 10: Text and image properties

**Files:**
- Modify: `CssParser.java`, `MarkupBuilder.java`, `SkinStyleCompiler.java`
- Modify: `CssTest.java`, `MarkupBuilderTest.java`

**Interfaces:**
- Produces: normalized `font-family`, `white-space`, `text-overflow`, `object-fit`, and
  `object-position` conversions.

- [ ] **Step 1: Write failing parser and actor tests**

Assert alias source order between `font` and `font-family`, Label wrap/ellipsis/alignment,
Image Scaling/Align mappings, and located incompatible-target failures.

- [ ] **Step 2: Run focused tests and observe failures**

Run `xvfb-run -a ./gradlew :libgdx-ui-markup:test` with test filters for `CssTest` and
`MarkupBuilderTest`.

- [ ] **Step 3: Normalize aliases before the cascade**

Canonicalize `font-family` to `font` while retaining declaration source order. Do not keep both
keys in one resolved style.

- [ ] **Step 4: Apply widget-specific properties**

Map `normal` to Label wrap, `nowrap` to no wrap, `ellipsis` to `setEllipsis(true)`, `clip` to
`setEllipsis(false)`, and the object properties to `Image#setScaling`/`setAlign`.

- [ ] **Step 5: Verify and commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test
git add libgdx-ui-markup/src/main libgdx-ui-markup/src/test
git commit -m "feat: add GDXCSS text and image properties"
```

### Task 11: Actor input, opacity, and bounded transforms

**Files:**
- Modify: `CssParser.java`, `MarkupBuilder.java`
- Modify: `CssTest.java`, `MarkupBuilderTest.java`
- Modify: `docs/guides/agentic-cookbook.md`, `README.md`

**Interfaces:**
- Produces: `opacity`, `pointer-events`, `scale`, `rotate`, `transform-origin`, and standard
  `visibility` conversions.

- [ ] **Step 1: Write failing closed-grammar tests**

Cover opacity endpoints/range, pointer values, one/two scale values, positive finite scales,
required `deg`, negative rotation, transform-origin keyword pairs, and rejection in pseudo rules
where Actor state cannot hold separate values.

- [ ] **Step 2: Write failing render-thread Actor assertions**

Assert alpha changes without replacing RGB, touchability, independent scale axes, rotation, and
origin coordinates after the Actor has a known size.

- [ ] **Step 3: Implement conversions and incompatibility checks**

Apply base Actor properties after fixed size/style setup. Never alter Table layout constraints to
simulate transforms.

- [ ] **Step 4: Update cookbook property table and recipes**

Include exact supported values, target restrictions, background tint prerequisites, and
intentional exclusions.

- [ ] **Step 5: Verify and commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test :libgdx-ui-markup-preview:test
git add libgdx-ui-markup README.md docs/guides/agentic-cookbook.md
git commit -m "feat: style common Scene2D actor properties"
```

### Task 12: Publish, review, and merge PR 3

**Files:** no planned source changes; review findings must name their exact test and production
files before remediation.

- [ ] **Step 1: Run PR-level verification**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test :libgdx-ui-markup-preview:test :libgdx-ui-markup-harness:test
git diff --check origin/main...HEAD
```

- [ ] **Step 2: Push and open the ready PR**

Use title `feat: expand common GDXCSS styling`; include the property matrix, resource ownership,
target restrictions, and exact local commands.

- [ ] **Step 3: Review and remediate**

Review shared-style isolation, incompatible-target diagnostics, transform/layout separation,
screenshots, and the exact remote patch. Reproduce real findings test-first and push fixes.

- [ ] **Step 4: Merge and reconcile**

Wait for checks on the reviewed SHA, merge it, verify `MERGED`, fetch `origin/main`, and
fast-forward local `main` before PR 4.

---

## Pull Request 4 — Selectors and design tokens

### Task 13: Bounded compound and structural selector AST

**Files:**
- Replace internals of: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/Selector.java`
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/SelectorPart.java`
- Modify: `CssParser.java`, `CssTest.java`

**Interfaces:**
- Produces: immutable Selector containing at most eight right-to-left parts with combinator
  `SELF`, `DESCENDANT`, or `CHILD`; source-compatible `tag()`, `id()`, `className()`, and
  `pseudo()` accessors describing the rightmost part for current consumers.

- [ ] **Step 1: Write failing selector matrix tests**

Cover `*`, multiple classes, `tag#id`, descendant, child, whitespace/comments, rightmost-only
pseudo, specificity, over-eight parts, and every explicitly unsupported combinator/function.

- [ ] **Step 2: Run GL-free tests and observe parse failures**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssTest
```

- [ ] **Step 3: Implement the immutable AST and one-pass bounded parser**

Reject over-limit structures before allocating unbounded split collections. Normalize `:active`
to `pressed`; retain `focus` distinctly.

- [ ] **Step 4: Run parser tests and commit**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssTest
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style
git commit -m "feat: parse bounded structural selectors"
```

### Task 14: Ancestry-aware resolution and focus styles

**Files:**
- Modify: `CssStyleResolver.java`, `MarkupBuilder.java`, `SkinStyleCompiler.java`
- Modify: `CssTest.java`, `MarkupBuilderTest.java`

**Interfaces:**
- Produces: `CssStyleResolver.resolve(Element, List<Element> ancestors, String pseudo, String path)`;
  existing overloads delegate with an empty ancestor list.

- [ ] **Step 1: Write failing ancestry and work-bound tests**

Assert direct child versus descendant matches, right-to-left backtracking, maximum matching group
specificity, empty-ancestry public behavior, and comparison-limit failure counting each attempted
part.

- [ ] **Step 2: Run GL-free tests and observe failures**

Use the Task 13 command.

- [ ] **Step 3: Thread immutable ancestry through `MarkupBuilder.Walk`**

Push the current Element before children and pop in `finally`; pass `List.copyOf(ancestors)` to
the resolver. Keep element-path ownership independent.

- [ ] **Step 4: Map `:focus` only to TextField focused fields**

Support `background` through `TextFieldStyle.focusedBackground` and `color`/`font-color` through
`TextFieldStyle.focusedFontColor`. Reject every other widget/property `:focus` combination at
the selector declaration.

- [ ] **Step 5: Verify and commit**

```text
xvfb-run -a ./gradlew :libgdx-ui-markup:test
git add libgdx-ui-markup/src/main libgdx-ui-markup/src/test
git commit -m "feat: resolve GDXCSS structural selectors"
```

### Task 15: Bounded root variables

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssVariables.java`
- Modify: `CssParser.java`, `CssDocument.java`, `CssTest.java`

**Interfaces:**
- Produces: immutable `CssDocument.variables(): Map<String,String>` while preserving the existing
  two-argument construction path through an overload/factory; complete-value `var(--name)`
  substitution capped at 256 variables and depth 16.

- [ ] **Step 1: Write failing variable tests**

Cover the documented example, forward references, a 16-deep success, 17-deep failure, direct and
indirect cycles, missing names, duplicate/root rules, excess count, invalid names, fallback and
mixed-token rejection, and post-substitution property validation coordinates.

- [ ] **Step 2: Run GL-free tests and observe `:root` rejection**

Use the Task 13 command.

- [ ] **Step 3: Implement pre-validation substitution**

Parse exactly one `:root` declaration block separately from actor rules. Resolve through a local
DFS with visiting/resolved states and compare-before-increment depth/count bounds. Preserve the
using declaration's line/column for failures.

- [ ] **Step 4: Verify and commit**

```text
./gradlew :libgdx-ui-markup:test --tests dev.gdx.markup.core.style.CssTest
git add libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style
git commit -m "feat: add bounded GDXCSS design tokens"
```

### Task 16: Final cookbook, compatibility, and ecosystem verification

**Files:**
- Modify: `docs/guides/agentic-cookbook.md`
- Modify: `README.md`
- Modify: `docs/adr/0001-declarative-scene-authoring.md`
- Modify: `samples/signin.gdxcss`
- Create: `libgdx-ui-markup/src/test/resources/gdxcss-cookbook.xml`
- Create: `libgdx-ui-markup/src/test/resources/gdxcss-cookbook.gdxcss`
- Modify: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Produces: one authoritative support matrix matching `CssParser`, executable recipes for relative
  sizing, common properties, structural selectors, and variables.

- [ ] **Step 1: Add the support matrix and migration guidance**

List every accepted selector, pseudo, property, unit, target restriction, bound, and intentional
non-goal. Explain `.css` compatibility and nested Table padding migration.

- [ ] **Step 2: Back cookbook snippets with parser/builder fixtures**

Add or reuse executable source strings so documentation examples cannot drift from the grammar.

- [ ] **Step 3: Run the complete local verification ladder**

```text
./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.gdxcss --frames 5 --screenshot build/signin.png --exit'
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test
./gradlew :libgdx-ui-markup-idea:buildPlugin
xvfb-run -a ./gradlew build
git diff --check
```

Expected: every command exits zero; preview writes a non-empty PNG; no test is skipped because of
an implementation failure.

- [ ] **Step 4: Commit documentation and final fixtures**

```text
git add README.md docs samples libgdx-ui-markup*/src/test
git commit -m "docs: publish the GDXCSS language contract"
```

### Task 17: Publish, review, merge, and reconcile PR 4

Open `feat: complete the bounded GDXCSS language`, review every design acceptance criterion
against the remote patch, remediate findings test-first, and wait for required checks on the exact
reviewed head. Merge using the expected head SHA, verify the PR is `MERGED`, fetch and fast-forward
local `main`, and delete only the local feature branch if repository convention permits.

Run a final requirement-by-requirement audit against
`docs/superpowers/specs/2026-08-09-gdxcss-language-expansion-design.md`. The objective is complete
only if all four PRs are merged, current CI evidence is green, responsive resizing is directly
exercised, public docs match the parser/builder, and local `main` is clean and synchronized.
