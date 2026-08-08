# Embedding libgdx-ui-markup in a game

This guide covers production wiring: markup inside an application-owned game (as opposed to the
preview app). By the end you have a render-thread-built Scene2D UI whose markup-declared
`data-runtime-entity` widgets are agent-runtime value sources, bound into the harness semantics
facade, and provably frame-correlated through the harness `ui_runtime_compare` tool.

The reference implementation is the preview (`libgdx-ui-markup-preview`,
`dev.gdx.markup.preview.PreviewMcp`); the compilable proof is the harness end-to-end test
(`libgdx-ui-markup-harness`, `MarkupHarnessEndToEndTest`), which launches the preview with
`--mcp` and drives `ui_runtime_compare` to `EQUAL` through the real MCP protocol. ADR 0002
records the design decision behind the correlation contract.

## Dependencies

All coordinates are published artifacts. Markup modules track the markup release (0.2.1 at the
time of writing); harness and agent-runtime are fixed dependencies of that release.

| Coordinate | Version | Provides |
|---|---|---|
| `io.github.teemuki8:libgdx-ui-markup` | 0.2.1 | `MarkupBuilder`, parser, CSS engine, `DefaultSkin` |
| `io.github.teemuki8:libgdx-ui-markup-harness` | 0.2.1 | `HarnessSemanticSink` |
| `io.github.teemuki8:libgdx-ui-markup-runtime` | 0.2.1 | `MarkupRuntimeSource` |
| `io.github.teemuki8:harness-core` / `harness-scene2d` / `harness-lwjgl3` | 1.1.0 | `RuntimeComparator`, `Scene2dSession`, `RenderThreadScheduler`, `ControlledStageClock`, capture |
| `io.github.teemuki8:harness-agent-runtime` | 1.1.0 | `AgentRuntimeObservationSource` |
| `io.github.teemuki8:harness-protocol` / `harness-mcp` | 1.1.0 | `HarnessProtocolService`, `HarnessMcpServer` (only if you serve MCP) |
| `io.github.teemuki8:agent-runtime-core` | 1.0.0 | `AgentRuntime`, `UiFrameCorrelation` |

Requires Java 25 and libGDX 1.14.2 (the harness backend). The preview distribution also needs
`gdx-backend-lwjgl3` plus the desktop natives; a game with its own backend adapts the harness
`lwjgl3` pieces accordingly.

## 1. Build the UI on the render thread with a live session

`MarkupBuilder.build` must run on the GL/render thread. It emits markup-declared semantics
(`testId`, `role`, `accessibleName`, `data-*`) through a `SemanticSink`; use
`HarnessSemanticSink` so the same build pass binds `data-runtime-entity` actors into the
harness `Semantics` facade. The sink needs a live `Scene2dSession` and your correlation token
(see step 4 for the token contract).

```java
// render thread
Scene2dSession session = new Scene2dSession(stage);
HarnessSemanticSink sink = new HarnessSemanticSink(session.semantics(), CORRELATION_TOKEN);

MarkupDocument document = new MarkupParser().parse(xml);
CssDocument css = new CssParser().parse(stylesheet);
BuiltUi ui = MarkupBuilder.build(document, css, DefaultSkin.create(), sink);
ui.root().setSize(stage.getViewport().getWorldWidth(), stage.getViewport().getWorldHeight());
stage.addActor(ui.root());
```

Size the root group to the viewport: harness actionability tests parent intersection and
`Group.hit`, so a zero-sized root rejects every actor (the preview does this explicitly).

## 2. Register runtime entities against the agent runtime

Every element with `data-runtime-entity` becomes an agent-runtime value source whose named
property (default `value`) reads the widget's live state, plus a `UiBinding` to the actor's
control id. Registration reads live actors, so it also runs on the render thread, after the
build:

```java
AgentRuntime runtime = AgentRuntime.builder()
        .sessionId(SessionId.of(APP_SESSION_ID))
        .build();
runtime.start();                       // the runtime owns its capture thread

MarkupRuntimeSource runtimeSource =
        MarkupRuntimeSource.register(runtime, document, ui, APP_SESSION_ID);
```

The source reports a bounded registration line, e.g.
`markup-runtime: {"entities":1,"bindings":1}` (the preview prints it to stderr). On a UI
rebuild, close the old source before registering the new actor tree:

```java
runtimeSource.close();
runtimeSource = MarkupRuntimeSource.register(runtime, newDocument, rebuiltUi, APP_SESSION_ID);
```

## 3. Record one UiFrameCorrelation per rendered frame

The harness proves frame equality by resolving each binding's correlation token against the
correlations the application records per rendered frame. Record exactly one correlation per
frame, on the render thread, after the frame's runtime snapshot is complete:

```java
// after the frame's beginFrame/endFrame on the render thread
runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
        runtime.currentEpoch(),
        runtime.latestFrame().orElseThrow().frameId(),
        APP_SESSION_ID,
        Optional.of(Long.toString(clock.frame())),   // harness frame id
        Optional.of(CORRELATION_TOKEN)));
```

