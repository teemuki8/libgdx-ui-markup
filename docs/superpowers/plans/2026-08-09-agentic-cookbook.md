# Agentic Cookbook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a source-grounded agent cookbook and require it to stay synchronized with public API and integration changes.

**Architecture:** Keep the cookbook in one progressive guide so an agent can load one recipe at a time, while linking to canonical source, tests, and the deeper embedding guide. Add only a README entry point and an explicit contributor rule; do not introduce production code or a documentation framework.

**Tech Stack:** Markdown, Java 25 examples, Gradle Wrapper commands, libGDX 1.14.2, libgdx-ui-markup 0.4.1, libgdx-ui-harness 1.2.0, libgdx-agent-runtime 2.0.0.

## Global Constraints

- Work only in the `docs/agentic-cookbook` isolated worktree.
- Do not change production Java behavior or dependencies.
- Every command must be runnable from the repository root and use `./gradlew`.
- Explicitly separate GL-free parse work from render-thread Actor, Stage, Skin, and runtime work.
- Preserve strict zero-match versus multiple-match locator failures.
- Never describe widget-mirror values as authoritative domain truth.
- Keep examples compilable or point to the exact existing compilable test/reference implementation.

---

### Task 1: Add the source-grounded cookbook

**Files:**

- Create: `docs/guides/agentic-cookbook.md`
- Reference: `README.md`
- Reference: `docs/guides/embedding.md`
- Reference: `samples/signin.xml`
- Reference: `samples/signin.css`
- Reference: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupBuilder.java`
- Reference: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupRegistry.java`
- Reference: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java`

**Interfaces:**

- Consumes: `MarkupParser.parse(String)`, `CssParser.parse(String)`, `DefaultSkin.create()`, `MarkupBuilder.build(MarkupDocument, CssDocument, Skin, SemanticSink)`, `MarkupBuilder.build(..., MarkupRegistry)`, `HarnessSemanticSink`, and the three `MarkupRuntimeSource` registration modes.
- Produces: A stable recipe index for agents and maintainers; no Java API.

- [ ] **Step 1: Establish recipe format and invariant summary**

Create the guide with a purpose paragraph, an agent decision table, and a short invariant block containing these facts:

```markdown
- Parse XML and CSS off the GL thread; the resulting model is immutable and GL-free.
- Create or mutate `Skin`, `Actor`, and `Stage` state only on the render thread.
- Call `MarkupBuilder.build(...)` only on the render thread.
- Treat unknown tags, attributes, CSS properties, and selectors as typed failures.
- Use markup-declared `id`/`role`/`name` semantics and strict locator resolution.
- Use authoritative runtime values when validating displayed domain state.
```

The decision table must route preview work, parse-only validation, application embedding,
harness automation, runtime comparison, custom tags, and failure diagnosis to a named recipe.

- [ ] **Step 2: Add preview, parse, and render-thread build recipes**

Use `samples/signin.xml` and `samples/signin.css`. Include the interactive and bounded CI preview
commands already documented in the README. Show GL-free parsing separately:

```java
MarkupDocument document = new MarkupParser().parse(xml);
CssDocument css = new CssParser().parse(stylesheet);
```

Then show the render-thread build and replacement lifecycle:

```java
Skin skin = DefaultSkin.create();
BuiltUi built = MarkupBuilder.build(document, css, skin, new NoopSink());
built.root().setSize(stage.getViewport().getWorldWidth(),
        stage.getViewport().getWorldHeight());
stage.addActor(built.root());

// On rebuild, still on the render thread:
built.root().remove();
built = MarkupBuilder.build(nextDocument, nextCss, skin, new NoopSink());
```

State that the application owns and disposes the Skin, and point production integration readers
to `docs/guides/embedding.md`.

- [ ] **Step 3: Add semantics, strict locator, and runtime-mode recipes**

Show a minimal markup fragment declaring stable semantics:

```xml
<button id="save" role="button" name="Save" text="Save"/>
```

Explain that strict resolution must retain distinct zero-match and multiple-match failures. Point
to the harness end-to-end test for executable query/action/wait/screenshot examples instead of
inventing a second client API.

Show the exact runtime registration signatures:

```java
MarkupRuntimeSource source = MarkupRuntimeSource.registerAuthoritative(
        runtime, document, built, APP_SESSION_ID,
        (entityId, propertyId, actor) ->
                () -> RuntimeValues.string(domainValue(entityId)));

MarkupRuntimeSource bindings = MarkupRuntimeSource.registerBindings(
        runtime, document, built, APP_SESSION_ID);

