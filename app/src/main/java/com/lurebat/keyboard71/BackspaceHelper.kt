package com.lurebat.keyboard71

import com.jormy.nin.NINLib
import java.text.BreakIterator

object BackspaceHelper {

    /**
     * Determines how many UTF-16 code units (characters) to delete from [backbuf]
     * immediately before the cursor.
     */
    fun processBackspace(backbuf: String, mode: String?, singleCharacterMode: Boolean): Int {
        if (backbuf.isEmpty()) return 0
        if (singleCharacterMode || mode == null || mode.isEmpty()) {
            return fallbackBackspace(backbuf, mode, singleCharacterMode)
        }
        try {
            val safeBuf = if (backbuf.length > 120) backbuf.substring(backbuf.length - 120) else backbuf
            val count = NINLib.processBackspaceAllowance(safeBuf, mode, if (singleCharacterMode) 1 else 0)
            if (count > 0) return minOf(count, backbuf.length)
        } catch (_: Throwable) {
            // Native lib not loaded or test environment
        }
        return fallbackBackspace(backbuf, mode, singleCharacterMode)
    }

    fun fallbackBackspace(backbuf: String, mode: String?, singleCharacterMode: Boolean): Int {
        if (backbuf.isEmpty()) return 0

        if (singleCharacterMode || mode == null) {
            // Delete 1 Unicode code point, safely handling surrogate pairs
            return if (backbuf.length >= 2 && Character.isSurrogatePair(backbuf[backbuf.length - 2], backbuf[backbuf.length - 1])) {
                2
            } else {
                1
            }
        }

        return when {
            mode == "C" || mode == ". " -> 0
            mode == "S" -> {
                // Delete sentence punctuation (. ! ? \n)
                // Skip trailing punctuation and whitespace at the cursor to find preceding boundary
                var pos = backbuf.length - 1
                while (pos >= 0 && (backbuf[pos] in " .!?\t\n\r")) {
                    pos--
                }
                if (pos < 0) {
                    backbuf.length
                } else {
                    val idx = backbuf.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), pos)
                    if (idx != -1) {
                        backbuf.length - (idx + 1)
                    } else {
                        backbuf.length
                    }
                }
            }
            mode == "L" -> {
                // Delete line (up to newline)
                // Skip trailing newline at cursor if any
                var pos = backbuf.length - 1
                while (pos >= 0 && (backbuf[pos] == '\n' || backbuf[pos] == '\r')) {
                    pos--
                }
                if (pos < 0) {
                    backbuf.length
                } else {
                    val idx = backbuf.lastIndexOf('\n', pos)
                    if (idx != -1) {
                        backbuf.length - (idx + 1)
                    } else {
                        backbuf.length
                    }
                }
            }
            mode == "emjf" -> {
                // Delete last word or emoji
                deleteLastWordOrEmoji(backbuf)
            }
            mode.startsWith("X:") -> {
                val count = mode.substring(2).toIntOrNull() ?: 1
                if (singleCharacterMode) {
                    var charsToDelete = 0
                    var remaining = count
                    var pos = backbuf.length
                    while (pos > 0 && remaining > 0) {
                        val step = if (pos >= 2 && Character.isSurrogatePair(backbuf[pos - 2], backbuf[pos - 1])) 2 else 1
                        charsToDelete += step
                        pos -= step
                        remaining--
                    }
                    charsToDelete
                } else {
                    deleteWords(backbuf, count)
                }
            }
            else -> {
                deleteLastWordOrEmoji(backbuf)
            }
        }
    }

    private fun deleteLastWordOrEmoji(text: String): Int {
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text)
        val end = iterator.last()
        var start = iterator.previous()
        // If trailing characters are whitespace, skip over them to delete the preceding word too
        while (start != BreakIterator.DONE && text.substring(start, end).isBlank()) {
            val prev = iterator.previous()
            if (prev == BreakIterator.DONE) break
            start = prev
        }
        return if (start != BreakIterator.DONE) end - start else text.length
    }

    private fun deleteWords(text: String, count: Int): Int {
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text)
        val end = iterator.last()
        var wordsFound = 0
        var currentBoundary = end
        while (wordsFound < count) {
            val prev = iterator.previous()
            if (prev == BreakIterator.DONE) {
                currentBoundary = 0
                break
            }
            val chunk = text.substring(prev, currentBoundary)
            if (chunk.isNotBlank()) {
                wordsFound++
            }
            currentBoundary = prev
        }
        return end - currentBoundary
    }
}
