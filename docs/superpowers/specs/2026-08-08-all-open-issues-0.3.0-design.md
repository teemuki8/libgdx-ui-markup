# All Open Issues and 0.3.0 Release Design

**Date:** 2026-08-08
**Scope:** GitHub issues #5 through #28, inclusive
**Release:** 0.3.0
**Status:** Approved

## Objective

Close every currently open `teemuki8/libgdx-ui-markup` issue with behavioral regression coverage, merge the fixes through reviewable dependency-ordered pull requests, publish the three supported Maven modules as 0.3.0, and create the corresponding GitHub release only after Maven Central reports `PUBLISHED` and the public coordinates resolve.

## Constraints

- Preserve the GL-free parse-model boundary: no libGDX `Actor`, `Stage`, collection, or backend type enters parse results.
- Perform every Scene2D read or mutation on the libGDX render thread.
- Keep diagnostics typed with `MarkupException.Kind`, element path, one-based line/column, and a bounded raw message.
- Bound bytes, counts, dimensions, work, results, duration, and retained artifacts at trust boundaries.
- Use Java 25 without preview or incubator APIs; the IDEA plugin retains its JBR-compatible Java 21 toolchain.
- Treat project-code warnings as failures.
- Preserve 0.2.x source and binary compatibility where safely possible. Add explicit APIs for stronger contracts rather than repurposing existing public entry points silently.
- Release only `libgdx-ui-markup`, `libgdx-ui-markup-runtime`, and `libgdx-ui-markup-harness` to Maven Central.

## Delivery Strategy

Use six dependency-ordered domain pull requests, followed by one release-notes pull request:

1. Core semantics: #8, #11, #17, #18, #19, #20, #21, #23, #25.
2. Runtime contracts: #9, #10, #28; consume the core path utility from PR 1.
3. Preview and IDEA lifecycle: #6, #7, #12, #16, #22, #24; consume transactional runtime registration from PR 2.
4. Qualification trust boundary: #5, #13, #14, #15.
5. Visual-fidelity qualification: #27; consume deterministic screenshots from PR 3 and bounded image handling from PR 4.
6. Dependency verification and locking: #26; generate metadata after the dependency graph stabilizes.
7. Release notes and public examples for 0.3.0.

Each PR closes its named issues with `Fixes #N`, contains only its domain changes, passes its focused gates and the affected suite, and is reviewed at the exact head SHA before merge.

## Core Semantics

### Element paths — #23

Introduce one public, GL-free `ElementPathTracker` in the core module. Each entered element frame owns its own tag counter. `enter(tag)` computes and pushes a child path, `current()` returns the already-entered element path without incrementing, and `exit()` pops the frame. The first same-tag child uses the bare tag; later siblings use zero-based suffixes such as `button[1]`.

Migrate parser, builder, and runtime generation to the same utility. Error helpers use `current()` rather than entering the current element twice. Nested repeated tags must produce identical paths across parser, builder, runtime, preview status, and tests.

### Located range validation — #8

Before constructing `Slider` or `ProgressBar`, validate cross-field invariants using `BuildContext` coordinates:

- both widgets require `min <= max`;
- slider requires `step > 0`;
- existing finite-number checks remain in force.

Failures throw `MarkupException.Kind.INVALID_VALUE` at the element path and source coordinates. No libGDX constructor exception may escape for accepted markup.

### Boolean-or-axis layout — #19

For `expand`, `fill`, and `grow`, map `false` to neither axis, `true` to both axes, `x` to the horizontal axis, and `y` to the vertical axis. Parser grammar remains unchanged.

### Default skin resource ownership — #25

Create the upload `Pixmap` in a local variable and dispose it exactly once in a `finally` block after `Texture` construction succeeds or fails. The resulting `Texture` remains owned by the `Skin`; a failed texture upload must not leak either native resource.

### CSS coordinates, bounds, and cascade — #11, #20, #21

Replace coordinate-destroying comment stripping and rule-index diagnostics with a single bounded scan that retains one-based source line and column for rule, selector, property, and value failures. Keep `ruleIndex` solely as the source-order tie breaker.

In addition to existing byte, rule, and declaration caps, enforce explicit selector length, selectors-per-group, total selectors, and total cascade-comparison limits. Reject over-limit stylesheets with typed `TOO_LARGE` diagnostics before unbounded splitting, rescanning, or sorting. Resolve immutable styles once per element/pseudo combination during a build rather than independently rescanning for layout, common attributes, and actor overrides.

For comma groups, inspect every matching selector and score the rule with the maximum matching specificity. Source order still breaks equal-specificity ties.

### Pseudo-state styles — #17, #18

Use one widget/property/pseudo mapping for tag, class, and ID selectors. Supported font-color states map to the corresponding libGDX style fields (`over`, `down`, `checked`, and `disabled` where the widget style provides them). Tagless `.class:pseudo` and `#id:pseudo` selectors are applied to each matched actor through a derived per-actor style; they are never silently ignored.