MarkupRuntimeSource mirror = MarkupRuntimeSource.registerWidgetMirror(
        runtime, document, built, APP_SESSION_ID);
```

Describe authoritative as the default for correctness assertions, bindings-only for entities
registered elsewhere, and widget-mirror as preview/correlation plumbing only. Require closing the
old source before replacing a UI. Link the frame-correlation and status details to the embedding
guide.

- [ ] **Step 4: Add MCP, custom-tag, and diagnosis recipes**

For MCP, use the preview `--mcp` mode and direct readers to
`MarkupHarnessEndToEndTest`/`MarkupMcpClient` for the executable sequence: query, action, wait,
runtime compare, screenshot. Do not promise a shell MCP client that the repository does not ship.

For custom tags, show both parser allow-list and builder registry setup:

```java
Set<String> customTags = Set.of("inventory-slot");
MarkupDocument document = new MarkupParser(customTags).parse(xml);

MarkupRegistry registry = MarkupRegistry.defaultRegistry();
registry.register("inventory-slot", (element, context) -> new Table(context.skin()));
BuiltUi built = MarkupBuilder.build(document, css, skin, sink, registry);
```

Warn that the factory runs during render-thread build, must not touch the Stage or run input, and
that custom attributes remain constrained by the parser vocabulary.

Add a failure table mapping parser/build `MarkupException.Kind`, preview exit 2/status output,
`MISSING`, `UNAVAILABLE`, `STALE`, `MISMATCH`, and `AMBIGUOUS` to the next concrete check.

- [ ] **Step 5: Add exact verification commands and source map**

End each recipe with its narrowest existing verification command. End the guide with the affected
suite commands and a source map to the exact implementation/test files. Commands must include:

```bash
./gradlew :libgdx-ui-markup:test
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test
```

- [ ] **Step 6: Check the cookbook against current source**

Run:

```bash
rg -n "MarkupBuilder\.build|registerAuthoritative|registerBindings|registerWidgetMirror|StrictResolution" \
  docs/guides/agentic-cookbook.md \
  libgdx-ui-markup/src/main \
  libgdx-ui-markup-runtime/src/main \
  libgdx-ui-markup-harness/src/test
```

Expected: every named API in the cookbook has a matching current declaration or executable test
reference, and all three runtime modes appear distinctly.

- [ ] **Step 7: Commit the cookbook**

```bash
git add docs/guides/agentic-cookbook.md
git commit -m "docs: add agentic usage cookbook"
```

### Task 2: Add discovery and maintenance rules

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`

**Interfaces:**

- Consumes: `docs/guides/agentic-cookbook.md` from Task 1.
- Produces: A discoverable README link and a repository-level synchronization rule for future agents.

- [ ] **Step 1: Link the cookbook from the README**

Add a short “Agent cookbook” entry near Quick start or the existing production-embedding link:

```markdown
For task-oriented agent recipes—preview, parse/build, semantics, runtime values, harness MCP,
custom tags, and diagnosis—see [the agentic cookbook](docs/guides/agentic-cookbook.md).
```

- [ ] **Step 2: Add the cookbook synchronization rule to AGENTS.md**

Extend `## Documentation` with this enforceable review rule:

```markdown
- Any change to a public API, markup or CSS dialect, preview CLI, semantic mapping, or integration
  contract MUST update every affected recipe in `docs/guides/agentic-cookbook.md` in the same
  change. Keep cookbook examples compilable or backed by an existing executable test/reference.
```

- [ ] **Step 3: Verify relative links**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
import re

for source in (Path("README.md"), Path("docs/guides/agentic-cookbook.md")):
    text = source.read_text()
    for target in re.findall(r"\[[^]]+\]\(([^)#]+)(?:#[^)]+)?\)", text):
        if "://" in target:
            continue
        resolved = (source.parent / target).resolve()
        assert resolved.exists(), f"broken link in {source}: {target}"
PY
```

Expected: exit 0 with no output.

- [ ] **Step 4: Verify the complete documentation change**

Run:

```bash
git diff --check main...HEAD
rg -n "agentic cookbook|public API|markup or CSS dialect|preview CLI|semantic mapping|integration contract" \
  README.md AGENTS.md docs/guides/agentic-cookbook.md
git status --short
```

Expected: no whitespace errors; the cookbook is linked; the maintenance rule covers each public
contract category; only intended documentation files are changed or committed.

- [ ] **Step 5: Commit discovery and maintenance rules**

```bash
git add README.md AGENTS.md
git commit -m "docs: keep cookbook aligned with public API"
```
