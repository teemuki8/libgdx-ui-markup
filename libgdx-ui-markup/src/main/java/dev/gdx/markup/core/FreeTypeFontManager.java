package dev.gdx.markup.core;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Render-thread owner for exact-size FreeType fonts attached to one {@link Skin}. Fonts are
 * cached by family and logical size, registered in the owning skin exactly once, and disposed
 * by that skin. This manager owns only the FreeType generators.
 */
public final class FreeTypeFontManager implements Disposable {
    /** Default bundled family name. */
    public static final String DEFAULT_FAMILY = "inter";
    /** Default logical font size. */
    public static final int DEFAULT_FONT_SIZE = 16;
    /** Maximum number of registered font families per skin. */
    public static final int MAX_FAMILIES = 16;
    /** Maximum number of cached family/size combinations per skin. */
    public static final int MAX_CACHED_FONTS = 64;
    /** Maximum glyph-set length accepted by the FreeType generator. */
    public static final int MAX_GLYPH_CHARACTERS = 2_048;

    private static final float MIN_RASTER_SCALE = 1f;
    private static final float MAX_RASTER_SCALE = 4f;
    private static final String MANAGER_RESOURCE = "__markup-freetype-manager";
    private static final String FONT_RESOURCE_PREFIX = "__markup-freetype-font-";
    private static final String BUNDLED_INTER = "META-INF/fonts/Inter-Regular.ttf";
    private static final Pattern FAMILY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final String DEFAULT_GLYPHS = uniqueCharacters(
            FreeTypeFontGenerator.DEFAULT_CHARS + "–—‘’“”…•");

    private final Skin skin;
    private final String defaultFamily;
    private final String glyphCharacters;
    private final float rasterScale;
    private final int maxCachedFonts;
    private final Thread ownerThread;
    private final Map<String, FreeTypeFontGenerator> generators;
    private final Map<FontKey, BitmapFont> fonts = new LinkedHashMap<>();
    private BitmapFont defaultFont;
    private String defaultResourceName;
    private boolean disposed;

    private FreeTypeFontManager(Skin skin, String defaultFamily,
            Map<String, FileHandle> families, String glyphCharacters, float rasterScale,
            int maxCachedFonts) {
        this.skin = skin;
        this.defaultFamily = defaultFamily;
        this.glyphCharacters = glyphCharacters;
        this.rasterScale = rasterScale;
        this.maxCachedFonts = maxCachedFonts;
        ownerThread = Thread.currentThread();
        generators = createGenerators(families);
    }

    /** Installs the bundled Inter family and eagerly creates the default 16-unit font. */
    public static FreeTypeFontManager installDefault(Skin skin, float rasterScale) {
        requireRenderThread();
        return install(skin, DEFAULT_FAMILY,
                Map.of(DEFAULT_FAMILY, Gdx.files.classpath(BUNDLED_INTER)),
                DEFAULT_GLYPHS, rasterScale);
    }

    /**
     * Installs an explicit bounded family set on one skin and eagerly creates its default font.
     * Family names use the same identifier grammar as the bounded CSS {@code font} property.
     */
    public static FreeTypeFontManager install(Skin skin, String defaultFamily,
            Map<String, FileHandle> families, String glyphCharacters, float rasterScale) {
        return install(skin, defaultFamily, families, glyphCharacters, rasterScale,
                MAX_FAMILIES, MAX_CACHED_FONTS);
    }

    static FreeTypeFontManager install(Skin skin, String defaultFamily,
            Map<String, FileHandle> families, String glyphCharacters, float rasterScale,
            int maxFamilies, int maxCachedFonts) {
        requireRenderThread();
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(defaultFamily, "defaultFamily");
        Objects.requireNonNull(families, "families");
        Objects.requireNonNull(glyphCharacters, "glyphCharacters");
        if (optional(skin) != null) {
            throw new IllegalStateException("a FreeType font manager is already installed");
        }
        validateConfiguration(defaultFamily, families, glyphCharacters, rasterScale,
                maxFamilies, maxCachedFonts);

        FreeTypeFontManager manager = new FreeTypeFontManager(skin, defaultFamily,
                new LinkedHashMap<>(families), glyphCharacters, rasterScale, maxCachedFonts);
        try {
            manager.seedDefaultFont();
            skin.add(MANAGER_RESOURCE, manager, FreeTypeFontManager.class);
            return manager;
        } catch (RuntimeException | Error failure) {
            if (skin.has(MANAGER_RESOURCE, FreeTypeFontManager.class)) {
                skin.remove(MANAGER_RESOURCE, FreeTypeFontManager.class);
            }
            manager.rollbackDefaultFont();
            manager.dispose();
            throw failure;
        }
    }

