package dev.gdx.markup.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.markup.core.MarkupException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CssLengthTest {
    @Test
    void parsesPixelsPercentAndAuto() {
        assertEquals(new CssLength.Pixels(12f), CssLength.parse("12", false));
        assertEquals(new CssLength.Pixels(12.5f), CssLength.parse("12.5px", false));
        assertEquals(new CssLength.Percent(1f), CssLength.parse("100%", false));
        assertEquals(new CssLength.Percent(0f), CssLength.parse("0%", false));
        assertEquals(CssLength.Auto.INSTANCE, CssLength.parse("auto", true));
    }

    @Test
    void rejectsAutoWhenThePropertyRequiresPixelsOrPercent() {
        MarkupException failure = assertThrows(MarkupException.class,
                () -> CssLength.parse("auto", false));
        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
    }

    @Test
    void rejectsNegativeNonFiniteAndUnsupportedLengths() {
        for (String value : List.of("-1", "NaN", "Infinity", "1em", "1vh", "1%%", "")) {
            MarkupException failure = assertThrows(MarkupException.class,
                    () -> CssLength.parse(value, true), value);
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind(), value);
        }
    }

    @Test
    void parsesCssAndLegacySpacingForms() {
        assertEquals(new CssSpacing(1f, 1f, 1f, 1f), CssSpacing.parse("1px"));
        assertEquals(new CssSpacing(1f, 2f, 1f, 2f), CssSpacing.parse("1px 2px"));
        assertEquals(new CssSpacing(1f, 2f, 3f, 2f), CssSpacing.parse("1 2 3"));
        assertEquals(new CssSpacing(1f, 2f, 3f, 4f), CssSpacing.parse("1 2 3 4"));
        assertEquals(new CssSpacing(1f, 2f, 3f, 4f), CssSpacing.parse("1,2,3,4"));
    }

    @Test
    void spacingRejectsMixedSeparatorsPercentAndWrongArity() {
        for (String value : List.of("1, 2 3", "1%", "1 2 3 4 5", "1,,3,4")) {
            MarkupException failure = assertThrows(MarkupException.class,
                    () -> CssSpacing.parse(value), value);
            assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind(), value);
        }
    }

    @Test
    void resolvedStyleExposesTypedValuesWithoutChangingRawProperties() {
        CssDocument document = new CssParser().parse("""
                button { width: 75%; padding: 1px 2px 3px 4px; }
                """);
        ResolvedStyle style = new CssStyleResolver(document).resolve(new dev.gdx.markup.core.Element(
                "button", null, null, null, null, java.util.Map.of(), List.of(), List.of(), 1, 1));

        CssLength.Percent width = assertInstanceOf(CssLength.Percent.class,
                style.lengthValue("width"));
        assertEquals(0.75f, width.ratio(), 0.0001f);
        assertEquals(new CssSpacing(1f, 2f, 3f, 4f), style.spacing("padding"));
        assertEquals("75%", document.rules().getFirst().properties().get("width"));
    }
}
