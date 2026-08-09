# libgdx-ui-markup — Agent Instructions

## Mission

Build a standalone Java library that compiles HTML-like XML + a bounded CSS subset into
libGDX Scene2D/Scene2D.UI UIs with **semantics by construction**. Markup authors declare
`testId`/`role`/`accessibleName` at the source, so automation locators (libgdx-ui-harness
and similar) stop depending on inference. Optimize for semantic reliability, actionable
diagnostics, and reproducibility before feature count.

## Scope and invariants

- V1 targets LWJGL3 desktop and Scene2D/Scene2D.UI.
- The architecture is layered: GL-free parse (XML + CSS → immutable model), render-thread
  builder (model → actor tree), transport-neutral diagnostics, an optional harness adapter,
  a hot-reloading preview app, and a thin IntelliJ plugin.
- No `Actor`, `Stage`, libGDX collection, or backend type may cross the GL-free model
  boundary into the parse result.
- All Stage/Actor reads and mutations run on the libGDX render thread; `MarkupBuilder.build`
  MUST be called from the render thread.
- Layout rides libGDX's own Table/Cell engine via XML attributes (`expand`, `fill`, `align`,
  `colspan`, `pad`, `space`). There is no CSS layout engine and no full HTML dialect.
- CSS is a bounded styling subset compiled into a libGDX Skin (or applied per-actor);
  unknown properties and unparseable selectors are typed errors.
- Public protocol data is immutable, versioned, bounded, and serializable.
- Bounded limits at trust boundaries: document size, element count, tree depth, attribute
  and text length, result counts, screenshots, trace size, request duration.
- Preserve strict locator errors: zero and multiple matches are distinct failures.
- Avoid preview and incubator Java APIs even though the project targets Java 25.

## Required workflow skills

Read and follow applicable installed skills before acting:

- `using-superpowers` at conversation start.
- `brainstorming` before changing behavior or architecture.
- `writing-plans` before multi-step implementation.
- `test-driven-development` for every feature and bug fix.
- `systematic-debugging` for failures or unexpected behavior.
- `karpathy-guidelines` for surgical, assumption-aware changes.
- `verification-before-completion` before any completion claim.
- `requesting-code-review` after significant implementation.
- `finishing-a-development-branch` when a branch is ready to integrate.
- `game-development` when changes depend on game-loop, input, or rendering semantics.

## Engineering rules

- Use Gradle Wrapper; do not rely on a machine-installed Gradle.
- Compile and test with JDK 25. Treat warnings as failures in project code.
- Follow red-green-refactor. A production behavior change starts with a failing behavioral test.
- Prefer records, sealed types, and explicit value objects for protocol models; keep hot
  render-loop paths allocation-aware.
- No sleeps for synchronization. Wait on observable state with a monotonic deadline.
- No global mutable singleton harness. Lifecycle and ownership must be explicit.
- Error responses must retain element path, line/column, and message; diagnostics are typed
  (`MarkupException.Kind`), never bare exceptions.
- Never expose secrets, arbitrary filesystem reads, arbitrary reflection, or unrestricted
  method invocation through the MCP surface.

## Verification expectations

Run the narrowest proof first, then the affected suite:

1. GL-free model/parser/CSS unit tests.
2. Scene2D render-thread builder tests (under `xvfb-run` like the harness CI).
3. Preview smoke: `--frames --screenshot --exit` success and error paths.
4. Harness E2E: a markup-declared UI driven through the harness MCP (query/action/wait/screenshot).
5. IDEA plugin build (`buildPlugin`).

A change is not complete because it compiles. Exercise the changed path and record the
exact command and result.

## Documentation

- Architecture decisions with lasting consequences require an ADR under `docs/adr/`.
- Specifications and plans must contain measurable acceptance criteria and exact
  verification commands.
- Keep public API examples compilable.
- Any change to a public API, markup or CSS dialect, preview CLI, semantic mapping, or integration
  contract MUST update every affected recipe in `docs/guides/agentic-cookbook.md` in the same
  change. Keep cookbook examples compilable or backed by an existing executable test/reference.
