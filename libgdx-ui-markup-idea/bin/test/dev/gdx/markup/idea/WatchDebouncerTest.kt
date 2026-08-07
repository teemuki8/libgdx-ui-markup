package dev.gdx.markup.idea

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchDebouncerTest {
    private val debounceNanos = 300_000_000L
    private val start = 1_000_000_000L

    @Test
    fun firesOnceAfterQuietWindow() {
        val debouncer = WatchDebouncer(debounceNanos)
        debouncer.noteChange(start)
        assertFalse(debouncer.takeDue(start + 100_000_000L), "not quiet yet")
        assertTrue(debouncer.pending())
        assertTrue(debouncer.takeDue(start + 400_000_000L), "quiet window elapsed")
        assertFalse(debouncer.pending())
        assertFalse(debouncer.takeDue(start + 500_000_000L), "fires exactly once")
    }

    @Test
    fun repeatedChangesExtendTheWindow() {
        val debouncer = WatchDebouncer(debounceNanos)
        debouncer.noteChange(start)
        debouncer.noteChange(start + 200_000_000L)
        assertFalse(debouncer.takeDue(start + 400_000_000L),
            "window restarts on each change")
        assertTrue(debouncer.takeDue(start + 600_000_000L))
    }

    @Test
    fun doesNothingWithoutChanges() {
        val debouncer = WatchDebouncer(debounceNanos)
        assertFalse(debouncer.takeDue(start))
        assertFalse(debouncer.pending())
    }
}
