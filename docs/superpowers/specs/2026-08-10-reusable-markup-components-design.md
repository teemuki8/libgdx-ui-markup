# Reusable markup components design

## Status

Approved by the user on 2026-08-10. This document defines the first reusable-component
contract. Implementation plans and pull requests must not silently broaden or weaken it.

## Objective

Reduce repetitive Scene2D construction and repeated markup by allowing one UI document to define
bounded reusable components with parameters, named/default slots, semantic defaults, native
Table/Cell layout, GDXCSS styling, and transparent passage of runtime-related attributes.

Component authoring remains declarative. Components are expanded during the GL-free parse phase
into the same immutable concrete `Element` tree consumed today. `MarkupBuilder`, Scene2D,
`SemanticSink`, the harness adapter, and the runtime adapter never receive component definitions,
invocations, slots, fills, or a scripting runtime.

This increment also makes component failures actionable: diagnostics identify the source,
concrete element, failing attribute, expected and received values, deterministic suggestion when
available, semantic consequence, template origin, and bounded invocation trace. Preview reloads
remain transactional and keep the last-good UI active after component failures.

## Architectural decision

Use explicit parse-time expansion:

1. The hardened SAX reader constructs a bounded internal raw tree without creating libGDX types.
2. A component compiler validates definitions and invocations, substitutes parameters, expands
   slots, detects cycles, and produces only concrete raw elements.
3. The existing concrete tag and attribute validation compiles that expanded tree into immutable
   public `Element` values.
4. `MarkupDocument` carries immutable provenance for each final element path.
5. The render-thread builder receives only the concrete document it already understands.

The public syntax uses `<use component="Name">` instead of dynamic tags such as `<HealthBar>`.
Explicit invocation keeps built-in tags, custom registered tags, and component names in separate
namespaces and lets the parser report deterministic unknown-component diagnostics. Dynamic
component tags may be considered later as syntax sugar but are not accepted by this contract.

External preprocessors and builder-time component objects are rejected. A preprocessor would
lose trustworthy source locations and transactional preview behavior; builder-time expansion
would cross the GL-free/render-thread boundary and make harness/runtime structure depend on
rendering.

## Source grammar

### Document placement

An `<ui>` root may contain one optional `<components>` block as its first element child. The block
contains only `<component>` definitions. All later children are the UI body.

```xml
<ui>
  <components>
    <component name="HealthBar">
      <!-- declarations and one template root -->
    </component>
  </components>

  <use component="HealthBar" id="player-health" current="72"/>
</ui>
```

Rules:

- `<components>` is invalid under a `<table>` root or anywhere below the root.
- More than one `<components>` block, a block after a body child, or a non-component child in the
  block is a located component-definition error.
- Definitions are document-local. They may be referenced before or after their declaration
  because the complete bounded raw tree is collected before component compilation.
- Markup cannot import paths, URLs, classes, or registries. Reuse across files requires a future
  caller-controlled source-resolution design; markup never gains arbitrary filesystem access.

### Component names and definitions

A component name matches `[A-Z][A-Za-z0-9]{0,63}`. Names are case-sensitive and unique within the
document. Each `<component>` contains zero or more `<param>` declarations followed by exactly one
concrete template root. The root must be a built-in actor tag, a caller-approved custom tag, or a
`<use>` that ultimately expands to exactly one concrete actor.

`<row>`, `<slot>`, `<fill>`, `<components>`, `<component>`, and `<param>` cannot be the final
component root. A component cannot expand to zero or multiple root actors. Requiring one root
makes invocation layout, root attribute overrides, semantic identity, and CSS ancestry
unambiguous.

### Parameters

Parameter declarations use:

```xml
<param name="current" required="true"/>
<param name="max" default="100"/>
```

The contract is:

- A name matches `[a-z][a-z0-9-]{0,63}` and is unique within the component.
- `required` is optional and defaults to `false`.
- `default` is optional bounded text and is literal; it cannot contain `${...}` substitutions.
- `required="true"` and `default` are mutually exclusive.
- A parameter without `required="true"` or `default` resolves to the empty string when omitted.
- At most 64 parameters are declared by one component.
- Unknown invocation attributes that are neither declared parameters nor permitted root
  overrides are typed errors. They are never silently ignored.

Substitution uses `${name}` in attribute values and element text. Substitution is textual,
bounded, and supports literals around references, for example `id="${id}-value"`. A value may
contain at most 32 references. Every reference must name a declared parameter. Expansion must
produce no more than the existing attribute/text length limit and is then validated by the
target concrete tag's ordinary grammar.

Parameter scope is lexical. Substitution runs in the component's template root and fallback slot
content. Caller-provided fill content does not inherit the invoked component's parameters and is
compiled in its own source scope. If an outer component template supplies a fill to a nested
component, outer substitutions occur while expanding that outer template; the nested component
cannot otherwise read outer parameters. `${...}` outside a component template is ordinary text
and receives no special evaluation.

