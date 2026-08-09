# Markup 0.4.1 Interoperability Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish markup 0.4.1 with current/minimum ecosystem qualification and authoritative harness/runtime evidence.

**Architecture:** Keep adapters owned by markup and keep core independent. Select an exact `current` or `minimum` ecosystem profile at Gradle configuration time, with profile-specific locks, while the existing markup harness test host supplies real render-thread and MCP evidence.

**Tech Stack:** Java 25, Gradle 9.6.1 Kotlin DSL, libGDX 1.14.2, harness 1.1/1.2, agent-runtime 1.0/2.0, JUnit, LWJGL3/Xvfb.

## Global Constraints

- Published core must not depend on harness or agent-runtime.
- Current versions are harness 1.2.0 and agent-runtime 2.0.0; minimum versions are 1.1.0 and 1.0.0.
- Dependency verification stays strict; CI never writes locks or verification metadata.
- Runtime comparison evidence must use application-authoritative values for correctness claims.
- All Actor/Stage work stays on the render thread and all failures stay typed and bounded.

---

### Task 1: Exact ecosystem profiles and lock contract

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `scripts/test-ecosystem-profile.py`
- Create: `scripts/verify-ecosystem-profile.py`
- Modify: module `gradle.lockfile` files
- Modify: `gradle/verification-metadata.xml`

**Interfaces:**
- Consumes: Gradle property `ecosystemProfile=current|minimum`.
- Produces: exact profile resolution and root tasks `currentEcosystemTest` and `minimumEcosystemTest`.

- [ ] **Step 1: Write the failing profile contract tests**

Create Python unit tests that invoke the verifier against temporary catalogs and assert rejection
of an unknown profile, a dynamic version, a current profile other than harness 1.2.0/runtime 2.0.0,
and a minimum profile other than harness 1.1.0/runtime 1.0.0. Assert the real repository currently
fails because profiles and root tasks do not exist.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test-ecosystem-profile.py`

Expected: failure naming missing `ecosystemProfile` support/current and minimum tasks.

- [ ] **Step 3: Implement profile selection minimally**

Define both exact version sets in settings/build configuration, default to `current`, fail closed on
other names, choose profile-specific lock files, and register `currentEcosystemTest` plus
`minimumEcosystemTest` as isolated nested Gradle builds running the harness/runtime tests under the
selected profile. Do not use version ranges or network lookups.

- [ ] **Step 4: Regenerate reviewed dependency evidence**

Run the current and minimum resolution surfaces with `--write-locks` and
`--write-verification-metadata sha256,pgp`, inspect every new teemuki8 component/signature, and keep
only expected coordinate changes.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
python3 scripts/test-ecosystem-profile.py
./gradlew resolveAndLockAll -PecosystemProfile=current --warning-mode=fail
./gradlew resolveAndLockAll -PecosystemProfile=minimum --warning-mode=fail
```

- [ ] **Step 6: Commit**

Commit message: `build: add locked ecosystem compatibility profiles`

### Task 2: Authoritative current-stack harness proof

**Files:**
- Modify: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java`
- Create: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/AuthoritativeMarkupProcess.java`
- Create: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/AuthoritativeMarkupTestApp.java`
- Modify: `libgdx-ui-markup-harness/build.gradle.kts`

**Interfaces:**
- Consumes: `MarkupRuntimeSource.registerAuthoritative`, `HarnessSemanticSink`, harness protocol/MCP.
- Produces: a real-process test session whose application-owned value can diverge from displayed text.

- [ ] **Step 1: Write the failing E2E**

Add `authoritativeMarkupValueReportsEqualThenMismatchThroughMcp()`: start the test app, query the
markup text field, compare `EQUAL`, change only the domain supplier through a bounded test command,
advance/correlate a frame, then assert `ui_runtime_compare` returns `MISMATCH` with entity `user`,
property `value`, the unchanged displayed value, and the changed runtime value.

- [ ] **Step 2: Verify RED**

Run:
`xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test --tests '*MarkupHarnessEndToEndTest.authoritativeMarkupValueReportsEqualThenMismatchThroughMcp' -PecosystemProfile=current --warning-mode=fail`

Expected: failure because the authoritative MCP fixture does not exist.

- [ ] **Step 3: Implement the minimum fixture**

Reuse the existing bounded process/client conventions. Build from the existing sample XML/CSS with
`HarnessSemanticSink`; register an `AtomicReference<String>` through
`registerAuthoritative`; record the same correlation token after each rendered frame; expose only
the finite test control needed to change that reference. Close the source before runtime and Stage.

- [ ] **Step 4: Verify GREEN and regression scope**

Run:

```bash
xvfb-run -a ./gradlew :libgdx-ui-markup-harness:test \
  -PecosystemProfile=current --warning-mode=fail