A selector/property/pseudo combination that cannot be represented by the target widget fails as a typed CSS error with the selector's true source coordinates. Base-state properties retain current behavior.

## Runtime Contracts

### Authoritative state — #9

Add explicit registration modes:

- bindings-only registration for applications that register authoritative domain properties separately;
- registration accepting an authoritative value-supplier resolver keyed by declared runtime entity/property;
- an explicitly named/documented widget-mirror convenience mode for preview, where no domain model exists.

Preserve the existing public convenience entry point by delegating to widget-mirror mode and documenting that it cannot detect UI/domain divergence. Production embedding guidance uses bindings-only or authoritative-supplier registration and never presents actor readback as domain truth.

### Transactional registration — #10

Preflight the entire document into an immutable registration plan before mutating `AgentRuntime`. Preflight validates entity count, IDs, properties, actor bindings, and supplier availability. During commit, retain every entity and binding handle immediately. On any exception, close acquired handles in reverse order, attach cleanup failures as suppressed exceptions, and leave runtime state equivalent to the pre-call state. A failed 257th entity or late binding must not poison the next registration attempt.

### Correlation status — #28

The current `AgentRuntimeObservationSource` cannot prove correlation when token/frame ordering is wrong and therefore returns no observation; `RuntimeComparator` reports `UNAVAILABLE`. Keep that status rather than inventing unreachable `STALE` or `UNCORRELATED` states. Add a bounded actionable missing-correlation reason where the local adapter/status surface permits it, naming token mismatch and drain-before-frame ordering as likely recovery checks. Align runtime tests, harness Javadoc, embedding guide, and ADR 0002. Every documented status must either be reachable through this adapter or explicitly identified as originating from another observation source.

## Preview and IDEA Lifecycle

### Deterministic render and screenshot — #6

Clear color and depth buffers to a fixed documented color before every stage draw. Read the framebuffer on the render thread and vertically flip rows before PNG encoding. Repeated unchanged renders must produce byte-identical normalized screenshots; an asymmetric fixture proves top/bottom orientation.

### Transactional rebuild — #7

A rebuild follows this order on the render thread:

1. bounded-read and parse candidate XML/CSS;
2. create candidate skin and actor tree without attaching it to the stage;
3. transactionally attach candidate runtime bindings;
4. replace stage content and publish success status;
5. close old runtime registration and dispose old skin only after the candidate is live.

If runtime attachment requires temporarily removing conflicting last-good IDs, re-register the retained last-good document/tree on candidate failure before returning an error. Transactional registration from #10 guarantees the failed candidate leaves no partial handles. Any losing candidate skin/tree is disposed; the last-good stage remains usable. Initial-build failure installs the error overlay independently of a successful build.

### Pre-allocation file limits — #16

Add bounded stream/path parse entry points for XML and CSS. Read at most the documented limit plus one byte, reject oversized input with typed `TOO_LARGE`, and decode UTF-8 only after the bounded byte read. Preview uses these entry points instead of `Files.readString`.

### Typed status schema — #22

Version `MarkupStatus` and serialize separate bounded fields: `schemaVersion`, `ok`, `kind`, `elementPath`, `line`, `column`, `message`, and `nodes`. Success omits error identity; error preserves the raw exception message without embedding path or coordinates in prose. IDEA parses the fields directly and does not parse a formatted status line. Compatibility tests cover the previous success/error JSON shapes where compatibility is retained.

### Artifact storage — #12

Each preview session creates a private, owner-only temporary directory. Artifact names use the full digest and writes use create-new semantics without following symbolic links. Enforce per-artifact and cumulative byte/count quotas before retention. Close deletes session artifacts and the directory; every write failure removes its temporary file. Published references remain transport-neutral.

### IDEA process ownership — #24

Extract a testable process owner from Swing UI code. It drains stdout and stderr concurrently, performs terminate→bounded wait→force-kill→final wait on a background executor, and preserves interrupt status. Reload schedules replacement without blocking the EDT. Register the panel/process owner as an IntelliJ `Disposable`; disposal stops watcher/executor/drains and leaves no child. Tests use injected fake/noisy/hung processes and observable latches rather than sleeps.

## Qualification Trust Boundary

### Manifest and path containment — #13, #15

Parse manifests through a bounded byte input with strict schema checks. Enforce caps for entry count, string length, and aggregate work. IDs use a conservative slug grammar. `markupFile` and `referenceFile` must be relative normalized paths contained by their designated corpus roots; absolute paths, traversal, and symbolic-link escapes fail before reads or writes. Output filenames derive only from validated IDs.

### Authenticated references — #14

Remote references require HTTPS, an explicit host allowlist, expected SHA-256 digest, media type, byte length, width, and height in the manifest. Revalidate every redirect target and reject user info, fragments, non-default authority tricks, DNS/address classes outside policy, or excessive redirects. Cache hits undergo the same identity checks as downloads. Update committed manifest identities from independently fetched valid references.