`${name}` is only component substitution. `{player.health}` remains a distinct opaque string for
the separate runtime-binding language. Components do not evaluate runtime expressions or weaken
the target attribute grammar.

### Component invocation and root overrides

`<use>` requires `component`. Its remaining attributes are partitioned as follows:

- names declared by the target component provide parameter values;
- common actor attributes (`id`, `name`, `label`, `class`, `style`, visibility, focus, dimensions,
  and Table/Cell placement attributes) override the expanded root;
- `data-*` attributes override the expanded root;
- any other name is an `UNKNOWN_PARAMETER` failure.

If a common root-override name is also a declared parameter, the same invocation value serves
both purposes. This intentionally supports declaring `id` as a required parameter, applying it
to the component root, and deriving child IDs such as `${id}-bar`.

Caller `class` tokens are appended to template-root class tokens, de-duplicated while retaining
first occurrence, and lower-cased through the existing class normalization. A caller cannot
erase the component's stable scoping class. Every other supplied root override replaces the
template-root value and is validated against the final concrete root tag.

Parameters do not create hidden ID namespaces. Repeated components use explicit interpolation
for unique descendant IDs. Duplicate IDs are detected after complete expansion with the existing
`DUPLICATE_ID` contract and the expanded concrete path.

### Slots and fills

A template inserts caller children through `<slot>`:

```xml
<slot/>
<slot name="details" required="true"/>
<slot name="empty-state">
  <label text="Nothing here"/>
</slot>
```

Rules:

- A slot name matches the parameter-name grammar. Omitted `name` denotes the one default slot.
- Names, including the implicit default name, are unique within a component.
- At most 32 slots are declared by one component.
- Slot children are fallback content. They are expanded only when the invocation supplies no
  fill for that slot.
- `required="true"` is mutually exclusive with fallback children and fails if no fill is
  supplied.
- A non-required slot without a fill or fallback expands to no children.

An invocation supplies children only through direct `<fill>` elements:

```xml
<use component="HealthBar" id="player-health" current="72">
  <fill slot="details">
    <label id="player-health-details" text="Regenerating"/>
  </fill>
</use>
```

Omitted `slot` targets the default slot. Fill names must exist and be unique per invocation.
Raw actor children directly under `<use>` are rejected so named/default assignment never depends
on position or inference. Fill content may contain concrete elements, `<row>`, or nested `<use>`
invocations and is validated in its final expanded parent context. Filled elements retain their
caller source origin; the surrounding component invocation is still present in their trace.

## Complete example

```xml
<ui>
  <components>
    <component name="HealthBar">
      <param name="id" required="true"/>
      <param name="current" required="true"/>
      <param name="max" default="100"/>

      <table class="health-bar">
        <progressbar id="${id}-bar"
                     min="0"
                     max="${max}"
                     value="${current}"/>
        <slot name="details"/>
      </table>
    </component>
  </components>

  <use component="HealthBar"
       id="player-health"
       current="72">
    <fill slot="details">
      <label id="player-health-details" text="Regenerating"/>
    </fill>
  </use>
</ui>
```

After parsing, `MarkupDocument.root()` contains a concrete Table with root ID `player-health`,
classes including `health-bar`, a ProgressBar named `player-health-bar`, and the caller Label.
No component-only element exists in the public tree.

## Expansion algorithm and bounds

The raw input remains subject to the existing 1 MiB UTF-8, 10,000 raw-element, depth,
attribute-length, and text-length limits. Definitions count toward those raw-input limits.

Component compilation additionally enforces:

| Resource | Limit |
|---|---:|
| component definitions | 256 |
| parameters per component | 64 |
| slots per component | 32 |
| substitutions per attribute/text value | 32 |
| nested component expansion depth | 16 |
| final concrete elements | 10,000 |
| total component/template expansion visits | 100,000 |
| diagnostic invocation-trace frames | 16 |

The compiler indexes definitions in source order, validates every definition before expanding
the body, and expands invocations depth-first in document order. A stack of component names and
invocation origins detects direct and indirect recursion. Cycle messages list the complete
bounded chain, for example `Menu -> MenuItem -> Menu`.

The final element budget is checked before adding each concrete element. Expansion-work counts
every visited component, template, slot, fill, and substituted raw node, including nodes that
later disappear as empty slots. Exceeding either budget throws `TOO_LARGE`; partial output is
discarded.

After expansion, the ordinary concrete validator runs once over the final tree. Required
attributes, value grammars, custom-tag admission, root restrictions, mixed-content rules, and
duplicate IDs therefore have one authority and behave identically for handwritten and generated
elements.

## CSS and semantics

