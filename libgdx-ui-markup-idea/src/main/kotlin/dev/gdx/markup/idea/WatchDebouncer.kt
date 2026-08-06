package dev.gdx.markup.idea

/**
 * One-shot debounce for file-change watchers: repeated {@link #noteChange} calls within the
 * quiet window collapse into a single {@link #takeDue} after the window elapses.
 */
class WatchDebouncer(private val debounceNanos: Long) {
    private var lastChangeNanos = Long.MIN_VALUE
    private var fired = true

    /** Records a file change at the given monotonic time. */
    fun noteChange(nowNanos: Long) {
        lastChangeNanos = nowNanos
        fired = false
    }

    /** Returns {@code true} exactly once after a change has gone quiet for the window. */
    fun takeDue(nowNanos: Long): Boolean {
        if (fired) {
            return false
        }
        if (nowNanos - lastChangeNanos >= debounceNanos) {
            fired = true
            return true
        }
        return false
    }

    /** Returns whether a change is still waiting for its quiet window. */
    fun pending(): Boolean = !fired
}
