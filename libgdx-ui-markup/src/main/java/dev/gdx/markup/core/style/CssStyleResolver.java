package dev.gdx.markup.core.style;

import dev.gdx.markup.core.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the deterministic cascade: specificity ({@code #id}=100 &gt; {@code .class}=10 &gt;
 * {@code tag}=1, {@code tag.class}=11) wins; later rules break ties. Each element resolves to one
 * immutable {@link ResolvedStyle} per pseudo-state; the base style uses a {@code null} pseudo.
 */
public final class CssStyleResolver {
    private final List<CssRule> rules;

    /** Creates a resolver over one parsed stylesheet. */
    public CssStyleResolver(CssDocument document) {
        this(Objects.requireNonNull(document, "document").rules());
    }

    /** Creates a resolver over an explicit rule list. */
    public CssStyleResolver(List<CssRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /** Resolves the base (non-pseudo) style for one element. */
    public ResolvedStyle resolve(Element element) {
        return resolve(element, null);
    }

    /** Resolves the style variant for one element and pseudo-state (or {@code null} for base). */
    public ResolvedStyle resolve(Element element, String pseudo) {
        Objects.requireNonNull(element, "element");
        List<Candidate> matching = new ArrayList<>();
        for (CssRule rule : rules) {
            for (Selector selector : rule.selectors()) {
                if (selector.matches(element.tag(), element.id(), element.classes(), pseudo)) {
                    matching.add(new Candidate(rule, selector.specificity()));
                    break;
                }
            }
        }
        matching.sort((left, right) -> {
            int bySpecificity = Integer.compare(left.specificity, right.specificity);
            return bySpecificity != 0 ? bySpecificity
                    : Integer.compare(left.rule.ruleIndex(), right.rule.ruleIndex());
        });
        ResolvedStyle.Builder builder = ResolvedStyle.builder();
        for (Candidate candidate : matching) {
            candidate.rule.properties().forEach(builder::put);
        }
        return builder.build();
    }

    private record Candidate(CssRule rule, int specificity) {}
}