GDXCSS resolves against the expanded concrete tag/class/ID ancestry. Component names and
component-only elements are not CSS selectors. A component that needs scoping declares a stable
class on its template root:

```css
.health-bar progressbar { width: 100%; }
.health-bar .details { font-color: accent; }
```

This uses the existing bounded selector engine and avoids another CSS language. Because caller
classes merge, the stable component class remains present. Slotted content is part of the
expanded subtree and therefore participates in scoped descendant selectors.

Semantic roles continue to come exclusively from concrete tag vocabulary. Template `id`, `name`,
and `label` values are semantic defaults; root overrides and parameter substitution may replace
them. The harness receives only final actor semantics. Runtime-related `data-*` attributes pass
through the same substitution and root-override rules, but component compilation neither reads
widget state nor registers gameplay authority.

## Runtime-binding boundary

This component increment is binding-transparent, not a binding evaluator. It may substitute an
opaque runtime expression into an attribute, but the final target grammar remains authoritative.
For example, substituting `{player.health}` into a future `bind-value` attribute is preserved;
substituting it into today's numeric-only `progressbar value` remains an `INVALID_VALUE` until a
separate one-way display-binding contract explicitly makes that target bindable.

That later contract must source values from caller-owned authoritative runtime/gameplay state,
mutate actors only on the render thread, and keep divergence observable through runtime
correlation. Components must not introduce two-way state, widget-to-gameplay writes, arbitrary
expressions, reflection, or method calls.

## Provenance and diagnostics

### Immutable provenance

`MarkupDocument` gains immutable provenance keyed by final concrete element path. The existing
two-argument `MarkupDocument(Element, int)` constructor remains available and supplies a stable
in-memory source plus empty invocation traces. Provenance contains:

- normalized source identity;
- concrete element path;
- origin line and column;
- optional originating attribute;
- zero to 16 component invocation frames, outermost first.

Template-generated elements use the template source location. Filled elements use the caller
content location. Each frame contains component name plus the `<use>` path, line, and column.
No Actor, libGDX collection, backend type, mutable map, or global registry enters the model.

`MarkupParser.parse(Path)` uses the normalized path as source identity. A new string overload
accepts a caller-supplied source name. `parse(String)` uses the stable label `<memory>`. Path
identity is `path.toAbsolutePath().normalize().toString()`. A supplied name must be nonblank, must
not contain control characters, and is capped at 4,096 UTF-16 units before any parse work.

### Structured failures

`MarkupException` retains its existing constructor and accessors and adds bounded optional
structured context for source, attribute/property, expected shape, received value, deterministic
suggestion, semantic consequence, and component trace. Existing callers remain valid.

Component failures add stable kinds:

- `DUPLICATE_COMPONENT`;
- `UNKNOWN_COMPONENT`;
- `MISSING_PARAMETER`;
- `UNKNOWN_PARAMETER`;
- `DUPLICATE_SLOT`;
- `UNKNOWN_SLOT`;
- `MISSING_SLOT`;
- `COMPONENT_CYCLE`.

Malformed declaration structure uses `INVALID_VALUE`; size/work failures use `TOO_LARGE`; final
concrete validation retains the existing kind such as `UNKNOWN_ATTRIBUTE`, `MISSING_ATTRIBUTE`,
`INVALID_VALUE`, or `DUPLICATE_ID`.

Unknown component, parameter, and slot failures offer one nearest valid alternative only when it
is deterministic. Candidate sets are already bounded. Distance uses case-sensitive Levenshtein
distance with maximum accepted distance `max(1, min(3, received.length() / 3))`; candidates are
checked in lexicographic order. A unique lowest-distance candidate within the threshold is
reported. Equal best candidates produce no suggestion.

A substituted concrete failure reports the template origin and invocation trace. Its semantic
consequence is `document rejected before Scene2D build`. Core diagnostics do not claim preview
state they cannot observe.

### Preview and IDEA protocol

`markup-status` advances to schema version 3. Failure status retains the existing fields and adds
bounded `source`, `attribute`, `expected`, `received`, `suggestion`, `consequence`, and
`componentTrace`. Optional strings serialize as empty strings for a stable shape. The trace is an
array of at most 16 immutable frames. Success status remains `ok` plus concrete actor `nodes`.

The IDEA status parser accepts schema version 3 and displays source/line/column first, then the
message and structured actionable context. It fails closed on unsupported schema versions as it
does today. All strings retain the existing 2,000-UTF-16-unit per-field bound; the complete trace
also has a 16-frame and 16,384-UTF-16-unit aggregate bound.

## Transactional hot reload

Definitions are part of the watched UI file, so no new watcher or polling path is introduced.
The existing 300 ms debounce triggers the same full candidate rebuild without restarting the
preview application.

