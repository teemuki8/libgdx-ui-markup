package dev.gdx.markup.core.style;

import java.util.List;
import java.util.Objects;

/** Immutable bounded selector, stored right-to-left for ancestry matching. */
public record Selector(List<SelectorPart> parts) {
    /** Maximum compound parts in one selector. */
    public static final int MAX_PARTS = 8;

    /** Validates and snapshots the bounded selector AST. */
    public Selector {
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        if (parts.isEmpty() || parts.size() > MAX_PARTS) {
            throw new IllegalArgumentException("selector requires 1 through " + MAX_PARTS + " parts");
        }
        if (parts.getFirst().combinator() != SelectorPart.Combinator.SELF) {
            throw new IllegalArgumentException("rightmost selector part must use SELF");
        }
        for (int index = 1; index < parts.size(); index++) {
            if (parts.get(index).combinator() == SelectorPart.Combinator.SELF
                    || parts.get(index).pseudo() != null) {
                throw new IllegalArgumentException("invalid non-rightmost selector part");
            }
        }
    }

    /** Source-compatible constructor for one legacy simple selector. */
    public Selector(String tag, String id, String className, String pseudo) {
        this(List.of(new SelectorPart(tag, id,
                className == null ? List.of() : List.of(className), pseudo,
                SelectorPart.Combinator.SELF)));
    }

    public String tag() { return parts.getFirst().tag(); }
    public String id() { return parts.getFirst().id(); }
    public String className() {
        return parts.getFirst().classNames().isEmpty() ? null
                : parts.getFirst().classNames().getFirst();
    }
    public String pseudo() { return parts.getFirst().pseudo(); }

    /** Full CSS-shaped specificity across every part. */
    public int specificity() {
        return parts.stream().mapToInt(SelectorPart::specificity).sum();
    }

    /** Legacy direct match against the rightmost compound only. */
    public boolean matches(String elementTag, String elementId, Iterable<String> classes,
            String elementPseudo) {
        return parts.getFirst().matches(elementTag, elementId, classes, elementPseudo);
    }
}