### The correlation-token contract

The token passed to `HarnessSemanticSink` must **equal** the
`UiFrameCorrelation.correlationToken()` recorded per frame. The preview uses
`markup-preview-frame`; an application with its own correlation token must pass **its own**
token to the sink and record every frame's correlation under that same token. A mismatch is
silent: the wiring compiles, `ui_runtime_compare` runs, and returns `STALE`/`UNCORRELATED`
with no diagnostic naming the token. Choose one stable application-scoped value and never
change it without re-recording.

## 4. Serve ui_runtime_compare on the render-thread scheduler

`RuntimeComparator` is pure; it must run on the render thread where snapshots are taken. Wire
`AgentRuntimeObservationSource` to it and submit the comparison through the
`RenderThreadScheduler` so it executes during the next drain. The preview also advertises
`ui_runtime_compare` in the session's `CapabilitySet` and registers a
`RuntimeCompareCoordinator` on the `HarnessProtocolService.Session`:

```java
RuntimeObservationSource runtimeObservation =
        new AgentRuntimeObservationSource(runtime, APP_SESSION_ID);
RuntimeComparator runtimeComparator = new RuntimeComparator(runtimeObservation);

HarnessProtocolService.RuntimeCompareCoordinator runtimeCoordinator =
        (locator, deadline) -> scheduler.submit(
                () -> runtimeComparator.compare(
                        session.snapshot(clock.revision(), clock.frame()),
                        locator.toCore(), new StrictResolution()),
                deadline);

CapabilitySet capabilities = new CapabilitySet(List.of(
        "snapshot", "query", "action", "wait", "screenshot", "ui_runtime_compare"));
```

The harness serves the tool only to sessions declaring the capability; without it the MCP tool
answers `UNSUPPORTED_CAPABILITY`.

## 5. The loop order that makes EQUAL achievable

The comparator's snapshot frame must equal the last recorded correlation frame. That requires
a fixed loop order on the render thread:

```java
void beforeDraw() {          // start of frame
    scheduler.drain();       // 1. run pending scheduler commands (the comparator) first
    clock.advance(FIXED_STEP); // 2. only then advance the deterministic clock
}

void afterDraw() {           // end of frame
    // fence + runtime.beginFrame/endFrame + record UiFrameCorrelation (step 3)
}
```

Draining before advancing means the comparator runs against the clock frame recorded by the
*previous* frame's correlation — the snapshot it takes is exactly the correlated frame.
Reversing the order (advance, then drain) yields a snapshot one frame ahead of the recorded
correlation and degrades the comparison to `STALE`/`UNCORRELATED`. This is the single most
common wiring bug.

With the controlled clock, the clock drives `stage.act`; do not call `stage.act` separately in
the MCP path (the preview branches on this).

`WaitEngine` follows the same render-thread rule: its snapshot supplier is invoked on the
calling (MCP virtual) thread, so route it through the scheduler when it can be called
off-thread, blocking on the hop — a supplier that reads the Stage directly is a silent
confinement violation with no error. The preview's `snapshotForWait` does this (direct on the
render thread, `scheduler.submit(...).join()` otherwise); see the harness getting-started
guide's "Threading and frame wiring" section for the same wiring.

## Statuses and what they mean

`ui_runtime_compare` returns a typed status; treat any status other than `EQUAL` as a wiring
or state problem:

| Status | Meaning | Common cause |
|---|---|---|
| `EQUAL` | displayed value equals the runtime value on a proven frame | — |
| `MISMATCH` | displayed value differs from the runtime value on a proven frame | state changed between snapshot and correlation |
| `STALE` | correlation exists but is not provable for the snapshot frame | loop order wrong (advance before drain), or clock not deterministic |
| `UNCORRELATED` | no correlation matches the binding's token/frame | token mismatch between sink and `UiFrameCorrelation`, or no correlation recorded for the frame |
| `MISSING` | actor has no runtime binding | `data-runtime-entity` absent, or build ran with a `NoopSink` |
| `UNAVAILABLE` | observation source cannot observe | `AgentRuntime` not started, or source not wired |
| `AMBIGUOUS` | locator matched multiple actors | markup ids not unique |

## Reference implementations

- `dev.gdx.markup.preview.PreviewMcp` — the complete wiring above, in production shape
  (session, sink, clock, scheduler, runtime, coordinator, server).
- `MarkupHarnessEndToEndTest.markupRuntimeEntityComparesThroughHarnessMcp` — the compilable
  proof: a `data-runtime-entity` textfield filled through the real input path compares
  `EQUAL` with `entityId=user`, `propertyId=value` over the MCP protocol.
- `docs/adr/0002-runtime-compare-correlation.md` — the decision and consequences behind the
  correlation contract.
