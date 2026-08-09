# Agentic Cookbook Design

## Goal

Add a compact, source-grounded cookbook that lets coding agents select and execute common
`libgdx-ui-markup` workflows without reconstructing the library contract from implementation
details. Make cookbook maintenance part of every public API change.

## Audience and structure

The primary audience is an agent modifying or integrating this repository. The cookbook will be
one progressively disclosed guide at `docs/guides/agentic-cookbook.md`, linked from the README.
Each recipe will state when to use it, show the smallest complete example, name the relevant
thread or ownership constraints, identify source-backed reference implementations, and end with
an exact verification command.

The cookbook will cover:

1. Inspecting and previewing an XML/CSS UI.
2. Parsing markup and CSS without GL access.
3. Building and replacing an actor tree on the render thread.
4. Declaring reliable semantics and using strict harness locators.
5. Selecting authoritative, bindings-only, or widget-mirror runtime values.
6. Exercising the preview through the harness MCP path.
7. Extending the bounded tag registry.
8. Diagnosing typed parser, builder, preview, and runtime-comparison failures.

The guide will point to the existing embedding guide for the full frame-correlation integration
instead of duplicating that longer contract.

## Maintenance contract

`AGENTS.md` will require every change to a public API, markup/CSS dialect, CLI option, semantic
mapping, or integration contract to update affected cookbook recipes in the same change. It will
also require examples to remain compilable or be backed by an existing test/reference
implementation. This is an agent rule, not a runtime enforcement mechanism.

## Scope boundaries

- No production Java behavior or dependency changes.
- No new example application or documentation test framework.
- No repetition of the full embedding guide or protocol specifications.
- Examples use the current Java 25, libGDX 1.14.2, markup 0.4.1, harness 1.2.0, and agent-runtime
  2.0.0 contracts documented by this checkout.

## Acceptance criteria

- `docs/guides/agentic-cookbook.md` contains all eight recipes above and links to canonical source,
  tests, or deeper documentation.
- Every command is runnable from the repository root and uses the Gradle Wrapper.
- Render-thread-only operations and GL-free operations are explicitly distinguished.
- Runtime recipes distinguish authoritative, bindings-only, and widget-mirror modes without
  presenting widget readback as domain truth.
- Locator guidance preserves distinct zero-match and multiple-match failures.
- `README.md` links to the cookbook.
- `AGENTS.md` contains the API/dialect/integration maintenance rule.
- A repository link scan finds no broken relative Markdown links in changed files.
- `git diff --check` passes.

## Verification

Run from the isolated worktree root:

```bash
git diff --check
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
rg -n "MarkupBuilder\.build|registerAuthoritative|registerBindings|registerWidgetMirror|StrictResolution" \
  docs/guides/agentic-cookbook.md
```

Because this change is documentation-only, Java test suites are not required unless inspection
finds that an example needs a new compilable fixture or a production change.