### Bounded image acquisition and decode — #5

Stream response bodies into owner-only temporary files while counting bytes. Stop at limit+1 and retain no oversized file. Validate response media type and manifest identity before atomically installing the cache entry. Use ImageIO metadata readers to inspect dimensions and total pixels before full decode. Reject excessive width, height, or pixel count; decode/subsample only to the bounded analysis resolution. Valid references produce deterministic scores.

### Total work and child lifecycle — #15

Use one monotonic deadline for the entire qualification run and derive each entry/child allowance from remaining time. Bound total decoded pixels and scored cells across the manifest. Own process drain tasks and terminate children with graceful wait followed by forced termination and final wait. Preserve interrupt status and join/cancel drains before returning. Report a typed bounded failure for the affected entry without orphaning work.

## Visual-Fidelity Qualification

### Signals — #27

Retain the existing dilated-region Dice as `coarseLayout` for diagnosis, not as the sole pass gate. At a bounded normalized analysis size, compute:

- `geometry`: Dice overlap of thresholded gradient/edge cells;
- `color`: intersection of fixed-size quantized RGB histograms, normalized to `[0,1]`;
- `detail`: grid-local high-frequency and edge-orientation histogram similarity, sensitive to glyph shape, text spacing, and fine decoration.

Each component has its own committed threshold. Strict qualification passes only when every required component meets its threshold; a weighted average cannot hide a failed dimension. The report schema is versioned and records every score, threshold, cell/density diagnostic, and verdict.

### Calibration

Calibrate thresholds from committed/local positive fixtures and deterministic deliberate negatives:

- vertical flip must fail geometry/detail;
- fixed translation/misalignment must fail geometry;
- hue shift must fail color;
- text/detail blur or scale change must fail detail.

Threshold generation uses a documented safety margin below positive scores but above the strongest deliberate-negative score. Strict CI uses reproducible local references and never silently recalibrates.

## Dependency Verification and Locking — #26

Enable dependency locking for every resolvable configuration in all six modules and commit the generated lock state. Commit Gradle verification metadata covering plugins, buildscript artifacts, compile/runtime dependencies, test dependencies, native artifacts, and release-only configurations with strict checksum/PGP verification. CI and release commands use strict verification and fail on missing metadata or lock drift. The Gradle wrapper checksum remains enforced.

Generate verification metadata only after other PRs stabilize the dependency graph. Review metadata additions against declared repositories and coordinates; never bootstrap trust from an unreviewed mirror.

## Compatibility and Documentation

0.3.0 is a feature/minor release. Existing public convenience APIs remain where their behavior is safe, but new production guidance selects explicit authoritative or bindings-only runtime registration. Status and qualification JSON schemas increment their version and retain bounded compatibility parsing where required by the IDEA/plugin boundary. Lasting changes to qualification scoring, correlation semantics, and process ownership update their ADRs or add an ADR.

Release notes list all fixed issues, public API additions, schema changes, security hardening, compatibility expectations, and the three Maven coordinates. Installation examples move to 0.3.0 only after the coordinates resolve publicly.

## Acceptance and Verification

Every issue's live acceptance criteria are mandatory. Verification proceeds from narrow to broad:

1. GL-free core parser/CSS tests:
   `./gradlew :libgdx-ui-markup:test --tests '*MarkupParserTest' --tests '*CssTest' --warning-mode=fail`
2. Core render-thread builder/resource tests:
   `xvfb-run -a ./gradlew :libgdx-ui-markup:test --tests '*MarkupBuilderTest' --warning-mode=fail`
3. Runtime and harness tests:
   `xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test :libgdx-ui-markup-harness:test --warning-mode=fail`
4. Preview unit tests and deterministic success/error smoke:
   `xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test --warning-mode=fail`
   followed by the documented `--frames --screenshot --exit` success and typed-error invocations.
5. IDEA lifecycle tests and plugin build:
   `./gradlew :libgdx-ui-markup-idea:unitTest :libgdx-ui-markup-idea:buildPlugin --warning-mode=fail`
6. Qualification focused tests and strict positive/negative gate:
   `xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test -PstrictQualification=true --warning-mode=fail`
7. Full repository gate:
   `xvfb-run -a ./gradlew clean check javadoc publishToMavenLocal --warning-mode=fail`
8. Release candidate gate:
   `xvfb-run -a ./gradlew -Prelease=true -PreleaseVersion=0.3.0 clean check javadoc centralBundle --warning-mode=fail`

The release is complete only when all issue PRs and the release-notes PR are merged, every issue is closed, the signed `v0.3.0` tag points at the reviewed candidate commit, the release workflow succeeds for that tag, Maven Central reports `PUBLISHED`, all three public coordinates resolve with POM/main/sources/Javadoc/signatures, and the GitHub release exists from the immutable tag.