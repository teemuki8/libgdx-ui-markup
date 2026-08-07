# ADR 0002 — Runtime values through the harness: typed frame-correlated comparison

Status: accepted · Date: 2026-08-07

## Context

Markup declares UI semantics by construction (ADR 0001); the same promise extends to runtime
state. An agent must be able to verify that what the UI displays matches the application's
actual runtime value — after a fill, does the displayed text equal the entity's live value, on
a provably correlated frame? The harness defines the `RuntimeBinding` +
`RuntimeComparator` + `RuntimeObservationSource` SPI and the `ui_runtime_compare` MCP tool
with typed statuses (`EQUAL`, `MISMATCH`, `STALE`, `UNCORRELATED`, `MISSING`, `UNAVAILABLE`,
`AMBIGUOUS`), but the adapter module (`harness-agent-runtime`) was unpublished until harness
1.1.0, so the tool could not be served. Before the release, `libgdx-ui-markup-runtime` had to
fall back to agent-runtime's own correlation surface and an in-process value assertion (see the
ADR 0001 follow-up section, now superseded).

## Decision

Wire the final hop of the three-library story (markup ↔ agent-runtime ↔ harness) using the
harness's own correlation surface, mirroring the harness reference fixture:

1. **Value registration stays agent-runtime-side.** `MarkupRuntimeSource` (runtime module)
   registers every `data-runtime-entity` element as an agent-runtime entity whose property
   supplier (default `value`) reads the widget's live state, plus a `UiBinding` mapping the
   entity property to the actor's control id. The runtime module contains no harness types.
2. **Harness binding by construction.** `HarnessSemanticSink` (harness module) observes the
   same `data-runtime-entity` / `data-runtime-property` attributes during the build pass and
   binds the actor through `Semantics.bind(actor, new RuntimeBinding(entityId, propertyId,
   null, null, correlationToken))`. Per-actor accumulation is weakly keyed, so actors replaced
   by a hot reload are not retained.
3. **Frame proof via per-frame correlation.** The preview records one `UiFrameCorrelation` per
   rendered frame mapping the agent-runtime frame to the harness clock frame under the token
   `markup-preview-frame`. `AgentRuntimeObservationSource` (published `harness-agent-runtime`)
   resolves each binding against the latest completed runtime frame and reports an observation
   only when the harness frame is proven — there is no clock fallback and no frame guessing.
4. **Comparison on the render thread.** `PreviewMcp` wires the `RuntimeCompareCoordinator` to
   run the pure `RuntimeComparator` on the render-thread scheduler. The loop order
   (drain commands, then advance the clock) makes the comparator's snapshot frame equal the
   last recorded correlation frame, so `EQUAL` is achievable on the same path the harness
   fixture uses.
5. **Capability-gated tool.** The preview advertises `ui_runtime_compare` in its
   `CapabilitySet`; the harness serves the tool only to sessions declaring the capability and
   answers `UNSUPPORTED_CAPABILITY` otherwise.

## Consequences

- `ui_runtime_compare` on a markup-declared textfield returns `EQUAL` with
  `entityId=user`, `propertyId=value` after a real fill through the harness input path
  (harness E2E, `MarkupHarnessEndToEndTest.markupRuntimeEntityComparesThroughHarnessMcp`).
- Dependency boundaries hold: the runtime module is harness-free, the harness module is
  agent-runtime-free, and the preview is the only module depending on both
  (`harness-agent-runtime` 1.1.0 added to the preview distribution).
- Statuses stay distinct: an actor without `data-runtime-entity` is `MISSING` (unbound);
  without provable frame correlation the comparison is `STALE` or `UNCORRELATED`; a runtime
  source that cannot observe is `UNAVAILABLE`.
- The correlation token is a contract between the sink and the preview
  (`markup-preview-frame`); changing it silently breaks frame proof.
