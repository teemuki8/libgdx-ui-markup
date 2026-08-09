package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FreeTypeFontManagerTest {
    @Test
    void installsBundledInterAndCachesEachLogicalSize() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = new Skin();
            FreeTypeFontManager fonts = FreeTypeFontManager.installDefault(skin, 2f);

            BitmapFont defaultFont = fonts.defaultFont();
            assertSame(defaultFont, fonts.font("inter", 16));
            assertSame(defaultFont, skin.getFont("default-font"));
            assertSame(fonts.font("inter", 28), fonts.font("inter", 28));
            assertNotSame(defaultFont, fonts.font("inter", 28));
            assertEquals(0.5f, defaultFont.getData().scaleX, 0.0001f);
            assertEquals(0.5f, defaultFont.getData().scaleY, 0.0001f);
            assertEquals(Texture.TextureFilter.Linear,
                    defaultFont.getRegions().first().getTexture().getMinFilter());
            assertEquals(Texture.TextureFilter.Linear,
                    defaultFont.getRegions().first().getTexture().getMagFilter());

            skin.dispose();
            assertEquals(0, defaultFont.getRegions().first().getTexture().getTextureObjectHandle(),
                    "skin disposal releases the generated font texture");
        });
    }

    @Test
    void validatesConfigurationAndLookupBounds() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = new Skin();
            assertThrows(IllegalArgumentException.class,
                    () -> FreeTypeFontManager.installDefault(skin, 0f));

            FreeTypeFontManager fonts = FreeTypeFontManager.installDefault(skin, 1f);
            assertThrows(IllegalArgumentException.class, () -> fonts.font("missing", 16));
            assertThrows(IllegalArgumentException.class,
                    () -> fonts.font("inter", TagSpec.MIN_FONT_SIZE - 1));
            assertThrows(IllegalArgumentException.class,
                    () -> fonts.font("inter", TagSpec.MAX_FONT_SIZE + 1));
            assertThrows(IllegalStateException.class,
                    () -> FreeTypeFontManager.installDefault(skin, 1f));

            skin.dispose();
            IllegalStateException disposed = assertThrows(IllegalStateException.class,
                    () -> fonts.font("inter", 20));
            assertTrue(disposed.getMessage().contains("disposed"));
        });
    }

    @Test
    void defaultSkinUsesBundledInterForItsWidgetStyles() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create(2f);
            FreeTypeFontManager fonts = FreeTypeFontManager.optional(skin);

            assertSame(fonts.defaultFont(), skin.getFont("default-font"));
            assertSame(fonts.defaultFont(), skin.get("label", LabelStyle.class).font);
            assertEquals(0.5f, fonts.defaultFont().getData().scaleX, 0.0001f);

            skin.dispose();
        });
    }

    @Test
    void packageLimitsExerciseFamilyAndCacheBoundsWithoutLargeAllocations() throws Exception {
        GdxTestHost.run(() -> {
            FileHandle inter = Gdx.files.classpath("META-INF/fonts/Inter-Regular.ttf");
            Skin familySkin = new Skin();
            assertThrows(IllegalArgumentException.class, () -> FreeTypeFontManager.install(
                    familySkin, "inter", Map.of("inter", inter, "alternate", inter),
                    "abc", 1f, 1, 2));
            familySkin.dispose();

            Skin cacheSkin = new Skin();
            FreeTypeFontManager fonts = FreeTypeFontManager.install(cacheSkin, "inter",
                    Map.of("inter", inter), "abc", 1f, 1, 2);
            fonts.font("inter", 18);
            IllegalStateException full = assertThrows(IllegalStateException.class,
                    () -> fonts.font("inter", 19));
            assertTrue(full.getMessage().contains("2-font limit"));
            assertSame(fonts.font("inter", 18), fonts.font("inter", 18),
                    "cache hits remain available at the limit");
            fonts.dispose();
            fonts.dispose();
            cacheSkin.dispose();
        });
    }

    @Test
    void reservedFontResourcesAreNeverOverwritten() throws Exception {
        GdxTestHost.run(() -> {
            Skin seedCollision = new Skin();
            BitmapFont existingDefault = new BitmapFont();
            BitmapFont existingSeed = new BitmapFont();
            seedCollision.add("default-font", existingDefault, BitmapFont.class);
            seedCollision.add("__markup-freetype-font-inter-16", existingSeed,
                    BitmapFont.class);
            assertThrows(IllegalStateException.class,
                    () -> FreeTypeFontManager.installDefault(seedCollision, 1f));
            assertSame(existingSeed,
                    seedCollision.get("__markup-freetype-font-inter-16", BitmapFont.class));
            seedCollision.dispose();

            Skin lazyCollision = new Skin();
            FreeTypeFontManager fonts = FreeTypeFontManager.installDefault(lazyCollision, 1f);
            BitmapFont existingLazy = new BitmapFont();
            lazyCollision.add("__markup-freetype-font-inter-18", existingLazy, BitmapFont.class);
            assertThrows(IllegalStateException.class, () -> fonts.font("inter", 18));
            assertSame(existingLazy,
                    lazyCollision.get("__markup-freetype-font-inter-18", BitmapFont.class));
            lazyCollision.dispose();
        });
    }

    @Test
    void customGlyphSetRejectsNonBmpCodePoints() throws Exception {
        GdxTestHost.run(() -> {
            Skin skin = new Skin();
            FileHandle inter = Gdx.files.classpath("META-INF/fonts/Inter-Regular.ttf");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> FreeTypeFontManager.install(skin, "inter", Map.of("inter", inter),
                            "abc😀", 1f));
            assertTrue(failure.getMessage().contains("BMP"));
            skin.dispose();
        });
    }
}
