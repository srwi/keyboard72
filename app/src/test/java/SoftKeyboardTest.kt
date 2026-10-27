package com.lurebat.keyboard71

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory simulation of Android's [InputConnection] for thorough unit testing.
 */
class MockInputConnection(initialText: String = "", initialSelStart: Int = initialText.length, initialSelEnd: Int = initialSelStart) : InputConnection {
    val buffer = StringBuilder(initialText)
    var selStart: Int = initialSelStart
    var selEnd: Int = initialSelEnd
    var compStart: Int = -1
    var compEnd: Int = -1

    val currentText: String
        get() = buffer.toString()

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val cursor = minOf(selStart, selEnd)
        val start = maxOf(0, cursor - n)
        return buffer.substring(start, cursor)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        val cursor = maxOf(selStart, selEnd)
        val end = minOf(buffer.length, cursor + n)
        return buffer.substring(cursor, end)
    }

    override fun getSelectedText(flags: Int): CharSequence {
        val start = minOf(selStart, selEnd)
        val end = maxOf(selStart, selEnd)
        return if (start != end) buffer.substring(start, end) else ""
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        selStart = maxOf(0, minOf(buffer.length, start))
        selEnd = maxOf(0, minOf(buffer.length, end))
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (start in 0..buffer.length && end in 0..buffer.length && start <= end) {
            compStart = start
            compEnd = end
        } else {
            compStart = -1
            compEnd = -1
        }
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (compStart != -1 && compEnd != -1 && compStart <= compEnd) {
            buffer.replace(compStart, compEnd, text.toString())
            compEnd = compStart + text.length
        } else {
            val start = minOf(selStart, selEnd)
            val end = maxOf(selStart, selEnd)
            buffer.replace(start, end, text.toString())
            compStart = start
            compEnd = start + text.length
        }

        if (newCursorPosition > 0) {
            selStart = compEnd + (newCursorPosition - 1)
            selEnd = selStart
        } else {
            selStart = compStart + newCursorPosition
            selEnd = selStart
        }
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (compStart != -1 && compEnd != -1 && compStart <= compEnd) {
            buffer.replace(compStart, compEnd, text.toString())
            val insertEnd = compStart + text.length
            compStart = -1
            compEnd = -1
            selStart = insertEnd
            selEnd = insertEnd
        } else {
            val start = minOf(selStart, selEnd)
            val end = maxOf(selStart, selEnd)
            buffer.replace(start, end, text.toString())
            val insertEnd = start + text.length
            selStart = insertEnd
            selEnd = insertEnd
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val cursor = selStart
        val delStart = maxOf(0, cursor - beforeLength)
        val delEnd = minOf(buffer.length, cursor + afterLength)
        buffer.delete(delStart, delEnd)
        selStart = delStart
        selEnd = delStart
        return true
    }

    override fun finishComposingText(): Boolean {
        compStart = -1
        compEnd = -1
        return true
    }

    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun performEditorAction(editorAction: Int): Boolean = true
    override fun performContextMenuAction(id: Int): Boolean = true
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)
    override fun commitCompletion(text: CompletionInfo?): Boolean = true
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
    val sentKeyEvents = mutableListOf<KeyEvent>()
    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) sentKeyEvents.add(event)
        return true
    }
    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
    override fun getHandler(): Handler? = null
    override fun closeConnection() {}
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}

class SoftKeyboardTest {

    // --- UnicodeHelper Tests ---

    @Test
    fun testUnicodeBackIndexAscii() {
        val text = "hello"
        assertEquals(0, UnicodeHelper.fallbackUnicodeBackIndex(text, 0))
        assertEquals(1, UnicodeHelper.fallbackUnicodeBackIndex(text, 1))
        assertEquals(3, UnicodeHelper.fallbackUnicodeBackIndex(text, 3))
        assertEquals(5, UnicodeHelper.fallbackUnicodeBackIndex(text, 5))
        assertEquals(5, UnicodeHelper.fallbackUnicodeBackIndex(text, 10)) // capped at length
    }