Parsing, expansion, concrete validation, Scene2D building, semantic emission, and runtime
attachment all occur before candidate commit. Any component failure closes the candidate,
retains the last-good actor tree, Skin, runtime registration, and harness session, and displays
the schema-v3 typed diagnostic overlay. No partially expanded or partially built tree becomes
visible. A later valid edit recovers through the ordinary rebuild path.

The reported success node count includes concrete actors only. Component definitions, uses,
slots, fills, and parameters never appear in screenshots, semantic queries, or runtime
correlation.

## Compatibility

- Documents without `<components>` produce an equal concrete `Element` tree and retain current
  validation kinds, paths, lines, and columns.
- Existing `MarkupDocument(Element, int)` and `MarkupException` construction remain callable.
- Existing `MarkupParser.parse(String)` and `parse(Path)` remain callable.
- Custom registered tags are valid inside component templates and fills when admitted through
  the parser's existing `extraTags` set.
- Existing layout, GDXCSS, Skin, semantic, harness, and runtime APIs require no component-specific
  actor behavior.
- The status schema change is intentional and synchronized across preview, tests, cookbook, and
  IDEA plugin in the same delivery.
- This is a public markup-dialect and diagnostic-protocol change and is documented as such; no
  release is published without a separate explicit release request.

## Intentional non-goals

- dynamic component element names such as `<HealthBar>`;
- external markup imports, URLs, arbitrary filesystem reads, or component discovery;
- loops, conditionals, arithmetic, functions, expression evaluation, scripting, or arbitrary
  Java execution;
- multiple-root fragments or implicit layout wrappers;
- implicit descendant-ID namespacing;
- automatic CSS selector rewriting or a new scoped-CSS syntax;
- runtime expression evaluation, gameplay state ownership, two-way binding, or widget-to-domain
  writes;
- incremental actor-tree patching; reload replaces one fully prepared candidate transactionally.

## Documentation and architecture record

Implementation updates, in the same change:

- `docs/adr/0005-parse-time-markup-components.md` for the lasting expansion, provenance, and
  boundary decision;
- README syntax, limits, diagnostics, compatibility, and non-goals;
- a component-backed sample XML/GDXCSS pair;
- every affected recipe and support table in `docs/guides/agentic-cookbook.md`;
- preview and IDEA schema documentation;
- release-facing notes only when a release is separately requested.

Public examples are executable or referenced by an automated test.

## Acceptance criteria

1. A document can define and invoke a component with required/default parameters, interpolate
   values into attributes and text, fill named/default slots, and use fallback slots.
2. The returned `MarkupDocument.root()` contains only concrete elements and is accepted by the
   unchanged Scene2D construction and semantic pipelines.
3. Invocation root ID/semantic/layout/data attributes override template defaults, while template
   and caller classes merge deterministically.
4. Nested components expand in document order; direct and indirect cycles fail with a bounded
   chain and `COMPONENT_CYCLE`.
5. Missing/unknown parameters and slots, duplicate definitions/slots, invalid component roots,
   substitution overflow, expansion depth, work, and final-element bounds each have focused
   typed regression coverage.
6. Unknown bounded names provide exactly one suggestion only under the specified unique-distance
   rule.
7. Concrete validation after substitution reports the final concrete path, template source and
   attribute, expected and received values, consequence, and complete bounded invocation trace.
8. Documents without components preserve current public parse behavior and test fixtures.
9. An Xvfb builder test renders a component-backed HUD using native Table layout and GDXCSS and
   proves component-generated semantic IDs/names/roles.
10. Preview tests prove successful component reload, failed-expansion last-good retention, and
    recovery, with no sleeps and schema-v3 bounded status.
11. Harness E2E queries and acts on component-generated semantics, waits for state, compares the
    correlated runtime value, and captures a screenshot.
12. IDEA tests parse/display schema-v3 component diagnostics and `buildPlugin` succeeds.
13. README, ADR, sample, and every affected agentic-cookbook recipe describe the exact implemented
    syntax and bounds.

## Verification gates

Run the narrowest proof during every red-green loop. Before claiming the component increment
complete, run and record:

```text
./gradlew :libgdx-ui-markup:test \
  --tests 'dev.gdx.markup.core.MarkupParserTest' \
  --warning-mode=fail --rerun-tasks

xvfb-run -a ./gradlew \
  :libgdx-ui-markup:test \
  :libgdx-ui-markup-preview:test \
  :libgdx-ui-markup-harness:test \
  --warning-mode=fail --rerun-tasks

./gradlew :libgdx-ui-markup-idea:check \
  :libgdx-ui-markup-idea:buildPlugin \
  --warning-mode=fail --rerun-tasks

xvfb-run -a ./gradlew build \
  --warning-mode=fail --console=plain

git diff --check
```

The final audit also inspects the concrete public tree, schema-v3 JSON, last-good preview state,
component sample screenshot, harness evidence, current cookbook, ADR, and exact branch head. A
successful build alone is not sufficient.
