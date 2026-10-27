package com.lurebat.keyboard71

import com.jormy.nin.NINLib

object CursorMovementHelper {

    /**
     * Determines how far to move cursor and candidate when dragging left.
     * Returns Pair(earlier, later) where earlier and later are positive character deltas.
     */
    fun processLeft(textBefore: String): Pair<Int, Int> {
        if (textBefore.isEmpty()) return Pair(0, 0)
        try {
            val result = NINLib.processSoftKeyboardCursorMovementLeft(textBefore)
            val earlier = (result shr 32).toInt()
            val later = (result and 0xFFFFFFFFL).toInt()
            if (earlier > 0 || later > 0) {
                return Pair(minOf(earlier, textBefore.length), minOf(later, textBefore.length))
            }
        } catch (_: Throwable) {
            // Native lib not loaded or test environment
        }

        // Fallback: 1 code point
        val step = if (textBefore.length >= 2 && Character.isSurrogatePair(textBefore[textBefore.length - 2], textBefore[textBefore.length - 1])) {
            2
        } else {
            1
        }
        return Pair(step, step)
    }

    /**
     * Determines how far to move cursor and candidate when dragging right.
     * Returns Pair(earlier, later) where earlier and later are positive character deltas.
     */
    fun processRight(textAfter: String): Pair<Int, Int> {
        if (textAfter.isEmpty()) return Pair(0, 0)
        try {
            val result = NINLib.processSoftKeyboardCursorMovementRight(textAfter)
            val earlier = (result shr 32).toInt()
            val later = (result and 0xFFFFFFFFL).toInt()
            if (earlier > 0 || later > 0) {
                return Pair(minOf(earlier, textAfter.length), minOf(later, textAfter.length))
            }
        } catch (_: Throwable) {
            // Native lib not loaded or test environment
        }

        // Fallback: 1 code point
        val step = if (textAfter.length >= 2 && Character.isSurrogatePair(textAfter[0], textAfter[1])) {
            2
        } else {
            1
        }
        return Pair(step, step)
    }
}
