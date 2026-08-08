package dev.gdx.markup.core.style;

import dev.gdx.markup.core.Element;
import dev.gdx.markup.core.MarkupException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the deterministic cascade: specificity ({@code #id}=100 &gt; {@code .class}=10 &gt;
 * {@code tag}=1, {@code tag.class}=11) wins; later rules break ties. A comma group scores the
 * maximum specificity over every matching part, so {@code button, #save} matches at 100, not
 * the first part's 1. Each element resolves to one immutable {@link ResolvedStyle} per
 * pseudo-state; the base style uses a {@code null} pseudo.
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
        this(rules, maxComparisonsPerResolve, maxComparisonsPerBuild, 0);
    }

    /**
     * Package-private seam for boundary tests: seeds the per-build comparison counter near its
     * limit so the non-wrapping behavior can be exercised without billions of comparisons.
     */
    CssStyleResolver(List<CssRule> rules, int maxComparisonsPerResolve, int maxComparisonsPerBuild,
            int seededBuildComparisons) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (maxComparisonsPerResolve <= 0) {
            throw new IllegalArgumentException("maxComparisonsPerResolve must be positive");
        }
        if (maxComparisonsPerBuild <= 0) {
            throw new IllegalArgumentException("maxComparisonsPerBuild must be positive");
        }
        if (seededBuildComparisons < 0) {
            throw new IllegalArgumentException("seededBuildComparisons must be non-negative");
        }
        this.maxComparisonsPerResolve = maxComparisonsPerResolve;
        this.maxComparisonsPerBuild = maxComparisonsPerBuild;
        this.comparisonsThisBuild = seededBuildComparisons;
    }

    /** Resolves the base (non-pseudo) style for one element. */
    public ResolvedStyle resolve(Element element) {
        return resolve(element, null, null);
    }

    /** Resolves the style variant for one element and pseudo-state (or {@code null} for base). */
    public ResolvedStyle resolve(Element element, String pseudo) {
        return resolve(element, pseudo, null);
    }

    /**
     * Resolves the style variant for one element and pseudo-state (or {@code null} for base),
     * reporting limit failures with {@code path} — the element's full tracked path, as threaded
     * by the builder — or with the element tag when {@code path} is {@code null} (direct GL-free
     * use). Every {@link Selector#matches} call is counted against the per-resolve and
     * per-build work limits using compare-before-increment, so the counters saturate at their
     * limits and can never wrap even when a limit is {@link Integer#MAX_VALUE}.
     */
    public ResolvedStyle resolve(Element element, String pseudo, String path) {
        Objects.requireNonNull(element, "element");
        String diagnosticPath = path != null ? path : element.tag();
        int comparisons = 0;
        List<Candidate> matching = new ArrayList<>();
        for (CssRule rule : rules) {
            int bestSpecificity = -1;
            for (Selector selector : rule.selectors()) {
                if (comparisons >= maxComparisonsPerResolve) {
                    throw tooLarge(diagnosticPath, element, "style resolution for <"
                            + element.tag() + "> exceeds the " + maxComparisonsPerResolve
                            + "-comparison limit");
                }
                comparisons++;
                if (comparisonsThisBuild >= maxComparisonsPerBuild) {
                    throw tooLarge(diagnosticPath, element, "style resolution exceeds the "
                            + maxComparisonsPerBuild + "-comparison build limit");
                }
                comparisonsThisBuild++;
                // Every matching part of a comma group counts; the strongest part scores the
                // whole rule, so `button, #save` beats a class rule even though `button` also
                // matches.
                if (selector.matches(element.tag(), element.id(), element.classes(), pseudo)
                        && selector.specificity() > bestSpecificity) {
                    bestSpecificity = selector.specificity();
                }
            }
            if (bestSpecificity >= 0) {
                matching.add(new Candidate(rule, bestSpecificity));
            }
        }
        matching.sort((left, right) -> {
            int bySpecificity = Integer.compare(left.specificity, right.specificity);
            return bySpecificity != 0 ? bySpecificity
                    : Integer.compare(left.rule.ruleIndex(), right.rule.ruleIndex());
        });
        ResolvedStyle.Builder builder = ResolvedStyle.builder();
        for (Candidate candidate : matching) {
            candidate.rule.properties().forEach((property, value) ->
                    builder.put(property, value, candidate.rule));
        }
        return builder.build();
    }

    private static MarkupException tooLarge(String path, Element element, String message) {
        return new MarkupException(MarkupException.Kind.TOO_LARGE, path,
                element.line(), element.column(), message);
    }

    private record Candidate(CssRule rule, int specificity) {}
}
