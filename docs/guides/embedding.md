# Embedding libgdx-ui-markup in a game

This guide covers production wiring: markup inside an application-owned game (as opposed to the
preview app). By the end you have a render-thread-built Scene2D UI whose markup-declared
`data-runtime-entity` elements are bound into the harness semantics facade and the agent
runtime, with an explicitly chosen value authority, provably frame-correlated through the
harness `ui_runtime_compare` tool.

The reference implementation is the preview (`libgdx-ui-markup-preview`,
`dev.gdx.markup.preview.PreviewMcp`); the compilable proof is the harness end-to-end test
(`libgdx-ui-markup-harness`, `MarkupHarnessEndToEndTest`), which launches the preview with
`--mcp` and drives `ui_runtime_compare` to `EQUAL` through the real MCP protocol. ADR 0002
records the design decision behind the correlation contract.

## Dependencies

All coordinates are published artifacts. The current tested stack is markup 0.4.1, harness 1.2.0,
and agent-runtime 2.0.0; the independently executed minimum compatibility profile uses harness
1.1.0 and agent-runtime 1.0.0.

| Coordinate | Version | Provides |
|---|---|---|
| `io.github.teemuki8:libgdx-ui-markup` | 0.4.1 | `MarkupBuilder`, parser, CSS engine, `DefaultSkin`, exact-size FreeType manager |
| `io.github.teemuki8:libgdx-ui-markup-harness` | 0.4.1 | `HarnessSemanticSink` |
| `io.github.teemuki8:libgdx-ui-markup-runtime` | 0.4.1 | `MarkupRuntimeSource` |
| `io.github.teemuki8:harness-core` / `harness-scene2d` / `harness-lwjgl3` | 1.2.0 | `RuntimeComparator`, `Scene2dSession`, `RenderThreadScheduler`, `ControlledStageClock`, capture |
| `io.github.teemuki8:harness-agent-runtime` | 1.2.0 | `AgentRuntimeObservationSource` |
| `io.github.teemuki8:harness-protocol` / `harness-mcp` | 1.2.0 | `HarnessProtocolService`, `HarnessMcpServer` (only if you serve MCP) |
| `io.github.teemuki8:agent-runtime-core` | 2.0.0 | `AgentRuntime`, `UiFrameCorrelation` |

Requires Java 25 and libGDX 1.14.2 (the harness backend). The preview distribution also needs
`gdx-backend-lwjgl3` plus the desktop natives; a game with its own backend adapts the harness
`lwjgl3` pieces accordingly. Desktop embedding also needs `gdx-freetype` and
`gdx-freetype-platform:1.14.2:natives-desktop`; the markup artifact declares them on its
runtime path.

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
Skin skin = DefaultSkin.create(rasterScale);
BuiltUi ui = MarkupBuilder.build(document, css, skin, sink);
ui.root().setSize(stage.getViewport().getWorldWidth(), stage.getViewport().getWorldHeight());
stage.addActor(ui.root());
```

Size the root group to the viewport: harness actionability tests parent intersection and
`Group.hit`, so a zero-sized root rejects every actor (the preview does this explicitly).

## 2. Own fonts and accessibility reflow with the Skin

Parsing XML/CSS remains GL-free. Creating or installing the font manager, building actors,
rebuilding after a density/accessibility change, and disposing the Skin are render-thread work.
`font-size` is an integer from 4 through 256 in XML and the same integer with optional `px` in
CSS. XML `font` and `font-size` each override their CSS value independently.

`DefaultSkin.create(rasterScale)` bundles Inter. For an application Skin, register only
application-owned font handles; markup cannot read files:

```java
Skin skin = new Skin(Gdx.files.internal("ui/skin.json"));
FreeTypeFontManager.install(
        skin,
        "game-ui",
        Map.of("game-ui", Gdx.files.internal("fonts/GameUi-Regular.ttf")),
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,:;!?-",
        rasterScale);
```

Compute `rasterScale` from the larger backbuffer/logical-window ratio and clamp it to 1–4.
The manager rasterizes physical pixels at that density and scales font metrics back to logical
units, so layout does not grow merely because the monitor is HiDPI. When an accessibility
setting changes the requested logical size, rebuild the markup actor tree and let Table/Cell
layout reflow. Keep the manager attached to the Skin: disposing the Skin releases generated
fonts and the manager releases its FreeType generators. A failed candidate rebuild must dispose
only its candidate Skin, leaving the committed Skin live.

## 3. Register runtime bindings and value authority separately

A `data-runtime-entity` element declares two independent contracts: the **actor binding**
(which entity property the actor's control id correlates with) and the **value authority**
(where the entity property's runtime value comes from). The `HarnessSemanticSink` in step 1
handles the semantic facade; `MarkupRuntimeSource` handles the runtime side. Registration reads
live actors, so it runs on the render thread, after the build. Choose the authority explicitly;
the 0.2.x `register(...)` convenience delegates to widget-mirror mode.

### Authoritative mode — production domain state

Register the entity with your own supplier, resolved once per `data-runtime-entity` element
during registration. The resolver runs on the render thread and must return a non-null
`Supplier<RuntimeValue>` for every entity; returning `null` fails registration during preflight
with a located `MarkupException` and no runtime mutation:

```java
AgentRuntime runtime = AgentRuntime.builder()
        .sessionId(SessionId.of(APP_SESSION_ID))
        .build();
