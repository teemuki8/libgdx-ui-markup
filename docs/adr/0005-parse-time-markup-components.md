# ADR 0005 — Parse-time reusable markup components

Status: Accepted · Date: 2026-08-10

## Context

Declarative Scene2D screens repeat bounded structures such as labeled controls, dialog shells,
HUD meters, and action rows. Copying those structures makes semantic IDs, runtime correlation,
Table/Cell constraints, and CSS classes drift. Applications could build factories above the
library, but then preview, IDEA diagnostics, and the committed XML no longer share one source of
truth.

Reuse must not weaken the existing architecture. Parsing remains immutable and GL-free;
`MarkupBuilder` remains the only Actor-producing phase and runs on the render thread. The public
model, CSS resolver, semantic sink, harness, and runtime adapter must continue to consume one
ordinary concrete element tree. The XML boundary must stay strict, bounded, reproducible, and
unable to read arbitrary files or execute application code.

## Decision

One optional document-local `<components>` block may appear as the first child of `<ui>`. The SAX
reader first creates an internal raw tree with source origins. A GL-free `ComponentCompiler`
indexes and validates all definitions, expands explicit `<use>` invocations depth-first, and
returns a concrete raw tree. The existing concrete validator then applies the authoritative tag,
attribute, value, root, mixed-content, custom-tag, and duplicate-ID rules exactly once.

The public `MarkupDocument.root()` contains only concrete `Element` values. Components add no
Actor type, layout node, CSS selector syntax, semantic role, runtime object, global registry, or
render-thread behavior. Documents without components follow the same pipeline and retain their
concrete behavior. Preview reload continues to prepare parsing, building, semantic emission, and
runtime attachment as one candidate before committing it.

## Component grammar/bounds

Definitions use `<component name="UpperCamelName">`, followed by zero or more `<param>` elements
and exactly one actor root or nested `<use>`. Component names match
`[A-Z][A-Za-z0-9]{0,63}`. Parameter and named-slot names match
`[a-z][a-z0-9-]{0,63}`.

A parameter may use `required="true"`, a literal `default`, or neither; required and default are
mutually exclusive. An optional parameter without a default becomes the empty string. `${name}`
performs bounded textual substitution in template attributes/text and fallback content. Caller
fill content remains in caller lexical scope. Defaults cannot contain substitutions. Final
values undergo the ordinary target attribute/text length and grammar checks.

`<slot/>` declares the default slot; `<slot name="details"/>` declares a named slot. An optional
slot may contain fallback actor children. A required slot cannot contain fallback. A `<use>` may
supply at most one direct `<fill>` for each declared slot, using an omitted `slot` attribute for
the default. A component expands to exactly one actor root; slots may contribute zero or more
children inside that root.

On `<use>`, declared parameter attributes supply values. Common actor attributes and `data-*`
also override the expanded root. If one name is both a parameter and a common override, the same
value serves both. Caller `class` tokens append to and de-duplicate the template-root tokens so a
stable component scoping class cannot be erased; other overrides replace the template value and
are validated against the final root tag. IDs have no implicit namespace.

The existing parser limits remain 1,048,576 UTF-8 input bytes, 10,000 raw elements, nesting depth
64, 4,096 characters per attribute value, and 4,096 characters per text value. Definitions count
toward the raw limits. Component compilation additionally enforces:

| Resource | Limit |
|---|---:|
| definitions per document | 256 |
| parameters per component | 64 |
| slots per component | 32 |
| substitutions per attribute/text value | 32 |
| nested component expansion depth | 16 |
| final concrete elements | 10,000 |
| component/template expansion visits | 100,000 |
| diagnostic invocation-trace frames | 16 |

Direct and indirect recursion fails as `COMPONENT_CYCLE`. Expansion depth, work, output, and
substitution overflow fail before any partial document can be returned.

GDXCSS resolves only against final concrete tag/class/ID ancestry. Components scope existing
selectors by declaring a stable class on the template root. Runtime-related `data-*` values pass
through unchanged or through textual parameter substitution, but components never evaluate
runtime expressions. An opaque `{player.health}` is preserved only where the target grammar
allows text; using it as today's numeric-only `progressbar value` remains `INVALID_VALUE`.