    /** Returns the cached or newly rasterized font for a family and logical integer size. */
    public BitmapFont font(String family, int logicalSize) {
        checkUsableOnOwnerThread();
        Objects.requireNonNull(family, "family");
        if (!generators.containsKey(family)) {
            throw new IllegalArgumentException("unknown FreeType font family \"" + family + "\"");
        }
        if (logicalSize < TagSpec.MIN_FONT_SIZE || logicalSize > TagSpec.MAX_FONT_SIZE) {
            throw new IllegalArgumentException("font size must be from " + TagSpec.MIN_FONT_SIZE
                    + " through " + TagSpec.MAX_FONT_SIZE + ", got " + logicalSize);
        }
        FontKey key = new FontKey(family, logicalSize);
        BitmapFont cached = fonts.get(key);
        if (cached != null) {
            return cached;
        }
        if (fonts.size() >= maxCachedFonts) {
            throw new CacheLimitException("FreeType font cache exceeds the "
                    + maxCachedFonts + "-font limit");
        }
        String resourceName = resourceName(key);
        if (skin.has(resourceName, BitmapFont.class)) {
            throw new ResourceCollisionException("reserved font resource already exists: "
                    + resourceName);
        }
        BitmapFont generated = generate(family, logicalSize);
        try {
            skin.add(resourceName, generated, BitmapFont.class);
            fonts.put(key, generated);
            return generated;
        } catch (RuntimeException | Error failure) {
            if (skin.has(resourceName, BitmapFont.class)) {
                skin.remove(resourceName, BitmapFont.class);
            }
            generated.dispose();
            throw failure;
        }
    }

    /** Returns the eagerly generated font for the configured family at 16 logical units. */
    public BitmapFont defaultFont() {
        checkUsableOnOwnerThread();
        return defaultFont;
    }

    /** Returns the manager attached to a skin, or {@code null} when none is installed. */
    static FreeTypeFontManager optional(Skin skin) {
        Objects.requireNonNull(skin, "skin");
        return skin.optional(MANAGER_RESOURCE, FreeTypeFontManager.class);
    }

    String defaultFamily() {
        return defaultFamily;
    }

    @Override
    public void dispose() {
        checkOwnerThread();
        if (!disposed) {
            disposed = true;
            disposeGenerators();
        }
    }

    private void seedDefaultFont() {
        FontKey key = new FontKey(defaultFamily, DEFAULT_FONT_SIZE);
        defaultResourceName = skin.has("default-font", BitmapFont.class)
                ? resourceName(key) : "default-font";
        if (skin.has(defaultResourceName, BitmapFont.class)) {
            throw new ResourceCollisionException("reserved font resource already exists: "
                    + defaultResourceName);
        }
        BitmapFont generated = generate(defaultFamily, DEFAULT_FONT_SIZE);
        try {
            skin.add(defaultResourceName, generated, BitmapFont.class);
            fonts.put(key, generated);
            defaultFont = generated;
        } catch (RuntimeException | Error failure) {
            if (skin.has(defaultResourceName, BitmapFont.class)) {
                skin.remove(defaultResourceName, BitmapFont.class);
            }
            generated.dispose();
            defaultResourceName = null;
            throw failure;
        }
    }