    @Test
    fun testUnicodeBackIndexGermanUmlauts() {
        // 'ä' is 2 bytes in UTF-8
        val text = "hällo" // bytes: h(1) ä(2) l(1) l(1) o(1) = 6 bytes
        assertEquals(1, UnicodeHelper.fallbackUnicodeBackIndex(text, 1)) // 'o'
        assertEquals(2, UnicodeHelper.fallbackUnicodeBackIndex(text, 2)) // 'lo'
        assertEquals(3, UnicodeHelper.fallbackUnicodeBackIndex(text, 3)) // 'llo'
        assertEquals(4, UnicodeHelper.fallbackUnicodeBackIndex(text, 4)) // 'llo' + 1 byte of ä -> includes full 'ä'
        assertEquals(4, UnicodeHelper.fallbackUnicodeBackIndex(text, 5)) // 'ällo'
        assertEquals(5, UnicodeHelper.fallbackUnicodeBackIndex(text, 6)) // 'hällo'
    }

    @Test
    fun testUnicodeBackIndexEmojiSurrogates() {
        // 👋 is 4 bytes in UTF-8, 2 chars in UTF-16
        val text = "hi👋" // bytes: h(1) i(1) 👋(4) = 6 bytes, 4 UTF-16 chars
        assertEquals(2, UnicodeHelper.fallbackUnicodeBackIndex(text, 4)) // emoji 👋 is 2 code units
        assertEquals(3, UnicodeHelper.fallbackUnicodeBackIndex(text, 5)) // 'i' + 👋
        assertEquals(4, UnicodeHelper.fallbackUnicodeBackIndex(text, 6)) // 'hi👋'
    }

    @Test
    fun testUnicodeFrontIndex() {
        val text = "hällo"
        assertEquals(1, UnicodeHelper.fallbackUnicodeFrontIndex(text, 1)) // 'h'
        assertEquals(2, UnicodeHelper.fallbackUnicodeFrontIndex(text, 2)) // 'h' + half of 'ä' -> adjusted to include 'ä'
        assertEquals(2, UnicodeHelper.fallbackUnicodeFrontIndex(text, 3)) // 'hä' (3 bytes)
        assertEquals(3, UnicodeHelper.fallbackUnicodeFrontIndex(text, 4)) // 'häl' (4 bytes)
    }

    // --- BackspaceHelper Tests ---

    @Test
    fun testBackspaceSingleChar() {
        assertEquals(1, BackspaceHelper.fallbackBackspace("abc", null, true))
        assertEquals(1, BackspaceHelper.fallbackBackspace("hello", null, false))
    }

    @Test
    fun testBackspaceSurrogateEmoji() {
        val withEmoji = "hello👋"
        // Should delete 2 chars (the surrogate pair)
        assertEquals(2, BackspaceHelper.fallbackBackspace(withEmoji, null, true))
    }

    @Test
    fun testBackspaceSentence() {
        val text = "Hello world. How are you?"
        val toDelete = BackspaceHelper.fallbackBackspace(text, "S", false)
        // Should delete " How are you?"
        assertEquals(13, toDelete)

        // Without trailing question mark
        val text2 = "Hello world. How are you"
        val toDelete2 = BackspaceHelper.fallbackBackspace(text2, "S", false)
        assertEquals(12, toDelete2)

        // Only one sentence
        val text3 = "Only one sentence."
        assertEquals(text3.length, BackspaceHelper.fallbackBackspace(text3, "S", false))
    }

    @Test
    fun testBackspaceLine() {
        val text = "First line\nSecond line"
        val toDelete = BackspaceHelper.fallbackBackspace(text, "L", false)
        // Should delete "Second line"
        assertEquals("Second line".length, toDelete)

        // Line with trailing newline
        val text2 = "First line\nSecond line\n"
        val toDelete2 = BackspaceHelper.fallbackBackspace(text2, "L", false)
        assertEquals("Second line\n".length, toDelete2)
    }

    @Test
    fun testBackspaceWordCount() {
        val text = "one two three four"
        val toDelete = BackspaceHelper.fallbackBackspace(text, "X:2", false)
        // Should delete "three four" (plus space)
        val remaining = text.substring(0, text.length - toDelete)
        assertEquals("one two ", remaining)
    }

