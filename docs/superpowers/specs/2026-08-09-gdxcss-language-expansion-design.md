# GDXCSS language expansion design

## Status

Approved for implementation by the user's instruction to choose the design and delivery details.
This document defines the public language contract for the work; implementation plans and pull
requests must not silently broaden or weaken it.

## Objective

Make the styling language familiar and productive for HTML/CSS-trained AI agents without
pretending that Scene2D is a browser. The canonical stylesheet extension becomes `.gdxcss`,
relative sizing such as `width: 100%` works responsively, and the bounded CSS subset gains the
most useful properties that have deterministic Scene2D equivalents.

Semantic reliability remains more important than accepting syntax. A construct that cannot be
represented faithfully must fail with a located `MarkupException`, not be ignored or approximated.

## Decision

Use a direct Scene2D conversion model:

1. CSS-like source is parsed GL-free into immutable, bounded values.
2. Selectors and custom properties are resolved GL-free with bounded work.
3. The render-thread builder converts resolved values to `Actor`, widget-style, `Table`, and
   `Cell` operations.
4. Responsive dimensions use Scene2D `Value` objects so they are reevaluated during layout rather
   than frozen at build time.

Do not add a browser, DOM, Yoga, flexbox, or independent CSS layout engine. Table/Cell remains the
only layout authority.

## Language identity and compatibility

- `.gdxcss` is the canonical and documented extension.
- `CssParser` continues to accept strings and arbitrary `Path` names; `.css` remains compatible.
- Preview file watching recognizes `.gdxcss` and `.css`.
- IDEA sibling discovery prefers `<markup-base>.gdxcss`, then falls back to
  `<markup-base>.css`.
- The IDEA plugin registers `.gdxcss` as a CSS-like file type with distinct GDXCSS identity.
- Samples, executable fixtures, qualification corpus, README commands, CLI help, cookbook
  recipes, and current release-facing documentation use `.gdxcss`.
- Historical plans, ADR history, and old release notes are not rewritten solely to rename an
  artifact that was correctly named when written.

## Value model

### Lengths

The immutable GL-free model distinguishes:

- `Pixels(float)` — unitless or `px`, non-negative unless the property explicitly allows signed
  values;
- `Percent(float)` — a finite percentage;
- `Auto` — only on properties that define native/preferred sizing behavior.

Supported responsive properties are `width`, `height`, `min-width`, `min-height`, `max-width`,
and `max-height`.

- A percentage width in a Table cell resolves against the containing Table width through a
  Scene2D `Value`; percentage height resolves against its height.
- Percent values remain live when the containing Table or viewport changes size.
- A top-level Table with both `width: 100%` and `height: 100%` uses `setFillParent(true)` and
  follows its Stage or parent Group.
- Percent dimensions on an actor without a Table cell, except that full-parent top-level Table
  case, fail with `STYLE_ERROR` at the declaration. They are never evaluated once at build time.
- `auto` clears an explicit CSS constraint and delegates to the actor's native Scene2D
  min/preferred/max value. XML dimensions remain explicit numeric constraints in this release.
- Percentage padding, margin, gap, font size, transforms, and XML dimensions are out of scope.

The existing unitless and `px` forms remain compatible. `em`, `rem`, viewport units, physical
units, `calc()`, `min()`, `max()`, and `clamp()` remain typed errors because the library has no
browser font/viewport value context for them.

### Spacing shorthand

`padding`, `margin`, and `gap` use familiar CSS whitespace syntax:

- one value: all sides;
- two values: vertical, horizontal;
- three values: top, horizontal, bottom;
- four values: top, right, bottom, left.

The legacy one-or-four comma-separated form remains accepted for source compatibility. New docs
use whitespace syntax. Values are non-negative pixels.

### Colors

Color values accept:

- `#rgb`, `#rgba`, `#rrggbb`, and `#rrggbbaa`;
- `rgb(r, g, b)` with integer channels from 0 through 255;
- `rgba(r, g, b, a)` with integer RGB channels and alpha from 0 through 1;
- `transparent`;
- a bounded identifier resolved from the caller-owned Skin.

Unresolved names and out-of-range channels are located typed errors. GDXCSS does not download
assets or fonts.

## Property conversion table

### Layout and visibility

