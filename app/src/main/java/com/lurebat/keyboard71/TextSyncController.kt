package com.lurebat.keyboard71

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages text synchronization between Android's [InputConnection] and the keyboard engine.
 *
 * Tracks cursor selection and composing region (candidate) cleanly, handles backspace modes,
 * word replacements, Unicode byte/character conversions, and spacebar cursor dragging without
 * corrupting the state.
 */
class TextSyncController {

    var selectionStart: Int = 0
    var selectionEnd: Int = 0
    var candidateStart: Int = -1
    var candidateEnd: Int = -1

    var selectionMode: Boolean = false
    var didProcessTextOps: Boolean = false
    var lastTextOpTimeMillis: Long = 0

    val hasCandidate: Boolean
        get() = candidateStart != -1 && candidateEnd != -1 && candidateStart != candidateEnd

    val hasSelection: Boolean
        get() = selectionStart != selectionEnd

    fun onStartInputView(
        initialSelStart: Int,
        initialSelEnd: Int,
        initialBefore: CharSequence?,
        initialSelected: CharSequence?,
        initialAfter: CharSequence?,
        ic: InputConnection?,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        val beforeStr = initialBefore?.toString() ?: ic?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        val selectedStr = initialSelected?.toString() ?: ic?.getSelectedText(0)?.toString() ?: ""

        if (initialSelStart >= 0 && initialSelEnd >= 0) {
            selectionStart = initialSelStart
            selectionEnd = initialSelEnd
        } else {
            selectionStart = beforeStr.length
            selectionEnd = beforeStr.length + selectedStr.length
        }

        candidateStart = -1
        candidateEnd = -1
        selectionMode = false
        didProcessTextOps = false

        signalCursorCandidacyResult(ic, "startInputView", dispatchEvent)
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        ic: InputConnection?,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        val isRecentOwnOp = didProcessTextOps || (System.currentTimeMillis() - lastTextOpTimeMillis < 55)

        selectionStart = newSelStart
        selectionEnd = newSelEnd
        candidateStart = candidatesStart
        candidateEnd = candidatesEnd

        if (isRecentOwnOp) {
            didProcessTextOps = false
        } else {
            // External cursor movement (user clicked in text, hardware keyboard, etc.)
            signalCursorCandidacyResult(ic, "external", dispatchEvent)
        }
    }

    fun onViewClicked(ic: InputConnection?, dispatchEvent: (TextBoxEvent) -> Unit) {
        if (ic != null) {
            ic.setComposingRegion(-1, -1)
            candidateStart = -1
            candidateEnd = -1
            signalCursorCandidacyResult(ic, "onViewClicked", dispatchEvent)
        }
    }

    fun processTextOps(
        queue: ConcurrentLinkedQueue<TextOp>,
        ic: InputConnection,
        editorInfo: EditorInfo?,
        dispatchEvent: (TextBoxEvent) -> Unit,
        handleSpecial: (String) -> Unit
    ) {
        val bufferSize = queue.size
        if (bufferSize == 0) return

        ic.beginBatchEdit()
        try {
            for (i in 0 until bufferSize) {
                val op = queue.poll() ?: break
                val next = queue.peek()
                processOperation(op, next, ic, editorInfo, dispatchEvent, handleSpecial)
            }
        } finally {
            didProcessTextOps = true
            lastTextOpTimeMillis = System.currentTimeMillis()
            ic.endBatchEdit()
        }
    }