    @Test
    fun testBackspaceNoopModes() {
        assertEquals(0, BackspaceHelper.fallbackBackspace("abc", "C", false))
        assertEquals(0, BackspaceHelper.fallbackBackspace("abc", ". ", false))
    }

    // --- TextSyncController End-to-End Tests ---

    @Test
    fun testTypingWordComposingAndSolidify() {
        val controller = TextSyncController()
        val ic = MockInputConnection()
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(-1, -1, "", "", "", ic) { events.add(it) }
        assertEquals(0, controller.selectionStart)
        assertEquals(0, controller.selectionEnd)
        assertFalse(controller.hasCandidate)

        // Type 'h'
        controller.processOperation(TextOp.MarkLiquid("h"), null, ic, null, { events.add(it) }, {})
        assertEquals("h", ic.currentText)
        assertTrue(controller.hasCandidate)
        assertEquals(0, controller.candidateStart)
        assertEquals(1, controller.candidateEnd)
        assertEquals(1, controller.selectionEnd)

        // Type 'he'
        controller.processOperation(TextOp.MarkLiquid("he"), null, ic, null, { events.add(it) }, {})
        assertEquals("he", ic.currentText)
        assertEquals(0, controller.candidateStart)
        assertEquals(2, controller.candidateEnd)

        // Type 'hello'
        controller.processOperation(TextOp.MarkLiquid("hello"), null, ic, null, { events.add(it) }, {})
        assertEquals("hello", ic.currentText)
        assertEquals(5, controller.candidateEnd)

        // Solidify "hello "
        controller.processOperation(TextOp.Solidify("hello "), null, ic, null, { events.add(it) }, {})
        assertEquals("hello ", ic.currentText)
        assertFalse(controller.hasCandidate)
        assertEquals(6, controller.selectionStart)
        assertEquals(6, controller.selectionEnd)
    }

    @Test
    fun testAutocorrectBackspaceReplacement() {
        val controller = TextSyncController()
        // Buffer has "hte" with cursor at end
        val ic = MockInputConnection("hte", 3, 3)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(3, 3, "hte", "", "", ic) { events.add(it) }
        assertEquals(3, controller.selectionStart)

        // Replace "hte" with "the" (3 bytes back)
        controller.performBackReplacement(3, "hte", "the", ic) { events.add(it) }

        assertEquals("the", ic.currentText)
        assertEquals(3, controller.selectionStart)
        assertEquals(3, controller.selectionEnd)
        assertEquals(3, ic.selStart)
    }

    @Test
    fun testBackspaceDeletesComposingWord() {
        val controller = TextSyncController()
        val ic = MockInputConnection()
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(0, 0, "", "", "", ic) { events.add(it) }

        // Start composing "typing"
        controller.processOperation(TextOp.MarkLiquid("typing"), null, ic, null, { events.add(it) }, {})
        assertEquals("typing", ic.currentText)
        assertTrue(controller.hasCandidate)

        // Backspace should delete the entire candidate
        controller.processOperation(TextOp.SimpleBackspace(false), null, ic, null, { events.add(it) }, {})
        assertEquals("", ic.currentText)
        assertFalse(controller.hasCandidate)
        assertEquals(0, controller.selectionStart)
    }

    @Test
    fun testBackspaceDeletesSingleCharacterWhenNoCandidate() {
        val controller = TextSyncController()
        val ic = MockInputConnection("Hello!", 6, 6)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(6, 6, "Hello!", "", "", ic) { events.add(it) }

        controller.processOperation(TextOp.SimpleBackspace(true), null, ic, null, { events.add(it) }, {})
        assertEquals("Hello", ic.currentText)
        assertEquals(5, controller.selectionStart)
        assertEquals(5, ic.selStart)
    }

