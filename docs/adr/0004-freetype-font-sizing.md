# ADR 0004 — Exact-size FreeType fonts for declarative UI

Status: Accepted · Date: 2026-08-09

## Context

Markup authors need `font-size` to mean a stable logical UI size. Scaling a pre-rasterized
bitmap font blurs glyphs, and scaling the completed Stage also scales layout instead of letting
Table/Cell reflow for accessibility. The target is static Scene2D UI on LWJGL3 desktop, including
HiDPI backing buffers; continuous game-world camera zoom is not a requirement.

The XML/CSS input boundary must stay GL-free, while font generation and actor mutation remain
render-thread-owned. Font paths cannot come from untrusted markup, caches need hard bounds, and
hot-reload candidates must not leak native generators or textures.

## Decision

1. XML exposes `font` and integer `font-size` only on the seven text-bearing built-in tags.
   Sizes are bounded to 4–256. CSS exposes the same properties, accepts an optional `px` suffix,
   and rejects `font-size` in pseudo-state rules with a located typed diagnostic.
2. XML overrides CSS independently for family and size. With a size, the family resolves through
   the attached `FreeTypeFontManager`; without a size, a named Skin `BitmapFont` resolves first,
   followed by a registered family at logical size 16.
3. `FreeTypeFontManager` is one final concrete FreeType implementation, not a renderer SPI. It
   owns at most 16 application-registered family generators and caches at most 64 exact
   family/size fonts for one Skin. Markup never supplies a filesystem path. The default family is
   bundled Inter under the SIL Open Font License, with a bounded default glyph set.
4. Each font is rasterized at `round(logicalSize * rasterScale)`, using linear filtering,
   AutoMedium hinting, kerning, gamma 1.8, two render passes, and no mipmaps or incremental glyph
   generation. Font data is scaled back to logical units. Raster scale is finite and bounded to
   1–4; the preview derives it from the larger physical/logical axis ratio.
5. Generated fonts are registered exactly once in the owning Skin. The Skin disposes font
   textures; its attached manager disposes generators. Installation and lazy registration roll
   back on failure. Preview rebuilds keep this ownership inside each transactional candidate.
6. The builder applies a generated font to every relevant copied style field, including
   TextField message text and a copied SelectBox dropdown ListStyle, without mutating shared Skin
   styles. Accessibility size changes rebuild the UI so native Scene2D layout reflows.

## Consequences

- Declared logical sizes remain sharp on normal and HiDPI desktop displays without changing
  Scene2D layout dimensions.
- Parsing remains deterministic and GL-free; generation, building, rebuild, and disposal are
  explicitly render-thread operations.
- Cache, family, glyph, and size bounds make memory/native-resource growth predictable. Unknown
  families, missing manager installation, and cache exhaustion remain typed and located.
- Existing custom Skin fonts remain compatible when no size is declared. Custom exact-size
  families require explicit application registration.
- The preview distribution carries the FreeType Java/native artifacts, bundled Inter file, and
  its license.

## Rejected alternatives

- MSDF/SDF rendering was rejected. Its shader, batching, atlas-generation, tuning, and asset
  pipeline cost serves continuous scale ranges that this static UI and accessibility-reflow
  contract does not require. Keeping an abstraction or placeholder for it would add deliberate
  technical debt, so production code has no renderer backend interface or future hook.
- Scaling one bitmap font or the completed Stage was rejected because it produces blur or breaks
  logical reflow semantics.
- Loading font paths from markup was rejected because it expands the trust boundary into
  arbitrary filesystem access and makes builds non-reproducible.
