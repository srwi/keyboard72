package com.lurebat.keyboard71

import com.jormy.nin.NINLib

object UnicodeHelper {

    /**
     * Converts a count of UTF-8 bytes backwards from the end of [str]
     * into the corresponding number of UTF-16 code units (Java/Kotlin String characters).
     */
    fun getUnicodeBackIndex(str: String?, byteCount: Int): Int {
        if (str.isNullOrEmpty() || byteCount <= 0) return 0
        try {
            val result = NINLib.getUnicodeBackIndex(str, byteCount)
            if (result >= 0) return minOf(result, str.length)
        } catch (_: Throwable) {
            // Native lib not loaded or test environment
        }
        return fallbackUnicodeBackIndex(str, byteCount)
    }

    /**
     * Converts a count of UTF-8 bytes forwards from the beginning of [str]
     * into the corresponding number of UTF-16 code units (Java/Kotlin String characters).
     */
    fun getUnicodeFrontIndex(str: String?, byteCount: Int): Int {
        if (str.isNullOrEmpty() || byteCount <= 0) return 0
        try {
            val result = NINLib.getUnicodeFrontIndex(str, byteCount)
            if (result >= 0) return minOf(result, str.length)
        } catch (_: Throwable) {
            // Native lib not loaded or test environment
        }
        return fallbackUnicodeFrontIndex(str, byteCount)
    }

    fun fallbackUnicodeBackIndex(str: String, byteCount: Int): Int {
        if (str.isEmpty() || byteCount <= 0) return 0
        val bytes = str.toByteArray(Charsets.UTF_8)
        if (byteCount >= bytes.size) return str.length

        // Index in byte array where we start counting towards the end
        var startIndex = bytes.size - byteCount

        // If startIndex is in the middle of a multi-byte UTF-8 sequence (continuation byte: 10xxxxxx),
        // adjust backwards to include the leading byte.
        while (startIndex > 0 && (bytes[startIndex].toInt() and 0xC0) == 0x80) {
            startIndex--
        }

        val subBytes = bytes.copyOfRange(startIndex, bytes.size)
        return String(subBytes, Charsets.UTF_8).length
    }

    fun fallbackUnicodeFrontIndex(str: String, byteCount: Int): Int {
        if (str.isEmpty() || byteCount <= 0) return 0
        val bytes = str.toByteArray(Charsets.UTF_8)
        if (byteCount >= bytes.size) return str.length

        var endIndex = byteCount
        // If endIndex lands on a continuation byte, adjust forward to include the full character.
        while (endIndex < bytes.size && (bytes[endIndex].toInt() and 0xC0) == 0x80) {
            endIndex++
        }

        val subBytes = bytes.copyOfRange(0, minOf(endIndex, bytes.size))
        return String(subBytes, Charsets.UTF_8).length
    }
}
