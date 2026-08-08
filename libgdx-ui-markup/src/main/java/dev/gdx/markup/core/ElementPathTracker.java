package dev.gdx.markup.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * GL-free element path tracker shared by the parser, the builder, and the runtime. Paths are
 * scoped per parent: each entered element frame owns its own tag counter, so repeated same-tag
 * siblings under different parents do not share indices. The first same-tag child uses the bare
 * tag; later siblings use zero-based suffixes such as {@code button[1]}.
 */
public final class ElementPathTracker {
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** Creates a tracker positioned at the document root with an empty path. */
    public ElementPathTracker() {
        frames.push(new Frame("", new HashMap<>()));
    }

    /**
     * Enters one element: counts it as a child of the current frame, appends its segment to the
     * parent path, pushes a fresh child-count frame, and returns the element's full path. The tag
     * must already be normalized (lowercase) by the caller.
     */
    public String enter(String tag) {
        Objects.requireNonNull(tag, "tag");
        Frame parent = frames.peek();
        int index = parent.childCounts.merge(tag, 1, Integer::sum) - 1;
        String segment = index == 0 ? tag : tag + "[" + index + "]";
        String path = parent.path.isEmpty() ? segment : parent.path + "/" + segment;
        frames.push(new Frame(path, new HashMap<>()));
        return path;
    }

    /** Returns the already-entered current element path without mutating the tracker. */
    public String current() {
        return frames.peek().path;
    }

    /** Leaves the current element; rejects underflow past the document root. */
    public void exit() {
        if (frames.size() <= 1) {
            throw new IllegalStateException("cannot exit the document root element frame");
        }
        frames.pop();
    }

    /** One entered element: its full path and its per-tag child counters. */
    private static final class Frame {
        private final String path;
        private final Map<String, Integer> childCounts;

        private Frame(String path, Map<String, Integer> childCounts) {
            this.path = path;
            this.childCounts = childCounts;
        }
    }
}