| GDXCSS property | Values | Scene2D conversion |
|---|---|---|
| `width`, `height` | pixels, percent, `auto` | Cell constraint; fixed Actor size outside a cell; percent as defined above |
| `min-width`, `min-height` | pixels, percent, `auto` | Cell min constraint |
| `max-width`, `max-height` | pixels, percent, `auto` | Cell max constraint; Scene2D zero remains unbounded only for `auto` |
| `padding[-side]` | pixel shorthand | Table internal padding for Table actors; existing Cell padding behavior remains compatible for other actors |
| `margin[-side]` | pixel shorthand | containing Cell `space*` |
| `gap`, `row-gap`, `column-gap` | pixels | Table child-cell spacing defaults |
| `display` | `initial`, `none` | normal build, or omit actor from the parent actor/layout tree |
| `visibility` | `visible`, `hidden` | `Actor#setVisible`; layout is retained |
| `visible` | `true`, `false` | compatibility alias; `visibility` wins if both are declared |
| `overflow` | `visible`, `hidden` | `Table#setClip`; unsupported actor types fail typed |
| `vertical-align` | `top`, `middle`, `bottom` | Label alignment and/or containing Cell alignment as applicable |

`display` is base-state only. A `display` or dimension declaration in a pseudo-state rule is a
typed error because changing the Table structure during input-state transitions is not supported.

### Text

| GDXCSS property | Values | Scene2D conversion |
|---|---|---|
| `font-family` | registered family identifier | standard alias for existing `font`; XML `font` still has highest priority |
| `font`, `font-size` | existing bounded values | retained compatibility and exact-size FreeType behavior |
| `color`, `font-color` | color | widget font-color fields and supported pseudo fields |
| `text-align` | `left`, `center`, `right` | Label/TextField alignment |
| `vertical-align` | `top`, `middle`, `bottom` | Label vertical alignment |
| `white-space` | `normal`, `nowrap` | Label wrapping on/off; non-Label targets fail typed |
| `text-overflow` | `clip`, `ellipsis` | Label ellipsis off/on; non-Label targets fail typed |

`font-family` and legacy `font` may not both survive the cascade for one element: the declaration
with later source order wins after alias normalization. Font size remains forbidden in pseudo
states.

### Paint, input, image, and transforms

| GDXCSS property | Values | Scene2D conversion |
|---|---|---|
| `background` and state variants | Skin drawable identifier | existing widget/Table drawable mapping |
| `background-color` | color | tint the selected background drawable; without `background`, tint the Skin's `white` drawable |
| `opacity` | number 0 through 1 | `Actor#getColor().a` |
| `pointer-events` | `auto`, `none` | `Touchable.enabled` / `Touchable.disabled` |
| `object-fit` | `contain`, `cover`, `fill`, `none` | Image `Scaling.fit`, `fill`, `stretch`, or `none` |
| `object-position` | horizontal/vertical alignment keywords | Image alignment |
| `scale` | one or two positive finite numbers | Actor scale around its configured origin |
| `rotate` | finite degrees with required `deg` suffix | Actor rotation |
| `transform-origin` | alignment keywords | Actor origin through Scene2D `Align` |

Background-color requires a tintable base drawable. A custom Skin without the selected drawable
or the fallback `white` drawable fails `UNRESOLVED_STYLE`; the builder does not allocate an
unowned Texture. General `transform`, translate functions, skew, matrices, filters, shadows,
gradients, borders, and border radius remain unsupported.

## Selector expansion

Keep selectors bounded and deterministic while adding the structures agents commonly emit:

- universal `*`;
- multiple classes, for example `button.primary.danger`;
- tag plus ID, for example `button#save`;
- descendant and direct-child combinators;
- `:active` as an alias for existing `:pressed`;
- `:focus` for widget/property combinations with a real focused style field.

The pseudo-state may occur only on the rightmost compound. Attribute selectors, sibling
combinators, pseudo-elements, structural pseudo-classes, `:not()`, `:has()`, and selector lists
inside functions remain unsupported.

A selector contains at most eight compound selectors. Existing selector length, group count,
total count, and comparison limits remain, and every compound comparison counts toward cascade
work. The resolver receives an immutable ancestor list from the builder. Existing direct
single-element resolution APIs remain source-compatible and match structural selectors only when
an ancestry-aware overload is used.