    fun processOperation(
        op: TextOp,
        next: TextOp?,
        ic: InputConnection,
        editorInfo: EditorInfo?,
        dispatchEvent: (TextBoxEvent) -> Unit,
        handleSpecial: (String) -> Unit
    ) {
        when (op) {
            is TextOp.MarkLiquid -> {
                val skipIpc = (next is TextOp.MarkLiquid || next is TextOp.Solidify)
                if (!skipIpc) {
                    ic.setComposingText(op.newString, 1)
                }
                val base = if (hasCandidate) candidateStart else minOf(selectionStart, selectionEnd)
                candidateStart = base
                candidateEnd = base + op.newString.length
                selectionStart = candidateEnd
                selectionEnd = candidateEnd
            }

            is TextOp.Solidify -> {
                when {
                    op.newString == "\n" -> {
                        var action = EditorInfo.IME_ACTION_UNSPECIFIED
                        val imeOptions = editorInfo?.imeOptions ?: 0
                        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                            action = imeOptions and EditorInfo.IME_MASK_ACTION
                        }
                        if (action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
                            ic.performEditorAction(action)
                        } else {
                            ic.commitText(op.newString, 1)
                        }
                    }

                    op.newString.startsWith("<{") && op.newString.endsWith("}>") -> {
                        val inner = op.newString.substring(2, op.newString.length - 2)
                        if (inner.isNotEmpty()) {
                            signalCursorCandidacyResult(ic, "external", dispatchEvent)
                            handleSpecial(inner)
                        }
                    }

                    else -> {
                        ic.commitText(op.newString, 1)
                    }
                }
                val base = if (hasCandidate) candidateStart else selectionStart
                selectionStart = base + op.newString.length
                selectionEnd = selectionStart
                candidateStart = -1
                candidateEnd = -1
            }

            is TextOp.SetSelection -> {
                performSetSelection(op.start, op.end, op.fromStart, op.signal, ic, dispatchEvent)
            }

            is TextOp.BackspaceReplacement -> {
                performBackReplacement(op.backIndexFromCursorBytes, op.oldString, op.newString, ic, dispatchEvent)
            }

            is TextOp.SimpleBackspace -> {
                performBackspacing(null, op.singleCharacterMode, ic, dispatchEvent)
            }

            is TextOp.BackspaceModed -> {
                performBackspacing(op.mode, true, ic, dispatchEvent)
            }

            is TextOp.DragCursorMove -> {
                performCursorMovement(op.xMovement, op.yMovement, op.selectionMode, ic, dispatchEvent)
            }

            is TextOp.DragCursorUp -> {
                // Release of cursor drag
            }

            is TextOp.RequestSelection -> {
                signalCursorCandidacyResult(ic, "requestsel", dispatchEvent)
            }

            is TextOp.MuCommand -> {
                performMuCommand(op.command, op.arg1, op.arg2, op.arg3, ic)
            }

            is TextOp.Special -> {
                handleSpecial(op.args)
            }
        }
    }

    fun performSetSelection(
        selectStartBytes: Int,
        selectEndBytes: Int,
        fromStart: Boolean,
        signal: Boolean,
        ic: InputConnection,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        if (selectStartBytes == 0 && selectEndBytes == 0 && !fromStart) {
            val target = if (hasCandidate) candidateEnd else selectionEnd
            ic.setComposingRegion(target, target)
            ic.setSelection(target, target)
            selectionStart = target
            selectionEnd = target
            candidateStart = target
            candidateEnd = target
        } else if (fromStart && selectStartBytes <= 0 && selectEndBytes <= 0) {
            var usedStart = selectionStart
            if (hasCandidate && candidateEnd == selectionStart) {
                usedStart = candidateStart
            }
            var preText = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
            if (hasCandidate) {
                val toCut = candidateEnd - candidateStart
                if (toCut <= preText.length) {
                    preText = preText.substring(0, preText.length - toCut)
                }
            }
            val uniBack = UnicodeHelper.getUnicodeBackIndex(preText, -selectStartBytes)
            val uniEnd = UnicodeHelper.getUnicodeBackIndex(preText, -selectEndBytes)
            val newStart = maxOf(0, usedStart - uniBack)
            val newEnd = maxOf(0, usedStart - uniEnd)

            ic.setComposingRegion(newStart, newEnd)
            ic.setSelection(newEnd, newEnd)
            selectionStart = newEnd
            selectionEnd = newEnd
            candidateStart = newStart
            candidateEnd = newEnd
        } else {
            val usedStart = selectionEnd
            var adjStart = selectStartBytes
            var adjEnd = selectEndBytes
            if (fromStart && candidateStart != -1) {
                val shifter = candidateStart - selectionEnd
                adjStart += shifter
                adjEnd += shifter
            }
            val preText = if (adjStart < 0 || adjEnd < 0) ic.getTextBeforeCursor(200, 0)?.toString() ?: "" else ""
            val postText = if (adjStart > 0 || adjEnd > 0) ic.getTextAfterCursor(200, 0)?.toString() ?: "" else ""

            val uniStart = if (adjStart < 0) -UnicodeHelper.getUnicodeBackIndex(preText, -adjStart)
                           else if (adjStart > 0) UnicodeHelper.getUnicodeFrontIndex(postText, adjStart)
                           else 0
            val uniEnd = if (adjEnd < 0) -UnicodeHelper.getUnicodeBackIndex(preText, -adjEnd)
                         else if (adjEnd > 0) UnicodeHelper.getUnicodeFrontIndex(postText, adjEnd)
                         else 0

            val newStart = maxOf(0, usedStart + uniStart)
            val newEnd = maxOf(0, usedStart + uniEnd)

            ic.setComposingRegion(newStart, newEnd)
            ic.setSelection(newEnd, newEnd)
            selectionStart = newEnd
            selectionEnd = newEnd
            candidateStart = newStart
            candidateEnd = newEnd
        }
        if (signal) {
            signalCursorCandidacyResult(ic, "setselle", dispatchEvent)
        }
    }

    fun performBackReplacement(
        rawBackIndexBytes: Int,
        oldString: String,
        replacement: String,
        ic: InputConnection,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        val beforeCursor = ic.getTextBeforeCursor(200, 0)?.toString() ?: ""
        var theBackBuffer = beforeCursor
        var baseCursor = selectionStart

        if (hasCandidate) {
            val candLen = candidateEnd - candidateStart
            if (candLen <= theBackBuffer.length) {
                theBackBuffer = theBackBuffer.substring(0, theBackBuffer.length - candLen)
                baseCursor -= candLen
            }
        }

        val charBackIndex = UnicodeHelper.getUnicodeBackIndex(theBackBuffer, rawBackIndexBytes)
        val replStart = baseCursor - charBackIndex
        if (replStart >= 0) {
            val replacingAcross = charBackIndex < oldString.length

            ic.setSelection(replStart, replStart)
            ic.setComposingRegion(replStart, replStart)
            ic.deleteSurroundingText(0, oldString.length)
            ic.commitText(replacement, 1)

            val delta = replacement.length - oldString.length
            selectionStart += delta
            selectionEnd += delta
            if (candidateStart != -1) candidateStart += delta
            if (candidateEnd != -1) candidateEnd += delta

            if (hasCandidate) {
                ic.setComposingRegion(candidateStart, candidateEnd)
            }
            ic.setSelection(selectionStart, selectionEnd)

            if (replacingAcross) {
                signalCursorCandidacyResult(ic, "backrepl", dispatchEvent)
            }
        }
    }

    fun performBackspacing(
        mode: String?,
        singleCharacterMode: Boolean,
        ic: InputConnection,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        if (hasCandidate) {
            ic.setComposingText("", 1)
            val startPos = minOf(candidateStart, selectionStart)
            candidateStart = -1
            candidateEnd = -1
            selectionStart = startPos
            selectionEnd = startPos
            return
        }

        if (hasSelection) {
            ic.commitText("", 1)
            val minPos = minOf(selectionStart, selectionEnd)
            selectionStart = minPos
            selectionEnd = minPos
            candidateStart = -1
            candidateEnd = -1
            return
        }

        val backBuf = ic.getTextBeforeCursor(120, 0)?.toString() ?: ""
        if (backBuf.isNotEmpty()) {
            val delCount = BackspaceHelper.processBackspace(backBuf, mode, singleCharacterMode)
            if (delCount > 0) {
                ic.deleteSurroundingText(delCount, 0)
                selectionStart = maxOf(0, selectionStart - delCount)
                selectionEnd = selectionStart
            }
        } else {
            val eventTime = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0))
            ic.sendKeyEvent(KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0))
        }
    }

    fun performCursorMovement(
        xMovement: Int,
        yMovement: Int,
        dragSelectionMode: Boolean,
        ic: InputConnection,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        val activeSelectionMode = dragSelectionMode || selectionMode

        if (xMovement < 0) {
            val charSeq = ic.getTextBeforeCursor(120, 0)
            if (charSeq != null) {
                var theStr = charSeq.toString()
                var usedCurPos = selectionStart
                if (hasCandidate && candidateEnd == selectionStart) {
                    usedCurPos = candidateStart
                    val diff = candidateEnd - candidateStart
                    if (diff > 0 && diff <= theStr.length) {
                        theStr = theStr.substring(0, theStr.length - diff)
                    }
                }

                val (earlier, later) = CursorMovementHelper.processLeft(theStr)
                val earlPoint = maxOf(0, usedCurPos - earlier)
                val laterPoint = maxOf(0, usedCurPos - later)

                if (activeSelectionMode) {
                    ic.setSelection(earlPoint, selectionEnd)
                    selectionStart = earlPoint
                } else {
                    ic.setComposingRegion(earlPoint, laterPoint)
                    ic.setSelection(earlPoint, earlPoint)
                    selectionStart = earlPoint
                    selectionEnd = earlPoint
                    candidateStart = earlPoint
                    candidateEnd = laterPoint
                }
                signalCursorCandidacyResult(ic, "cursordrag", dispatchEvent)
            }
        } else if (xMovement > 0) {
            val charSeq2 = ic.getTextAfterCursor(120, 0)
            if (charSeq2 != null) {
                val theStr2 = charSeq2.toString()
                var later2: Int
                var laterPoint: Int
                if (hasCandidate) {
                    laterPoint = candidateEnd
                    later2 = laterPoint
                } else {
                    val (earlier2, later22) = CursorMovementHelper.processRight(theStr2)
                    later2 = selectionStart + earlier2
                    laterPoint = selectionStart + later22
                }

                if (activeSelectionMode) {
                    ic.setSelection(selectionStart, laterPoint)
                    selectionEnd = laterPoint
                } else {
                    ic.setComposingRegion(later2, laterPoint)
                    ic.setSelection(laterPoint, laterPoint)
                    selectionStart = laterPoint
                    selectionEnd = laterPoint
                    candidateStart = later2
                    candidateEnd = laterPoint
                }
                signalCursorCandidacyResult(ic, "cursordrag", dispatchEvent)
            }
        }
    }

    fun performMuCommand(cmd: String?, a1: String?, a2: String?, a3: String?, ic: InputConnection) {
        if (cmd == "retypebksp") {
            ic.setComposingText("", 0)
            candidateStart = selectionStart
            candidateEnd = selectionStart
        }
    }

    fun signalCursorCandidacyResult(
        ic: InputConnection?,
        mode: String?,
        dispatchEvent: (TextBoxEvent) -> Unit
    ) {
        if (ic == null) {
            dispatchEvent(TextBoxEvent.Reset)
            return
        }

        if (hasCandidate && (selectionEnd < candidateStart || selectionStart > candidateEnd)) {
            ic.finishComposingText()
            candidateStart = -1
            candidateEnd = -1
        }

        val preTextSeq = ic.getTextBeforeCursor(200, 0)
        val postTextSeq = ic.getTextAfterCursor(200, 0)
        var preText = preTextSeq?.toString() ?: ""
        var postText = postTextSeq?.toString() ?: ""
        var curWord = ""

        if (hasCandidate) {
            val candLen = candidateEnd - candidateStart
            if (selectionEnd == candidateEnd || (selectionEnd >= candidateStart && selectionEnd <= candidateEnd)) {
                val useLen = minOf(candLen, preText.length)
                curWord = preText.substring(preText.length - useLen)
                preText = preText.substring(0, preText.length - useLen)
            } else if (selectionStart == candidateStart) {
                val useLen = minOf(candLen, postText.length)
                curWord = postText.substring(0, useLen)
                postText = postText.substring(useLen)
            } else {
                curWord = if (candLen <= preText.length) preText.takeLast(candLen) else ""
            }
        } else if (hasSelection) {
            curWord = ic.getSelectedText(0)?.toString() ?: ""
        }

        dispatchEvent(TextBoxEvent.Selection(curWord, preText, postText, mode))
    }
}
