# Runtime Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issues #9, #10, and #28 with explicit authoritative-state modes, atomic registration, and truthful correlation diagnostics.

**Architecture:** Preflight immutable registration plans before touching `AgentRuntime`; separate actor binding from value authority. Preserve the existing widget-mirror convenience API as explicitly non-authoritative while adding production-grade bindings-only and supplied-value APIs.

**Tech Stack:** Java 25, agent-runtime 0.16.0, libGDX Scene2D, JUnit 5, Gradle Wrapper, Xvfb.

## Global Constraints

- Consume `ElementPathTracker` from the merged core-semantics PR.
- Every actor read/registration occurs on the render thread.
- Rollback leaves the external runtime observationally unchanged.
- Missing correlation through `AgentRuntimeObservationSource` remains `UNAVAILABLE`, with actionable bounded diagnostics.

---

### Task 1: Preflight Registration Plan (#10)

**Files:**
- Modify: `libgdx-ui-markup-runtime/src/main/java/dev/gdx/markup/runtime/MarkupRuntimeSource.java`
- Test: `libgdx-ui-markup-runtime/src/test/java/dev/gdx/markup/runtime/MarkupRuntimeSourceTest.java`

**Interfaces:**
- Produces internal immutable `RegistrationPlan`/`PlannedBinding` values containing entity ID, property ID, actor, and optional supplier.
- Consumes core `ElementPathTracker` for consistent diagnostics.

- [ ] **Step 1: Add failing no-mutation tests**

Cover a missing late actor, duplicate/invalid late entity, 257th entity, and injected binding-registration failure. After each failure assert zero candidate entities/bindings remain and an immediately corrected registration succeeds.

- [ ] **Step 2: Run runtime test red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test --tests '*MarkupRuntimeSourceTest' --warning-mode=fail`

Expected: early registrations leak or block the corrected retry.

- [ ] **Step 3: Split preflight from commit**

Walk the complete immutable document/tree without mutating runtime. Validate limits, IDs, actor correspondence, property IDs, and supplier resolution into `List.copyOf` plans. Use `ElementPathTracker.enter/current/exit` and remove runtime's global sibling counter.

- [ ] **Step 4: Commit with reverse rollback**

Acquire each entity/binding handle and append it immediately. On failure close acquired handles in reverse order, attach cleanup failures with `addSuppressed`, and rethrow the original typed failure. Construct/return `MarkupRuntimeSource` only after full commit.

- [ ] **Step 5: Run tests green and commit**

Run the Step 2 command. Commit message: `fix: make runtime registration transactional`

### Task 2: Explicit Runtime Authority Modes (#9)

**Files:**
- Modify: `libgdx-ui-markup-runtime/src/main/java/dev/gdx/markup/runtime/MarkupRuntimeSource.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewMcp.java`
- Test: `libgdx-ui-markup-runtime/src/test/java/dev/gdx/markup/runtime/MarkupRuntimeSourceTest.java`
- Test: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java`
- Modify: `docs/guides/embedding.md`
- Modify: `README.md`

**Interfaces:**
- Produce `registerBindings(AgentRuntime, Document, BuiltUi, String)`.
- Produce `registerAuthoritative(AgentRuntime, Document, BuiltUi, String, RuntimeValueResolver)`.
- Produce `registerWidgetMirror(AgentRuntime, Document, BuiltUi, String)`.
- Define `@FunctionalInterface RuntimeValueResolver` resolving `(entityId, propertyId, Actor)` to a non-null `Supplier<RuntimeValue>` or an equivalent public value-source interface matching existing agent-runtime types.
- Existing `register(...)` delegates to and documents widget-mirror mode for 0.2.x compatibility.

- [ ] **Step 1: Add failing authority tests**

Assert bindings-only installs UI correlations without property suppliers; authoritative registration reports a mismatch when supplied domain value differs from the widget; widget-mirror still tracks live widgets but is named/documented non-authoritative; a missing authoritative supplier fails during preflight without mutation.

- [ ] **Step 2: Run tests red**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test :libgdx-ui-markup-harness:test --warning-mode=fail`

- [ ] **Step 3: Implement modes on one planning pipeline**

Use a private authority strategy to decide whether planned properties carry no supplier, a resolver supplier, or `valueOf(actor)`. Do not duplicate traversal/commit logic. Preview calls `registerWidgetMirror` explicitly.

- [ ] **Step 4: Update compilable guidance**

Show production domain registration separately from `HarnessSemanticSink` actor bindings. State that widget mirror validates transport/correlation only and cannot detect UI/domain divergence.

- [ ] **Step 5: Run tests/Javadoc green and commit**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:check :libgdx-ui-markup-harness:check :libgdx-ui-markup-runtime:javadoc --warning-mode=fail`

Commit message: `feat: separate runtime bindings from value authority`

### Task 3: Truthful Correlation Status (#28)

**Files:**
- Modify: `libgdx-ui-markup-harness/src/main/java/dev/gdx/markup/harness/HarnessSemanticSink.java`
- Modify: `docs/guides/embedding.md`
- Modify: `docs/adr/0002-runtime-compare-correlation.md`
- Test: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java`
- Test: `libgdx-ui-markup-runtime/src/test/java/dev/gdx/markup/runtime/MarkupRuntimeSourceTest.java`

**Interfaces:**
- Preserve comparator status `UNAVAILABLE` for absent adapter observations.
- If the local status/diagnostic model supports a message, emit a bounded reason naming token equality and drain-before-frame ordering checks; do not alter external agent-runtime SPI without source evidence.

- [ ] **Step 1: Add failing mismatch/order tests**

Create a token mismatch and reversed drain/frame-order scenario through `AgentRuntimeObservationSource`. Assert the actual status is `UNAVAILABLE`, the diagnostic is actionable where exposed, and neither scenario claims `STALE`/`UNCORRELATED` through this source.

- [ ] **Step 2: Run tests red against current documentation/expectations**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:test :libgdx-ui-markup-harness:test --warning-mode=fail`

- [ ] **Step 3: Align code comments, Javadoc, guide, and ADR**

Document reachable states per source. The recovery text must instruct consumers to verify the same correlation token in `HarnessSemanticSink` and `UiFrameCorrelation`, then drain observations before advancing the frame.

- [ ] **Step 4: Run documentation-bearing module gates and commit**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:check :libgdx-ui-markup-harness:check :libgdx-ui-markup-harness:javadoc --warning-mode=fail`

Commit message: `docs: align runtime correlation statuses`

### Task 4: Runtime PR Verification

- [ ] **Step 1: Run affected suite**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-runtime:check :libgdx-ui-markup-harness:check :libgdx-ui-markup-preview:check javadoc --warning-mode=fail`

- [ ] **Step 2: Verify compatibility and callsites**

Use LSP references for every added/changed exported registration symbol. Confirm existing `register` callers compile, preview selects widget mirror explicitly, and production examples select bindings-only or authoritative mode.

- [ ] **Step 3: Create PR**

Create a ready issue-only PR with `Fixes #9`, `Fixes #10`, and `Fixes #28`, exact tests, root cause, rollback invariant, and 0.3.0 API notes.