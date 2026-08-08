# Core Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issues #8, #11, #17, #18, #19, #20, #21, #23, and #25 with shared path and CSS semantics.

**Architecture:** Keep path tracking and CSS parsing GL-free. Use immutable resolved styles and one per-build cache; apply libGDX style mutations only on the render thread. Make every invalid markup/CSS path a located typed diagnostic.

**Tech Stack:** Java 25, libGDX Scene2D, JUnit 5, Gradle Wrapper, Xvfb.

## Global Constraints

- No libGDX type crosses into parse results.
- `MarkupBuilder` and resource tests run on `GdxTestHost` under Xvfb.
- Production behavior changes begin with a failing behavioral test.
- Public diagnostics retain kind, element path, and one-based line/column.
- No compatibility shim beyond the approved additive 0.3.0 contract.

---

### Task 1: Parent-Scoped Element Paths (#23)

**Files:**
- Create: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/ElementPathTracker.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Produces: `public final class ElementPathTracker` with `String enter(String tag)`, `String current()`, and `void exit()`.
- Consumes: tag names already normalized by parser/builder.
- Later runtime plan consumes the public GL-free tracker.

- [ ] **Step 1: Add failing nested-sibling tests**

Add parser and builder cases with two parent tables, each containing repeated buttons. Assert `ui/table/button`, `ui/table/button[1]`, `ui/table[1]/button`, and `ui/table[1]/button[1]`. Add an invalid color/float on the second branch and assert its error path does not double-enter the current element.

- [ ] **Step 2: Run tests red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupParserTest' --tests '*MarkupBuilderTest' --warning-mode=fail`

Expected: second-parent and current-element path assertions fail because counters are document-global and builder error helpers increment twice.

- [ ] **Step 3: Implement the tracker and migrate callers**

Use a frame stack where each frame owns `Map<String,Integer> childCounts`. `enter` increments only the current frame's count, appends the bare tag for index zero or `tag[index]` otherwise, and pushes a fresh child-count frame. `current` returns the top path without mutation. `exit` rejects underflow. Replace parser/builder global maps and every builder error helper's `pathOf(element.tag())` with `current()`.

- [ ] **Step 4: Run focused tests green**

Run the Step 2 command. Expected: all parser and builder tests pass.

- [ ] **Step 5: Commit**

Commit message: `fix: scope generated element paths per parent`

### Task 2: Located Widget Range Validation and Layout Booleans (#8, #19)

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/BuiltinTagFactories.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Consumes: `BuildContext.elementPath()`, `line()`, `column()`, and `floatAttr`.
- Produces: no new public API; typed `MarkupException.Kind.INVALID_VALUE` failures.

- [ ] **Step 1: Add failing behavioral tests**

Add slider cases `min=2 max=1` and `step=0`, plus progress cases `min=2 max=1`. Assert `INVALID_VALUE`, element path, line, column, and a message naming the conflicting fields. Add table-cell cases for `expand=false`, `fill=false`, and `grow=false`; assert neither axis is enabled while `true`, `x`, and `y` preserve current behavior.

- [ ] **Step 2: Run tests red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupBuilderTest' --warning-mode=fail`

Expected: invalid constructors escape as libGDX exceptions and `false` enables both axes.

- [ ] **Step 3: Implement minimal validation and explicit switches**

Before constructing widgets, throw:
```java
throw new MarkupException(MarkupException.Kind.INVALID_VALUE,
    context.elementPath(), context.line(), context.column(),
    "slider requires min <= max and step > 0");
```
Use the corresponding progress message without `step`. Add `case "false" -> { }` to all three boolean-or-axis switches; retain `x`, `y`, and default validated `true` behavior.

- [ ] **Step 4: Run tests green and commit**

Run the Step 2 command. Commit message: `fix: validate ranges and false layout axes`

