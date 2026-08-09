# Markup 0.4.1 Interoperability and Release Gates Design

## Objective

Release `libgdx-ui-markup` 0.4.1 with deterministic authoring, rendering, harness, and
agent-runtime qualification against the current published teemuki8 stack. Keep the published
dependency direction optional: markup core knows neither the harness nor agent runtime, while
the markup-owned adapter modules connect those libraries explicitly.

## Scope

This release updates the harness baseline from 1.1.0 to 1.2.0 and the agent-runtime baseline
from 1.0.0 to 2.0.0, including dependency locks, verification metadata, public examples, and
release notes. It adds an explicit compatibility lane proving that the source remains usable
with the previous harness 1.1.0 and agent-runtime 1.0.0 floors. It does not add MSDF, a second
UI construction path, a new layout engine, or a dependency from markup core to either library.

## Architecture and dependency direction

The published graph remains:

```text
libgdx-ui-markup-harness -> libgdx-ui-markup + harness-scene2d
libgdx-ui-markup-runtime -> libgdx-ui-markup + agent-runtime-core
libgdx-ui-markup-preview -> both adapters and the complete harness/runtime development stack
```

`HarnessSemanticSink` remains the sole markup-to-harness semantic adapter. It emits declared
test identifiers, roles, accessible names, labels, properties, and runtime bindings during the
render-thread build. `MarkupRuntimeSource` remains the markup-to-runtime adapter. Production
examples and interoperability tests use `registerAuthoritative` or `registerBindings`; the
widget-mirror compatibility path is retained only as explicitly non-authoritative preview
convenience.

## Deterministic release gates

The release gate consists of:

1. GL-free XML/CSS/model tests with typed location-bearing failures.
2. Xvfb-backed render-thread builder and exact-size FreeType tests.
3. Preview success and typed-error smoke executions.
4. Strict deterministic visual recreation qualification.
5. Agent-runtime tests covering authoritative equality, authoritative mismatch, transactional
   registration, bounds, and lifecycle.
6. Harness MCP E2E covering declared semantics, strict locator resolution, real input, wait,
   screenshot, and correlated runtime comparison.
7. IDEA plugin build and public Javadocs.
8. Dependency locking, PGP/checksum verification, and Maven Central bundle validation.

Remote visual references retain their existing authenticated bounded cache contract. A strict
release run fails on an unavailable, mismatched, oversized, or invalid reference instead of
silently reducing corpus coverage.

## Compatibility lanes

Two named, deterministic lanes are required:

- `minimumEcosystemTest`: harness 1.1.0 plus agent-runtime 1.0.0.
- `currentEcosystemTest`: harness 1.2.0 plus agent-runtime 2.0.0.

Each lane has independently resolvable compile and runtime configurations recorded in dependency
locks. Versions are exact inputs; dynamic selectors such as `latest.release`, version ranges, or
unlocked substitutions are forbidden. The current lane executes the complete markup/harness
E2E. The minimum lane exercises the compatible semantic and correlation surface available at the
floor. Both lanes must compile and run, not merely resolve dependencies.

## Authoritative interoperability proof

The current published-artifact path must prove:

```text
XML/CSS -> MarkupBuilder -> BuiltUi + HarnessSemanticSink
        -> Scene2dSession -> harness MCP query/action/wait/screenshot
        -> MarkupRuntimeSource.registerAuthoritative
        -> AgentRuntimeObservationSource -> ui_runtime_compare
```

An application-owned value equal to the displayed value produces `EQUAL`. Deliberately changing
the authoritative value while leaving the widget unchanged produces `MISMATCH` with the exact
entity and property identifiers. Widget readback is not accepted as evidence for this gate.

## Diagnostics and failure policy

All new failures remain typed and bounded. A compatibility lane reports the exact coordinate and
configuration that failed. Interoperability failures retain the locator, semantic identity,
runtime entity/property identity, and correlation status where applicable. No gate disables
dependency verification, writes lock state during CI, guesses frame correlation, or retries by
sleeping.

## Documentation and versioning

README, embedding guidance, release notes, and dependency examples name markup 0.4.1, harness
1.2.0, and agent-runtime 2.0.0 as the current tested stack. The minimum compatibility floor is
documented separately so an old baseline cannot be mistaken for the recommended stack.

The three publishable coordinates are:

- `io.github.teemuki8:libgdx-ui-markup:0.4.1`
- `io.github.teemuki8:libgdx-ui-markup-harness:0.4.1`
- `io.github.teemuki8:libgdx-ui-markup-runtime:0.4.1`

Preview, qualification, and IDEA modules remain unpublished.

## Acceptance criteria

- Production module direction is unchanged and markup core has no harness/runtime dependency.
- Current and minimum ecosystem lanes both execute successfully from locked dependencies.
- The current E2E proves markup semantics, real harness input, screenshot capture, authoritative
  `EQUAL`, and an independently induced authoritative `MISMATCH`.
- Strict visual qualification and exact-size FreeType regression tests pass.
- Public documentation contains no claim that 1.1.0/1.0.0 are the current stack.
- The 0.4.1 Git tag and GitHub release point to the reviewed release commit.
- Maven Central publishes and resolves all three 0.4.1 coordinates with signatures and metadata.

## Exact verification

```bash
./gradlew :libgdx-ui-markup:test --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test --warning-mode=fail
xvfb-run -a ./gradlew minimumEcosystemTest currentEcosystemTest --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup-qualification:test \
  -PstrictQualification=true --warning-mode=fail
xvfb-run -a ./gradlew :libgdx-ui-markup-preview:run \
  --args='--ui samples/signin.xml --css samples/signin.css --frames 5 --screenshot build/signin.png --exit'
./gradlew :libgdx-ui-markup-idea:buildPlugin --warning-mode=fail
xvfb-run -a ./gradlew clean check javadoc centralBundle \
  -Prelease=true -PreleaseVersion=0.4.1 --warning-mode=fail
```

After publication, a clean consumer resolution must retrieve all three coordinates from Maven
Central and the downstream harness compatibility gate must consume 0.4.1 before harness 1.2.1 is
released.
