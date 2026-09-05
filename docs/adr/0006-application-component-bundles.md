# ADR 0006 — Application-registered component bundles

Status: accepted, unreleased. Date: 2026-09-05.

## Decision

`MarkupParser.withComponentBundles(Map<String,String>)` returns an independent parser with an
immutable mapping of up to 16 UpperCamel namespaces to in-memory XML sources. The application
owns loading/versioning these sources; the parser performs no path, URL, or import resolution.
Each bundle contains exactly `<ui><components>...</components></ui>` and no actor body.

A bundle named `Common` exports `ActionButton` as `Common.ActionButton`. Unqualified static
`use` targets inside that bundle resolve in its namespace; already qualified targets remain
explicit. Bundle declaration names must be unqualified. Screen-local names remain unchanged;
a screen-local declaration colliding with a qualified export is a duplicate-component error.
General runtime expressions or scripting are not introduced. Existing parameterized component
targets, when used, must resolve to their full qualified name explicitly.

After bounded raw parsing and namespace qualification, bundle definitions and document-local
definitions enter the same ComponentCompiler and concrete validation path. The combined input
shares the configured UTF-8 byte and raw-element bounds; all definitions share the 256-component
bound and existing cycle, expansion-depth, work, parameter, and slot limits. Definition origins
retain `bundle:Common` source identity and original line/column. Caller invocation traces and
concrete screen provenance remain available. The parsed document byte count includes bundles.

IDs are not rewritten. Authors pass explicit instance IDs/prefix parameters, and duplicate Actor
IDs still fail concrete validation. Semantic and runtime bindings therefore retain the exact
declared identity. MarkupBuilder, CSS, Skin ownership, and semantic sinks are unchanged; all
bundle composition is immutable and GL-free, and Actor construction remains render-thread owned.

## Compatibility and scope

Existing parser constructors and document-local components behave unchanged. The new factory
replaces any previous bundle configuration on the returned parser; it never mutates the original.
Configured screens require an `<ui>` root. Names may contain one namespace separator, with each
segment matching `[A-Z][A-Za-z0-9]{0,63}`. A bundle's short references cannot silently bind to a
screen-local definition. The API is not included in published 0.6.0.

The stock preview CLI has no bundle-loading flag in this increment. An application using bundles
must configure its authoring/test host with the same supplied sources; unconfigured preview
parsers correctly reject unknown qualified components. This change does not supply a UI theme,
font fallback, responsive screen profiles, or aesthetic qualification.

## Verification

`ComponentBundlesTest` verifies shared screens, explicit IDs, configuration isolation, source
diagnostics, cross-bundle cycles, duplicate exports/IDs, aggregate bounds, namespace limits, and
external-entity rejection. `MarkupBuilderTest.registeredBundleBuildsTheSameConcreteScene2dAndSemanticPath`
builds a real button under LWJGL3 and checks text, source-declared identity, semantics, and native
Table sizing. Run `xvfb-run -a ./gradlew :libgdx-ui-markup:test --console=plain`, then the full
repository `clean check` gate and preview/harness tests.