runtime.start();                       // the runtime owns its capture thread

MarkupRuntimeSource runtimeSource = MarkupRuntimeSource.registerAuthoritative(
        runtime, document, ui, APP_SESSION_ID,
        (entityId, propertyId, actor) -> () -> RuntimeValues.string(domainValue(entityId)));
```

The entity property now reports the domain model, not the widget. `ui_runtime_compare` returns
`EQUAL` when the widget displays that value and `MISMATCH` when it does not — that is the
divergence detection production wants. The supplier is evaluated by the agent-runtime capture
thread on every frame, so it must be thread-safe or capture stable state.

### Bindings-only mode — application-registered entities

If the application already registers the entity (a shared domain service or another value
source), markup needs only the correlation:

```java
MarkupRuntimeSource runtimeSource =
        MarkupRuntimeSource.registerBindings(runtime, document, ui, APP_SESSION_ID);
```

This installs the `UiBinding` per `data-runtime-entity` element without registering an entity or
widget supplier (`registeredEntities()` is empty). The comparison reports `MISSING` until the
application registers the referenced entity.

### Widget-mirror mode — preview convenience, explicitly non-authoritative

`registerWidgetMirror(...)` (which the 0.2.x `register(...)` delegates to) reads the widget's
live state back as the property value; the preview selects it explicitly. It validates
transport and correlation plumbing only: `EQUAL` proves the pipeline, not the data, and it
**cannot detect a UI displaying the wrong model value**. Do not present actor readback as domain
truth.

```java
MarkupRuntimeSource runtimeSource = MarkupRuntimeSource.registerWidgetMirror(
        runtime, document, ui, APP_SESSION_ID);
```

On a UI rebuild, close the old source before registering the new actor tree in any mode:

```java
runtimeSource.close();
runtimeSource = MarkupRuntimeSource.registerAuthoritative(
        runtime, newDocument, rebuiltUi, APP_SESSION_ID,
        (entityId, propertyId, actor) -> () -> RuntimeValues.string(domainValue(entityId)));
```

The preview prints a bounded registration line naming the mode, e.g.
`markup-runtime: {"mode":"widget-mirror","entities":1,"bindings":1}`.

## 4. Record one UiFrameCorrelation per rendered frame

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
silent at compile time but not at runtime: `AgentRuntimeObservationSource` can prove no frame
for the binding, so `ui_runtime_compare` reports `UNAVAILABLE` — never `STALE`/`UNCORRELATED`
through this source. The recovery is to record every frame's correlation under the exact token
passed to the sink (the sink's Javadoc and the statuses section below name the same checks).
Choose one stable application-scoped value and never change it without re-recording.

## 5. Serve ui_runtime_compare on the render-thread scheduler

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

## 6. The loop order that makes EQUAL achievable

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
correlation: the source still proves the older frame, so the comparison degrades to `STALE`
(never `UNCORRELATED` through this source). If instead the correlation is recorded against a
frame that is no longer the latest — or the token mismatches — the source can prove nothing
and the comparison is `UNAVAILABLE`. This loop order is the single most common wiring bug.

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
or state problem. Through `AgentRuntimeObservationSource` (the adapter this guide wires), an
observation exists only when the correlation is provable, so the comparator reports
`UNAVAILABLE` for correlation problems, `STALE` only for a snapshot ahead of the proven frame,
and never `UNCORRELATED`:

| Status | Meaning | Common cause |
|---|---|---|
| `EQUAL` | displayed value equals the runtime value on a proven frame | — |
| `MISMATCH` | displayed value differs from the runtime value on a proven frame | state changed between snapshot and correlation |
| `STALE` | observation proven for an older frame than the snapshot | loop order wrong (advance before drain), or clock not deterministic |
| `UNCORRELATED` | no provable frame | not reachable through `AgentRuntimeObservationSource`: its observations always carry a proven frame; a clock-based source (no strict correlation) may emit it |
| `MISSING` | actor has no runtime binding | `data-runtime-entity` absent, or build ran with a `NoopSink` |
| `UNAVAILABLE` | the adapter emits no observation for the binding | token mismatch between `HarnessSemanticSink` and `UiFrameCorrelation`; no correlation recorded for the latest frame (correlation recording lagging the frame capture); `AgentRuntime` not started, or source not wired |
| `AMBIGUOUS` | locator matched multiple actors | markup ids not unique |

**Recovery.** When `ui_runtime_compare` reports `UNAVAILABLE` for a bound actor, verify that
the exact correlation token passed to `HarnessSemanticSink` equals the
`UiFrameCorrelation.correlationToken()` recorded for each rendered frame, then drain
observations before advancing the frame (step 5). The runtime's `framesForUiSession` lists
which correlations the session actually recorded and under which token; the binding's token is
the one passed to `HarnessSemanticSink`.

## Reference implementations

- `dev.gdx.markup.preview.PreviewMcp` — the complete wiring above, in production shape
  (session, sink, clock, scheduler, runtime, coordinator, server).
- `MarkupHarnessEndToEndTest.markupRuntimeEntityComparesThroughHarnessMcp` — the compilable
  proof: a `data-runtime-entity` textfield filled through the real input path compares
  `EQUAL` with `entityId=user`, `propertyId=value` over the MCP protocol.
- `docs/adr/0002-runtime-compare-correlation.md` — the decision and consequences behind the
  correlation contract.
