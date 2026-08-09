# ADR 0001 — Declarative Scene2D authoring: HTML-like XML + CSS styling, semantics by construction

Status: accepted · Date: 2026-08-06

## Context

libgdx-ui-harness gives agents deterministic locators (`role`, `accessibleName`, `testId`) but
they are derived by inference or imperative `Semantics` calls scattered through application
code. We want a declarative authoring format whose semantics are exact **by construction**:
the same source that declares the UI also declares its automation contract. Prior art
(source-verified): gdx-lml proved a tag-to-provider registry with per-tag attribute defaults and
is archived (crashinvaders fork is maintenance-mode); its lesson is that layout must ride
libGDX's own Table/Cell engine — every attempt at a CSS layout engine for Scene2D died. Skin
JSON expresses styles only, never structure.

## Decision

A standalone library (`libgdx-ui-markup`) with four modules builds Scene2D UIs from an HTML-like
XML dialect plus a bounded CSS subset:

1. **Dialect: HTML-like XML + CSS for styling only.** Layout is Scene2D-native through XML
   attributes (`expand`, `fill`, `align`, `colspan`, `pad`, `space`, `grow`); CSS is a bounded
   styling subset compiled into a libGDX Skin (drawable/color/font fields per widget state).
   There is no CSS layout engine and no full HTML dialect; agents get a familiar syntax whose
   rendering matches Scene2D's own layout engine deterministically.
2. **Two-phase parse→build.** `MarkupParser` and the CSS engine are GL-free and produce
   immutable models with typed, located diagnostics (`MarkupException.Kind` + element path +
   line/column). `MarkupBuilder.build` runs on the render thread, compiles CSS into the skin,
   creates actors, applies cell constraints, and emits semantics.
3. **Semantics by construction through a string-based `SemanticSink` SPI.** The core has no
   harness dependency: `id` → test identifier + actor name, `name` → accessible name, `label`
   → label, tag → canonical role, `data-*` → properties. `NoopSink` keeps actors addressable by
   `findActor`; the harness adapter maps canonical strings to the harness `Role` enum (exact
   enum-name fallback; unknown roles are skipped rather than failing the build).
4. **Bounded at every trust boundary.** Input size (1 MiB), element count (10 000), depth (64),
   attribute/text length (4 KiB), CSS rules and declarations, status output, MCP deadlines, and
   screenshot payloads all have hard limits enforced in the parser, builder, and preview.
5. **Prior-art lessons reused.** The tag→`TagFactory` registry (gdx-lml's provider registry)
   enables custom widgets without touching the core — the parser accepts allowlisted custom
   tags, the registry supplies the actor. Table/Cell is the layout engine; CSS never positions
   actors.
6. **Pseudo-state interpretation.** `:hover`/`:pressed`/`:checked`/`:disabled` selectors and
   the `background-*` properties map to the widget style's state fields (`over`, `down`,
   `checked`, `disabled`) on the base style, not to whole-style swaps; Scene2D widgets already
   render state fields at runtime. `tag` and `tag.class` selectors compile into named skin
   styles; class-only and id-only selectors apply per actor (cloned style, never mutating the
   shared skin style).
7. **Stylesheet identity.** The bounded language is named GDXCSS and uses `.gdxcss` for canonical
   files. `CssParser` remains extension-neutral and the preview/IDE continue accepting `.css`,
   so the name sets accurate authoring expectations without breaking existing applications.
8. **Web-shaped syntax, Scene2D semantics.** GDXCSS supports bounded universal/compound,
   descendant, and direct-child selectors; global `:root` design tokens; responsive dimensions;
   and paint/text/image/Actor properties only where Scene2D has a deterministic conversion.
   Selectors have at most eight parts, variables are capped at 256 with resolution depth 16,
   and every match attempt consumes cascade work. There is still no inheritance, browser box
   model, flex/grid, positioning, media query, script, URL, or general CSS transform engine.
9. **Relative sizing remains Table-native.** Percent dimensions in a Cell become live Scene2D
   `Value`s evaluated against the containing Table. The exact `width: 100%; height: 100%` pair
   fills a top-level Table. Other percentages without a Cell fail typed; they are never frozen
   to a build-time pixel value.

## Follow-up: agent-runtime value source

The same semantics-by-construction principle extends to runtime values: `libgdx-ui-markup-runtime`
registers every `data-runtime-entity` actor as an agent-runtime entity (published
`agent-runtime-core` 1.0.0) whose property supplier reads the widget's live state, plus a
native `UiBinding` mapping the entity property to the actor's control id. The final hop —
exposing those values through the harness `ui_runtime_compare` tool with typed frame
correlation — landed with harness 1.1.0 (which publishes `harness-agent-runtime`); see
ADR 0002 for the wiring and its correlation contract.

## Consequences

- Markup-declared UIs are drivable through the harness MCP without any imperative metadata
  code: the E2E test locates `role=button name=Save` with `testId=save`, clicks through the
  real input path, and observes the state change, all from `samples/signin.xml` + CSS.
- Diagnostics are typed and located end to end; CI (preview `--exit`) and the IDEA plugin parse
  the same bounded `markup-status` line.
- Deviations from the plan are recorded in code comments: with `--mcp`, `markup-status` lines
  move to stderr because stdout carries JSON-RPC; gdx 1.14.2's `CheckBoxStyle` has no
  `checkboxDown`/`disabledFontColor` of its own (inherited from `TextButtonStyle`), and
  `Actor` has no min-size setters (min-* is a cell constraint only).
- A root `<ui>` element produces a plain root `Group` that the host must size to the viewport
  (the preview does this) or harness actionability sees a zero-sized parent.
- GDXCSS is deliberately not browser CSS. Unknown properties, unsupported selectors/units, and
  incompatible actor targets are errors. Legacy `.css` paths remain accepted, and legacy
  one/four-value comma spacing remains parseable; new sources use `.gdxcss` and CSS-order
  whitespace shorthands. Table padding is internal and margin is external Cell space.