There are no external component imports, URLs, arbitrary filesystem reads, discovery paths,
dynamic tag names, loops, conditionals, arithmetic, functions, scripting, reflection, arbitrary
Java calls, multiple-root fragments, implicit ID namespaces, runtime/gameplay ownership, two-way
binding, or incremental actor-tree patching. The no-import boundary makes a watched source file
self-contained and prevents declarative reuse from becoming an execution or file-access surface.

## Provenance/diagnostics

Each final concrete path has immutable `ElementProvenance`: normalized source identity, concrete
path, line/column, optional originating attribute, and up to 16 outermost-first component
invocation frames. Template-generated elements retain template origins; caller fills retain
caller origins. The model contains no Actor, backend type, libGDX collection, or mutable global
state.

`MarkupException` retains stable kind/path/line/column and carries transport-neutral structured
context: source, attribute, expected shape, received value, deterministic nearest suggestion,
semantic consequence, and component trace. Components add `DUPLICATE_COMPONENT`,
`UNKNOWN_COMPONENT`, `MISSING_PARAMETER`, `UNKNOWN_PARAMETER`, `DUPLICATE_SLOT`, `UNKNOWN_SLOT`,
`MISSING_SLOT`, and `COMPONENT_CYCLE`; declaration errors use `INVALID_VALUE`, budget failures use
`TOO_LARGE`, and generated concrete errors retain the existing concrete kind.

Preview `markup-status` schema 3 projects that context. Success contains only schema, `ok`, and
the concrete node count. Failure contains the existing fields plus `source`, `attribute`,
`expected`, `received`, `suggestion`, `consequence`, and `componentTrace`. Each serialized string
is capped at 2,000 UTF-16 units; traces are capped at 16 frames and 16,384 aggregate UTF-16 units.
IDEA accepts exactly schema 3 and fails closed on unsupported versions.

A preview component failure rejects the candidate before commit, displays the structured
diagnostic, and retains the exact last-good actor tree, Skin, runtime registration, and harness
session. A subsequent valid source edit builds and commits one fresh complete candidate.

## Consequences

- Authors can remove repeated declarative structure without moving semantics or layout into Java.
- Parser, preview, IDEA, CSS, builder, harness, and runtime behavior share one concrete tree and
  one validation authority.
- CSS and runtime integrations need no component-specific API; stable template-root classes and
  ordinary `data-*` attributes remain sufficient.
- Exact budgets bound hostile expansion and diagnostics; transactional reload prevents partially
  expanded or partially built state from becoming live.
- Public provenance makes generated failures actionable while keeping the parse result GL-free.
- The markup dialect and preview protocol grow intentionally; consumers of `markup-status` must
  support schema 3, and public component syntax must remain synchronized with the cookbook and
  executable sample.

## Rejected alternatives

- Runtime Scene2D component actors were rejected because they would cross the GL-free boundary,
  create hidden layout/semantic nodes, and make builder, harness, and runtime behavior template
  aware.
- External imports or a component search path were rejected because they add arbitrary file/URL
  access, watcher complexity, dependency cycles, and non-reproducible resolution.
- Custom XML element names such as `<HealthBar>` were rejected because explicit `<use
  component="HealthBar">` keeps the dialect distinguishable from concrete/custom actor tags and
  makes unknown-component diagnostics unambiguous.
- General expressions, loops, conditions, scripts, and reflection were rejected because they
  create a programming language and execution surface rather than bounded declarative reuse.
- Multiple-root components and implicit wrappers were rejected because they obscure Table/Cell
  placement and root override semantics.
- Implicit ID namespacing and CSS selector rewriting were rejected because they silently change
  declared semantics and add a second selector language.
- Incremental actor patching was rejected for this increment because one fully prepared candidate
  preserves the existing ownership, disposal, semantic, runtime-correlation, and recovery model.
