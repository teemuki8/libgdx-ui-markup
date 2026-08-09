package dev.gdx.markup.core.style;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One compound selector and its relationship to the part immediately to its right. */
public record SelectorPart(String tag, String id, List<String> classNames, String pseudo,
        Combinator combinator) {
    public enum Combinator { SELF, DESCENDANT, CHILD }

    public SelectorPart {
        classNames = List.copyOf(Objects.requireNonNull(classNames, "classNames"));
        Objects.requireNonNull(combinator, "combinator");
        if (new HashSet<>(classNames).size() != classNames.size()) {
            throw new IllegalArgumentException("duplicate selector class");
        }
    }

    public int specificity() {
        return (id == null ? 0 : 100) + classNames.size() * 10
                + (pseudo == null ? 0 : 10) + (tag == null ? 0 : 1);
    }

    public boolean matches(String elementTag, String elementId, Iterable<String> classes,
            String elementPseudo) {
        if (pseudo != null && !pseudo.equals(elementPseudo)) return false;
        if (tag != null && !tag.equals(elementTag)) return false;
        if (id != null && !id.equals(elementId)) return false;
        Set<String> actual = new HashSet<>();
        classes.forEach(actual::add);
        return actual.containsAll(classNames);
    }
}
