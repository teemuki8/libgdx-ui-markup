package dev.gdx.markup.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic bounded nearest-name lookup for actionable diagnostics. */
final class NearestSuggestion {
    private static final int MAX_EXPECTED_LENGTH = 4096;
    static final int MAX_CANDIDATES =
            MarkupParser.MAX_EXTRA_TAGS + MarkupParser.MAX_COMPONENTS;

    private NearestSuggestion() {}

    static Optional<String> unique(String received, Collection<String> candidates) {
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(candidates, "candidates");
        if (received.length() > MarkupParser.MAX_NAME_LENGTH
                || candidates.size() > MAX_CANDIDATES) {
            return Optional.empty();
        }
        List<String> sorted = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            String name = Objects.requireNonNull(candidate, "candidate");
            if (name.length() > MarkupParser.MAX_NAME_LENGTH) {
                return Optional.empty();
            }
            sorted.add(name);
        }
        sorted.sort(String::compareTo);

        int threshold = Math.max(1, Math.min(3, received.length() / 3));
        int bestDistance = Integer.MAX_VALUE;
        String best = null;
        boolean tie = false;
        String previous = null;
        for (String candidate : sorted) {
            if (candidate.equals(previous)) {
                continue;
            }
            previous = candidate;
            int distance = distance(received, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return best != null && !tie && bestDistance <= threshold
                ? Optional.of(best)
                : Optional.empty();
    }

    static String expected(Collection<String> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<String> sorted = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                sorted.add(candidate);
            }
        }
        sorted.sort(String::compareTo);
        StringBuilder result = new StringBuilder("one of [");
        String previous = null;
        boolean truncated = false;
        for (String candidate : sorted) {
            if (candidate.equals(previous)) {
                continue;
            }
            previous = candidate;
            int separator = result.length() == "one of [".length() ? 0 : 2;
            if (result.length() + separator + candidate.length() + 1 > MAX_EXPECTED_LENGTH) {
                truncated = true;
                break;
            }
            if (separator != 0) {
                result.append(", ");
            }
            result.append(candidate);
        }
        if (truncated) {
            if (result.length() + 7 <= MAX_EXPECTED_LENGTH) {
                result.append(", ...");
            }
        }
        return result.append(']').toString();
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