    @Test
    fun testBackspaceDeletesSelectionWhenTextSelected() {
        val controller = TextSyncController()
        val ic = MockInputConnection("Hello beautiful world", 6, 15)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(6, 15, "Hello ", "beautiful", " world", ic) { events.add(it) }
        assertTrue(controller.hasSelection)

        controller.processOperation(TextOp.SimpleBackspace(false), null, ic, null, { events.add(it) }, {})
        assertEquals("Hello  world", ic.currentText)
        assertFalse(controller.hasSelection)
        assertEquals(6, controller.selectionStart)
    }

    @Test
    fun testExternalSelectionUpdateSignalsEngine() {
        val controller = TextSyncController()
        val ic = MockInputConnection("One two three", 3, 3)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(3, 3, "One", "", " two three", ic) { events.add(it) }
        events.clear()

        // User taps at index 7 (after "two")
        ic.setSelection(7, 7)
        controller.onUpdateSelection(3, 3, 7, 7, -1, -1, ic) { events.add(it) }

        assertEquals(1, events.size)
        val selEvent = events[0] as TextBoxEvent.Selection
        assertEquals("One two", selEvent.textBefore)
        assertEquals(" three", selEvent.textAfter)
        assertEquals("", selEvent.currentWord)
        assertEquals("external", selEvent.mode)
    }

    @Test
    fun testCandidateExtractedCorrectlyInSignal() {
        val controller = TextSyncController()
        val ic = MockInputConnection()
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(0, 0, "", "", "", ic) { events.add(it) }

        // Type "cat"
        controller.processOperation(TextOp.MarkLiquid("cat"), null, ic, null, { events.add(it) }, {})
        events.clear()

        controller.signalCursorCandidacyResult(ic, "test") { events.add(it) }
        assertEquals(1, events.size)
        val selEvent = events[0] as TextBoxEvent.Selection
        assertEquals("cat", selEvent.currentWord)
        assertEquals("", selEvent.textBefore)
        assertEquals("", selEvent.textAfter)
    }

    @Test
    fun testPerformSetSelectionEndMovement() {
        val controller = TextSyncController()
        val ic = MockInputConnection("First word, second word", 5, 5)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(5, 5, "First", "", " word, second word", ic) { events.add(it) }

        // Move 6 bytes forward
        controller.performSetSelection(0, 6, false, false, ic) { events.add(it) }
        assertEquals(11, controller.selectionStart)
        assertEquals(11, ic.selStart)
    }

    @Test
    fun testBackspaceEmptyBufferSendsDelKeyEvent() {
        val controller = TextSyncController()
        val ic = MockInputConnection("", 0, 0)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(0, 0, "", "", "", ic) { events.add(it) }
        controller.processOperation(TextOp.SimpleBackspace(true), null, ic, null, { events.add(it) }, {})

        // In JVM unit test environment with returnDefaultValues = true, KeyEvent getters return default values,
        // but verifying that two KeyEvents (DOWN + UP) were dispatched confirms the fallback deletion logic.
        assertEquals(2, ic.sentKeyEvents.size)
    }

    @Test
    fun testBackspaceEmojiSurrogatePairInController() {
        val controller = TextSyncController()
        val ic = MockInputConnection("Hello👋", 7, 7)
        val events = mutableListOf<TextBoxEvent>()

        controller.onStartInputView(7, 7, "Hello👋", "", "", ic) { events.add(it) }
        controller.processOperation(TextOp.SimpleBackspace(true), null, ic, null, { events.add(it) }, {})

        // Emoji 👋 is 2 chars, so length drops by 2 to "Hello"
        assertEquals("Hello", ic.currentText)
        assertEquals(5, controller.selectionStart)
    }

    @Test
    fun testSignalWordDestructionAndEvent() {
        // Ensure static method exists and does not crash even without an active keyboard service
        com.jormy.nin.SoftKeyboard.signalWordDestruction("apple", "apples")

        val event = TextBoxEvent.fromType(TextEventType.WORD_DESTRUCTION, "apple", "apples")
        assertTrue(event is TextBoxEvent.WordDestruction)
        val wordDes = event as TextBoxEvent.WordDestruction
        assertEquals("apple", wordDes.destroyedWord)
        assertEquals("apples", wordDes.destroyedString)
    }
}