### Task 3: CSS Coordinates and Work Bounds (#11, #21)

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssRule.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssStyleResolver.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`

**Interfaces:**
- Produces: true one-based `CssRule.line()`/`column()` while preserving zero-based `ruleIndex()` only for source order.
- Produces bounded constants for selector length, selectors per group, total selectors, and comparisons per resolve/build.

- [ ] **Step 1: Add failing coordinate and bound tests**

Cover multiline comments, blank lines, a malformed selector, unknown property, malformed value, overlong selector, excessive comma group, and a stylesheet that stays below byte/rule caps but exceeds the new total-selector/work cap. Assert exact one-based line/column and `TOO_LARGE` for every bound.

- [ ] **Step 2: Run GL-free tests red**

Run:
`./gradlew :libgdx-ui-markup:test --tests '*CssTest' --warning-mode=fail`

Expected: coordinates reflect rule index/column zero and selector/work cases are accepted.

- [ ] **Step 3: Implement a position-preserving bounded scan**

Scan the original source once, advancing line/column through comments and whitespace. Record selector/property token starts. Enforce limits before allocating split collections. Keep rule index independent. In the resolver, count selector comparisons and fail with a located `TOO_LARGE` diagnostic when the configured maximum is exceeded. In `MarkupBuilder.Walk`, cache `ResolvedStyle` by element identity plus pseudo for the duration of one build.

- [ ] **Step 4: Run CSS and builder tests green**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*CssTest' --tests '*MarkupBuilderTest' --warning-mode=fail`

- [ ] **Step 5: Commit**

Commit message: `fix: bound CSS work and preserve coordinates`

### Task 4: Group Specificity and Pseudo-State Styles (#17, #18, #20)

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssStyleResolver.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/SkinStyleCompiler.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Produces: maximum specificity over all matching selectors in one rule.
- Produces: one internal pseudo-to-style-field mapping shared by tag/class/id application.

- [ ] **Step 1: Add failing cascade and render tests**

Add `button,#save` against a competing class rule and assert ID specificity wins. Add `checkbox:hover`, `.warning:hover`, and `#save:disabled` font colors and assert only the corresponding state field changes. Add an unsupported widget/property/pseudo combination and assert a located typed CSS error rather than a silent no-op.

- [ ] **Step 2: Run tests red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*CssTest' --tests '*MarkupBuilderTest' --warning-mode=fail`

- [ ] **Step 3: Implement maximum matching specificity**

Replace first-match break logic with a maximum across matching selectors. Preserve source-order tie breaking between rules.

- [ ] **Step 4: Implement state-specific actor styles**

Map supported pseudos to libGDX style fields, clone the actor's style before class/ID-specific mutation, and assign the derived style only to that actor. Use selector coordinates in unsupported-combination errors. Never mutate a shared Skin style for tagless selectors.

- [ ] **Step 5: Run tests green and commit**

Run the Step 2 command. Commit message: `fix: compile pseudo styles with correct specificity`

### Task 5: DefaultSkin Pixmap Ownership (#25)

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/DefaultSkin.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupBuilderTest.java`

**Interfaces:**
- Produces: unchanged `DefaultSkin.create()` API with exact Pixmap/Texture ownership.

- [ ] **Step 1: Add a failing repeated-create ownership test**

Add a package-visible injection seam for pixel/texture factories only if required to observe disposal. Test successful upload disposes Pixmap once and Skin disposal owns Texture; test texture-construction failure disposes Pixmap and any created texture exactly once.

- [ ] **Step 2: Run render test red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupBuilderTest' --warning-mode=fail`

- [ ] **Step 3: Implement failure-safe ownership**

Wrap `new Texture(pixel)` in `try/finally { pixel.dispose(); }`; add the texture to Skin only after construction. Do not retain Pixmap in Skin.

- [ ] **Step 4: Run module gate and commit**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:check --warning-mode=fail`

Commit message: `fix: dispose default skin upload pixmap`

### Task 6: Core PR Verification

**Files:** all files changed above.

- [ ] **Step 1: Run affected module checks**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup:check :libgdx-ui-markup:javadoc --warning-mode=fail`

- [ ] **Step 2: Review issue contracts**

Confirm every acceptance criterion from #8, #11, #17, #18, #19, #20, #21, #23, and #25 maps to a passing behavioral test and no exported symbol was changed without all LSP references migrated.

- [ ] **Step 3: Create PR**

Push an issue-only branch and create a ready PR with `Fixes #8`, `#11`, `#17`, `#18`, `#19`, `#20`, `#21`, `#23`, and `#25`, exact commands/results, root causes, and compatibility impact.