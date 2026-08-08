package dev.gdx.markup.core.style;

import dev.gdx.markup.core.Element;
import dev.gdx.markup.core.MarkupException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the deterministic cascade: specificity ({@code #id}=100 &gt; {@code .class}=10 &gt;
 * {@code tag}=1, {@code tag.class}=11) wins; later rules break ties. Each element resolves to one
 * immutable {@link ResolvedStyle} per pseudo-state; the base style uses a {@code null} pseudo.
 * Selector comparisons are counted against per-resolve and per-build (resolver lifetime) work
 * limits; a build creates one resolver, so callers that resolve many elements bound the total
 * cascade work. One resolver is not thread-safe.
 */
public final class CssStyleResolver {
    /** Maximum selector comparisons in one resolve call. */
    public static final int MAX_COMPARISONS_PER_RESOLVE = 65_536;
    /** Maximum selector comparisons across one build (one resolver lifetime). */
    public static final int MAX_COMPARISONS_PER_BUILD = 1_048_576;

    private final List<CssRule> rules;
    private final int maxComparisonsPerResolve;
    private final int maxComparisonsPerBuild;
    private int comparisonsThisBuild;

    /** Creates a resolver over one parsed stylesheet with the default work limits. */
    public CssStyleResolver(CssDocument document) {
        this(document, MAX_COMPARISONS_PER_RESOLVE, MAX_COMPARISONS_PER_BUILD);
    }

    /** Creates a resolver over one parsed stylesheet with explicit work limits. */
    public CssStyleResolver(CssDocument document, int maxComparisonsPerResolve,
            int maxComparisonsPerBuild) {
        this(Objects.requireNonNull(document, "document").rules(), maxComparisonsPerResolve,
                maxComparisonsPerBuild);
    }

    /** Creates a resolver over an explicit rule list with the default work limits. */
    public CssStyleResolver(List<CssRule> rules) {
        this(rules, MAX_COMPARISONS_PER_RESOLVE, MAX_COMPARISONS_PER_BUILD);
    }

    /** Creates a resolver over an explicit rule list with explicit work limits. */
    public CssStyleResolver(List<CssRule> rules, int maxComparisonsPerResolve,
            int maxComparisonsPerBuild) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (maxComparisonsPerResolve <= 0) {
            throw new IllegalArgumentException("maxComparisonsPerResolve must be positive");
        }
        if (maxComparisonsPerBuild <= 0) {
            throw new IllegalArgumentException("maxComparisonsPerBuild must be positive");
        }
        this.maxComparisonsPerResolve = maxComparisonsPerResolve;
        this.maxComparisonsPerBuild = maxComparisonsPerBuild;
    }

    /** Resolves the base (non-pseudo) style for one element. */
    public ResolvedStyle resolve(Element element) {
        return resolve(element, null);
    }

    /**
     * Resolves the style variant for one element and pseudo-state (or {@code null} for base).
     * Every {@link Selector#matches} call is counted against the per-resolve and per-build work
     * limits; exceeding either throws a located {@code TOO_LARGE} diagnostic at the element.
     */
    public ResolvedStyle resolve(Element element, String pseudo) {
        Objects.requireNonNull(element, "element");
        int comparisons = 0;
        List<Candidate> matching = new ArrayList<>();
        for (CssRule rule : rules) {
            for (Selector selector : rule.selectors()) {
                if (++comparisons > maxComparisonsPerResolve) {
                    throw tooLarge(element, "style resolution for <" + element.tag()
                            + "> exceeds the " + maxComparisonsPerResolve + "-comparison limit");
                }
                if (++comparisonsThisBuild > maxComparisonsPerBuild) {
                    throw tooLarge(element, "style resolution exceeds the "
                            + maxComparisonsPerBuild + "-comparison build limit");
                }
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

    private static MarkupException tooLarge(Element element, String message) {
        return new MarkupException(MarkupException.Kind.TOO_LARGE, element.tag(),
                element.line(), element.column(), message);
    }

    private record Candidate(CssRule rule, int specificity) {}
}