Specificity follows CSS-shaped weights: ID 100, each class/pseudo 10, and each tag 1. The
universal selector contributes zero. Later rules continue to break equal-specificity ties.

## Custom properties

Support a deliberately global subset:

```css
:root {
  --surface: #182026;
  --space-md: 12px;
}

.panel {
  background-color: var(--surface);
  padding: var(--space-md);
}
```

- Custom properties may be declared only in one `:root` rule and are not selectors or actor
  properties.
- At most 256 custom properties are allowed.
- Names use `--` followed by the existing bounded identifier grammar.
- `var(--name)` may replace a complete property value; mixed token substitution and fallback
  arguments are out of scope.
- Resolution depth is capped at 16 and cycles/unresolved names are located `STYLE_ERROR`s.
- Substitution happens before property validation, so the target property's existing grammar
  remains authoritative.

This supplies agent-friendly design tokens without implementing CSS inheritance.

## Diagnostics and compatibility

- Unknown properties remain errors.
- Known properties on incompatible actor types fail at the source declaration rather than being
  ignored.
- New aliases are normalized before the cascade so source order remains deterministic.
- Every new error retains kind, element path or `css`, line, column, and actionable message.
- Parsed public values remain immutable, serializable where public, and free of Actor/libGDX
  types. Scene2D `Value` objects are created only on the render thread.
- Existing `.css`, pixel lengths, selectors, comma spacing, XML layout attributes, Skin styles,
  and public Java entry points remain compatible.
- Nested Tables currently receive CSS padding both internally and on their containing Cell. The
  expanded dialect deliberately normalizes this to the web-shaped meaning: `padding` is internal
  and `margin` is external. The migration note calls out this single CSS behavior correction;
  XML `pad` behavior does not change.

## Intentional non-goals

- flexbox, grid, floats, general positioning, media/container queries, and a CSS box-layout engine;
- inheritance, browser user-agent defaults, and DOM layout;
- arbitrary URLs, filesystem reads, web fonts, scripts, event handlers, and unrestricted method
  calls;
- borders/radius, shadows, gradients, filters, blend modes, and general transform matrices;
- full HTML tags, forms, templates, loops, or a reactive binding language.

These are not silently accepted. Agents receive typed diagnostics and should use Table/Cell XML
attributes or application Java where the bounded language does not represent a concept.

## Pull-request sequence

Work is delivered sequentially from freshly reconciled `main`; each PR is independently reviewed,
CI-green, and merged before the next begins.

1. **GDXCSS identity and migration** — canonical extension, preview/watch compatibility, IDEA file
   type and sibling fallback, repository recipes and fixtures.
2. **Responsive lengths and layout properties** — immutable length values, `%`, `auto`, max sizes,
   standard spacing, gaps, display/visibility, overflow, and render-thread conversion.
3. **Visual/text/image/actor properties** — colors, background tint, opacity, pointer events,
   wrapping/ellipsis, object fit/position, scale/rotate/origin, and compatibility aliases.
4. **Selectors and design tokens** — compounds, ancestry combinators, pseudo aliases/focus, and
   bounded root custom properties.

## Acceptance criteria

1. `samples/signin.gdxcss` is canonical; `.css` input remains executable.
2. A rendered Table child with `width: 100%` tracks its containing Table after at least two
   different parent sizes without rebuilding the UI.
3. A top-level Table with `width: 100%; height: 100%` tracks the preview viewport.
4. Unsupported relative-size contexts and units fail with located typed diagnostics.
5. Every property in the conversion tables has positive behavior coverage and invalid-value or
   incompatible-target coverage where applicable.
6. Selector ancestry, specificity, pseudo aliases, variable cycles, missing variables, and all
   new bounds have GL-free tests.
7. The preview success and error paths exercise `.gdxcss`; legacy `.css` is covered by a
   compatibility test.
8. The cookbook contains compilable recipes for responsive layout, common styling, variables,
   and diagnosis, and its property/selector support table matches the parser.
9. The harness E2E still drives a markup-declared UI by strict semantics and captures a PNG.
10. The IDEA plugin builds and resolves `.gdxcss` before `.css`.

## Verification gates

Run narrow tests during each red-green loop, then before the final merge:

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

The final audit also checks the merged PR heads, required GitHub checks, current cookbook, public
fixtures, compatibility tests, and a clean local `main` synchronized with `origin/main`.
