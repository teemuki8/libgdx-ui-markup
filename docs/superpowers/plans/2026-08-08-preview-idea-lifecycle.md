# Preview and IDEA Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix issues #6, #7, #12, #16, #22, and #24 with deterministic rendering, transactional reload, bounded storage/input, typed status, and owned IDEA processes.

**Architecture:** Keep parsing and status models GL-free; run build/swap/screenshot operations on the render thread. Make lifecycle ownership explicit through closeable preview resources and a testable non-EDT process owner.

**Tech Stack:** Java 25, libGDX LWJGL3, Kotlin/JBR 21 IDEA plugin, JUnit 5, Xvfb.

## Global Constraints

- Depend on merged runtime transactional registration.
- Never block the IDEA EDT or use sleeps for synchronization.
- Retain last-good actors/skin/runtime until a complete candidate succeeds.
- Bound all status strings, files, artifact counts, and storage bytes.

---

### Task 1: Bounded XML/CSS Path Parsing (#16)

**Files:**
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/MarkupParser.java`
- Modify: `libgdx-ui-markup/src/main/java/dev/gdx/markup/core/style/CssParser.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/MarkupParserTest.java`
- Test: `libgdx-ui-markup/src/test/java/dev/gdx/markup/core/style/CssTest.java`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`

**Interfaces:**
- Add bounded `parse(Path)` or `parse(InputStream)` overloads to XML and CSS parsers.
- Existing `parse(String)` remains and shares the same byte-limit implementation.

- [ ] Add tests proving limit+1 files fail `TOO_LARGE` before `Files.readString`, exact-limit UTF-8 files pass, and partial multibyte sequences fail deterministically.
- [ ] Run `./gradlew :libgdx-ui-markup:test :libgdx-ui-markup-preview:test --warning-mode=fail` and observe the oversized-path reproduction fail.
- [ ] Implement a bounded byte reader that reads at most maximum+1, then decodes UTF-8 strictly and delegates to the existing parser; use it from `PreviewApp.rebuild`.
- [ ] Run the focused tests green and commit `fix: enforce preview file limits before allocation`.

### Task 2: Versioned Typed Markup Status (#22)

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/MarkupStatus.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupStatusLine.kt`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/MarkupStatusTest.java`
- Test: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/MarkupStatusLineParserTest.kt`

**Interfaces:**
- `MarkupStatus` schema version 2 fields: `schemaVersion`, `ok`, `kind`, `elementPath`, `line`, `column`, `message`, `nodes`.
- Error factory accepts `MarkupException`; generic failures use a stable non-markup kind only if the existing schema requires it.

- [ ] Add failing JSON tests for success and a located `INVALID_VALUE` error; assert message excludes duplicated formatted path/coordinates and every string is bounded.
- [ ] Add IDEA parser tests consuming fields directly and rejecting unsupported future schema versions with an actionable panel message.
- [ ] Implement record validation/escaping/version output and replace `exception.formatted()` emission with raw typed fields.
- [ ] Run `./gradlew :libgdx-ui-markup-preview:test :libgdx-ui-markup-idea:unitTest --warning-mode=fail` and commit `feat: preserve typed preview status fields`.

### Task 3: Deterministic Frames and Screenshots (#6)

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`
- Add test fixture under: `libgdx-ui-markup-preview/src/test/resources/`

**Interfaces:** unchanged CLI; screenshots become top-left normalized PNGs.

- [ ] Add an asymmetric top/bottom fixture and a repeated unchanged-frame test. Assert orientation and byte-identical output after a prior larger/different render.
- [ ] Run `xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test --warning-mode=fail` and observe inversion/ghosting.
- [ ] Set fixed clear color, call `glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)` before draw, and flip framebuffer Pixmap rows exactly once before encoding.
- [ ] Run focused tests and the preview `--frames 5 --screenshot ... --exit` smoke twice; compare files; commit `fix: make preview screenshots deterministic`.

### Task 4: Transactional Rebuild and Runtime Attachment (#7)