    private BitmapFont generate(String family, int logicalSize) {
        int rasterSize = Math.round(logicalSize * rasterScale);
        FreeTypeFontParameter parameter = new FreeTypeFontParameter();
        parameter.size = rasterSize;
        parameter.characters = glyphCharacters;
        parameter.hinting = FreeTypeFontGenerator.Hinting.AutoMedium;
        parameter.kerning = true;
        parameter.gamma = 1.8f;
        parameter.renderCount = 2;
        parameter.genMipMaps = false;
        parameter.minFilter = Texture.TextureFilter.Linear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        parameter.incremental = false;
        BitmapFont font = generators.get(family).generateFont(parameter);
        font.getData().markupEnabled = false;
        float logicalScale = (float) logicalSize / rasterSize;
        font.getData().setScale(logicalScale);
        for (TextureRegion region : font.getRegions()) {
            region.getTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        return font;
    }

    private static Map<String, FreeTypeFontGenerator> createGenerators(
            Map<String, FileHandle> families) {
        Map<String, FreeTypeFontGenerator> created = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, FileHandle> entry : families.entrySet()) {
                created.put(entry.getKey(), new FreeTypeFontGenerator(entry.getValue()));
            }
            return created;
        } catch (RuntimeException | Error failure) {
            disposeGeneratorsReverse(created);
            throw failure;
        }
    }

    private static void validateConfiguration(String defaultFamily,
            Map<String, FileHandle> families, String glyphCharacters, float rasterScale,
            int maxFamilies, int maxCachedFonts) {
        if (maxFamilies < 1 || maxFamilies > MAX_FAMILIES
                || maxCachedFonts < 1 || maxCachedFonts > MAX_CACHED_FONTS) {
            throw new IllegalArgumentException("test limits must stay within production bounds");
        }
        if (!Float.isFinite(rasterScale)
                || rasterScale < MIN_RASTER_SCALE || rasterScale > MAX_RASTER_SCALE) {
            throw new IllegalArgumentException("raster scale must be from " + MIN_RASTER_SCALE
                    + " through " + MAX_RASTER_SCALE + ", got " + rasterScale);
        }
        if (families.isEmpty() || families.size() > maxFamilies) {
            throw new IllegalArgumentException("font families must contain from 1 through "
                    + maxFamilies + " entries");
        }
        if (!families.containsKey(defaultFamily)) {
            throw new IllegalArgumentException("default font family is not registered: "
                    + defaultFamily);
        }
        for (Map.Entry<String, FileHandle> entry : families.entrySet()) {
            if (!FAMILY_NAME.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("invalid font family name: " + entry.getKey());
            }
            FileHandle file = Objects.requireNonNull(entry.getValue(),
                    "font file for " + entry.getKey());
            if (!file.exists()) {
                throw new IllegalArgumentException("font file does not exist: " + file);
            }
        }
        if (glyphCharacters.isEmpty() || glyphCharacters.length() > MAX_GLYPH_CHARACTERS) {
            throw new IllegalArgumentException("glyph characters must contain from 1 through "
                    + MAX_GLYPH_CHARACTERS + " UTF-16 code units");
        }
        for (int index = 0; index < glyphCharacters.length(); index++) {
            if (Character.isSurrogate(glyphCharacters.charAt(index))) {
                throw new IllegalArgumentException(
                        "glyph characters must contain BMP characters only");
            }
        }
    }

    private static void requireRenderThread() {
        if (Gdx.app == null || Gdx.gl == null) {
            throw new IllegalStateException(
                    "FreeType fonts must be installed on the render thread");
        }
    }

    private void checkUsableOnOwnerThread() {
        checkOwnerThread();
        if (disposed) {
            throw new IllegalStateException("FreeType font manager is disposed");
        }
    }

    private void checkOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "FreeType font manager may only be used on its render thread");
        }
    }

    private void disposeGenerators() {
        disposeGeneratorsReverse(generators);
    }

    private static void disposeGeneratorsReverse(Map<String, FreeTypeFontGenerator> owned) {
        ArrayList<FreeTypeFontGenerator> reverse = new ArrayList<>(owned.values());
        for (int index = reverse.size() - 1; index >= 0; index--) {
            reverse.get(index).dispose();
        }
    }

    private void rollbackDefaultFont() {
        if (defaultFont == null) {
            return;
        }
        if (defaultResourceName != null && skin.has(defaultResourceName, BitmapFont.class)) {
            skin.remove(defaultResourceName, BitmapFont.class);
        }
        fonts.remove(new FontKey(defaultFamily, DEFAULT_FONT_SIZE));
        defaultFont.dispose();
        defaultFont = null;
        defaultResourceName = null;
    }

    private static String resourceName(FontKey key) {
        return FONT_RESOURCE_PREFIX + key.family() + "-" + key.logicalSize();
    }

    private static String uniqueCharacters(String value) {
        StringBuilder unique = new StringBuilder(value.length());
        value.codePoints().distinct().forEach(unique::appendCodePoint);
        return unique.toString();
    }

    private record FontKey(String family, int logicalSize) {
    }

    static final class CacheLimitException extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        CacheLimitException(String message) {
            super(message);
        }
    }

    static final class ResourceCollisionException extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        ResourceCollisionException(String message) {
            super(message);
        }
    }
}
