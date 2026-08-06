package dev.gdx.markup.core.style;

/**
 * One simple compound selector: {@code tag}, {@code .class}, {@code #id}, or
 * {@code tag.class}, optionally followed by one pseudo-state ({@code :hover}, {@code :pressed},
 * {@code :checked}, {@code :disabled}). Combinators and descendant selectors are not part of
 * the v1 subset; the cascade stays deterministic.
 */
public record Selector(String tag, String id, String className, String pseudo) {
    /** Validates the immutable shape. */
    public Selector {
        if (id != null && (className != null || tag != null)) {
            throw new IllegalArgumentException("an id selector cannot be combined with tag/class");
        }
    }

    /** Returns the cascade specificity: id=100, class=10, tag=1, tag.class=11. */
    public int specificity() {
        int score = 0;
        if (id != null) {
            score += 100;
        }
        if (className != null) {
            score += 10;
        }
        if (tag != null) {
            score += 1;
        }
        return score;
    }

    /** Returns whether this selector matches the element's tag, id, classes, and pseudo-state. */
    public boolean matches(String elementTag, String elementId, Iterable<String> classes,
            String elementPseudo) {
        if (pseudo != null && !pseudo.equals(elementPseudo)) {
            return false;
        }
        if (tag != null && !tag.equals(elementTag)) {
            return false;
        }
        if (id != null && !id.equals(elementId)) {
            return false;
        }
        if (className != null) {
            for (String candidate : classes) {
                if (className.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