xvfb-run -a ./gradlew currentEcosystemTest --warning-mode=fail
```

- [ ] **Step 5: Commit**

Commit message: `test: prove authoritative markup runtime through harness MCP`

### Task 3: Minimum compatibility execution

**Files:**
- Modify: `libgdx-ui-markup-harness/src/test/java/dev/gdx/markup/harness/MarkupHarnessEndToEndTest.java`
- Modify: `libgdx-ui-markup-runtime/src/test/java/dev/gdx/markup/runtime/MarkupRuntimeSourceTest.java`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: CI jobs that execute rather than merely resolve both profile graphs.

- [ ] **Step 1: Write failing workflow/profile assertions**

Extend `scripts/test-ecosystem-profile.py` to require two CI invocations and to require current
profile authoritative E2E plus minimum profile semantic/correlation tests.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test-ecosystem-profile.py`

Expected: failure because CI has no matrix invocations.

- [ ] **Step 3: Add the two exact CI lanes**

Run `minimumEcosystemTest` and `currentEcosystemTest` under Xvfb in separate named steps. Retain
test reports on failure. Do not regenerate dependency state in CI.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
python3 scripts/test-ecosystem-profile.py
xvfb-run -a ./gradlew minimumEcosystemTest currentEcosystemTest --warning-mode=fail
```

- [ ] **Step 5: Commit**

Commit message: `ci: qualify minimum and current ecosystem stacks`

### Task 4: 0.4.1 public contract and release documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/guides/embedding.md`
- Modify: `docs/maintainers/releasing.md`
- Create: `docs/releases/v0.4.1.md`
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Produces: documentation and release workflow matching the executable gates.

- [ ] **Step 1: Add failing documentation/version assertions**

Extend the profile verifier to require the current/minimum table, 0.4.1 release note, authoritative
wording, exact Central coordinates, and both ecosystem tasks in the release workflow.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test-ecosystem-profile.py`

- [ ] **Step 3: Update documentation and release workflow**

Document 0.4.1/current/minimum separately, remove stale “current 1.1.0/1.0.0” claims, preserve the
widget-mirror warning, and require both ecosystem tasks before `centralBundle` publication.

- [ ] **Step 4: Verify GREEN and full candidate**

Run:

```bash
python3 scripts/test-ecosystem-profile.py
xvfb-run -a ./gradlew clean check javadoc minimumEcosystemTest \
  currentEcosystemTest --warning-mode=fail
```

- [ ] **Step 5: Commit**

Commit message: `docs: prepare markup 0.4.1 release`

### Task 5: Review, publish, and verify 0.4.1

**Files:**
- Review all branch changes and generated release artifacts.

- [ ] **Step 1: Run the exact candidate verification**

Run the specification's full verification ladder on the release commit, including strict visual
qualification and IDEA plugin build. Build the signed Central bundle through the release workflow.

- [ ] **Step 2: Request code review and remediate findings**

Review dependency direction, profile isolation, authoritative ownership, thread confinement,
cleanup, bounds, workflow safety, and public compatibility. Re-run affected gates after fixes.

- [ ] **Step 3: Merge and reconcile main**

Merge the reviewed branch only after exact-head checks pass; fast-forward local `main` to the remote
merge commit.

- [ ] **Step 4: Tag and publish**

Create/push `v0.4.1`, monitor the release workflow through Central publication, and create/verify
the GitHub release for that immutable tag.

- [ ] **Step 5: Verify public coordinates**

Resolve the three 0.4.1 artifacts from Maven Central in a clean consumer cache and record the public
POM/signature/result evidence before beginning harness 1.2.1.