**Files:**
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewApp.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewMcp.java`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewAppTest.java`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/PreviewMcpTest.java`

**Interfaces:**
- Add an internal candidate value owning document, CSS, skin, actor tree, and runtime registration until committed.
- `PreviewMcp.attachRuntime` either returns a live owner and preserves/reinstalls last-good registration on failure, or throws with no candidate handles.

- [ ] Add failing tests for bad edit after good build, initial bad build, candidate runtime collision/failure, and repeated recovery. Assert old skin remains undisposed while last-good actors are live, candidate resources are disposed, no runtime handles leak, and initial error overlay is visible.
- [ ] Run preview tests red under Xvfb.
- [ ] Refactor rebuild into prepare→runtime attach→stage swap→old dispose. On candidate failure dispose only candidate resources and publish typed error. If old IDs must be removed, restore the retained old registration before returning failure.
- [ ] Run preview/runtime tests green and commit `fix: make preview rebuild transactional`.

### Task 5: Private Quota-Bounded Artifact Storage (#12)

**Files:**
- Create: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/InMemoryArtifactPublisher.java`
- Delete: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/TmpDirArtifactPublisher.java`
- Modify: `libgdx-ui-markup-preview/src/main/java/dev/gdx/markup/preview/PreviewMcp.java`
- Test: `libgdx-ui-markup-preview/src/test/java/dev/gdx/markup/preview/InMemoryArtifactPublisherTest.java` (replaces `TmpDirArtifactPublisherTest.java`)

**Interfaces:**
- Publisher implements `AutoCloseable` and retains payloads only in bounded per-session memory, keyed by their full SHA-256 digest; `readBack(sha256)` resolves a published digest to a defensive copy in-process.
- Constructor accepts bounded per-file/count/total quotas for deterministic tests; production uses fixed safe defaults (16 MiB/file, 128 MiB total, 64 artifacts).

- [x] Add failing tests for opaque references, full-digest metadata, per-file/total/count quota, dedupe without extra quota, digest-collision rejection (injectable digest seam), defensive copies, concurrent publish accounting, close clearing, and no-filesystem-side-effects.
- [x] Implement in-memory retention with synchronized quota accounting and close that zeroizes/removes retained payloads and rejects later publish/readback.
- [x] Make `PreviewMcp.close` close the publisher on every path; run preview and harness suites and commit `fix: retain preview artifacts in memory (cutover from temp-directory storage)`.

> **Hosted report — task 5 (issue #12), 2026-08-08.** Decision: preview artifact retention is in-memory per session, not on disk. A pure-Java review proved that no cross-platform, identity-conditioned directory unlink exists: `SecureDirectoryStream.deleteDirectory(name)` is name-based (no JDK API conditions an unlink on the directory's inode/owner), `renameat2`/`RENAME_EXCHANGE` is not exposed by the JDK, and macOS/Windows lack the Linux-only primitive; the JDK Windows provider additionally returns a null `fileKey` (commit df1d559's ADS token workaround only papered over the unverifiable identity). Every filesystem approach therefore either races or fails closed by refusing cleanup. In-memory retention keeps the public `ArtifactReference.Publisher` contract and opaque `artifact:<128-bit digest prefix>` references, bounds bytes/count exactly, and removes the entire symlink/ACL/fileKey/platform surface. The publisher snapshots the caller's array exactly once before hashing, so concurrent caller-side mutation can never desynchronize the retained bytes from the reference digest (regression: `concurrentCallerMutationCannotCorruptTheRetainedPayload`, which fails the pre-fix hash-then-clone order). Evidence: `TmpDirArtifactPublisher` (directory identity, owner-only ACLs, `SecureDirectoryStream` cleanup, Windows `WindowsDirKey`) and its tests were deleted; `InMemoryArtifactPublisher` + `InMemoryArtifactPublisherTest` (16 tests) cover quotas, dedupe/collision, caller-mutation races, concurrency, defensive copies, close clearing, and no-disk behavior; the preview suite (`xvfb-run -a ./gradlew :libgdx-ui-markup-preview:test`) and the harness E2E (`xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test --rerun`, which now asserts opaque reference metadata and that the live session creates no OS temp entries) are green. In-process session readback is proven by the preview's `artifactPublishedDuringSessionReadsBackInProcess` E2E; the harness protocol has no artifact-read tool, so cross-process byte retrieval is intentionally not asserted.

### Task 6: IDEA Child Process Ownership (#24)

**Files:**
- Create: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/PreviewProcessOwner.kt`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupPreviewPanel.kt`
- Modify: `libgdx-ui-markup-idea/src/main/kotlin/dev/gdx/markup/idea/MarkupPreviewToolWindowFactory.kt`
- Test: `libgdx-ui-markup-idea/src/test/kotlin/dev/gdx/markup/idea/PreviewProcessOwnerTest.kt`

**Interfaces:**
- `PreviewProcessOwner` implements IntelliJ `Disposable` at the integration boundary and exposes non-blocking `replace(command)`/`dispose()` scheduling.
- Internal constructor accepts launcher, executor, monotonic timing/wait policy, and stream consumers for plain-JVM tests.

- [ ] Add fake-process tests for noisy stdout/stderr, graceful exit, hung force-kill, interrupted wait, replacement, and disposal. Assert calls originating on EDT return without waiting and all drain tasks terminate via latches.
- [ ] Run `./gradlew :libgdx-ui-markup-idea:unitTest --warning-mode=fail` red.
- [ ] Implement single-owner serialized lifecycle: drain both streams, destroy, bounded wait, destroyForcibly, final wait, preserve interrupt; register owner with `Disposer.register(project, owner)` and remove panel-side blocking waits.
- [ ] Run unitTest and `buildPlugin`; commit `fix: own IDEA preview processes off EDT`.

### Task 7: Preview/IDEA PR Verification

- [ ] Run `xvfb-run -a ./gradlew :libgdx-ui-markup:check :libgdx-ui-markup-runtime:check :libgdx-ui-markup-preview:check :libgdx-ui-markup-idea:unitTest :libgdx-ui-markup-idea:buildPlugin --warning-mode=fail`.
- [ ] Run preview success/error CLI smokes and verify typed JSON plus deterministic screenshot.
- [ ] Create a ready PR with `Fixes #6`, `#7`, `#12`, `#16`, `#22`, and `#24`, exact evidence, schema compatibility, resource ownership, and EDT proof.