package dev.gdx.markup.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.markup.core.MarkupException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CssColorTest {
    @Test
    void parsesHexFunctionsTransparentAndNamedColors() {
        assertEquals(new CssColor.Rgba(0xaa, 0xbb, 0xcc, 1f), CssColor.parse("#abc"));
        assertEquals(new CssColor.Rgba(0xaa, 0xbb, 0xcc, 0xdd / 255f),
                CssColor.parse("#abcd"));
        assertEquals(new CssColor.Rgba(0x12, 0x34, 0x56, 1f),
                CssColor.parse("#123456"));
        assertEquals(new CssColor.Rgba(0x12, 0x34, 0x56, 0x78 / 255f),
                CssColor.parse("#12345678"));
        assertEquals(new CssColor.Rgba(0, 127, 255, 1f),
                CssColor.parse("rgb(0, 127, 255)"));
        assertEquals(new CssColor.Rgba(1, 2, 3, 0.25f),
                CssColor.parse("rgba(1, 2, 3, 0.25)"));
        assertEquals(new CssColor.Rgba(0, 0, 0, 0f), CssColor.parse("transparent"));
        assertEquals(new CssColor.Named("accent-blue"), CssColor.parse("accent-blue"));
    }

    @Test
    void rejectsMalformedUnsupportedAndOutOfRangeColors() {
        for (String value : List.of(
                "#12", "#ggg", "#12345", "#123456789",
                "rgb(-1, 2, 3)", "rgb(256, 2, 3)", "rgb(1.5, 2, 3)",
                "rgb(10%, 20%, 30%)", "rgb(1, 2)", "rgb(1, 2, 3) trailing",
                "rgba(1, 2, 3, -0.1)", "rgba(1, 2, 3, 1.1)",
                "rgba(1, 2, 3, 50%)", "rgba(1, 2, 3)", "")) {
            MarkupException failure = assertThrows(MarkupException.class,
                    () -> CssColor.parse(value), value);
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind(), value);
        }
    }

    @Test
    void rgbaRecordRejectsInvalidComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> new CssColor.Rgba(256, 0, 0, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> new CssColor.Rgba(0, 0, 0, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new CssColor.Rgba(0, 0, 0, 1.01f));
        assertThrows(IllegalArgumentException.class, () -> new CssColor.Named("not valid"));
    }
}
