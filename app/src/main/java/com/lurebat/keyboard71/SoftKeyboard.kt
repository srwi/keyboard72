@file:Suppress("NAME_SHADOWING")

package com.lurebat.keyboard71

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.jormy.nin.NINLib
import com.jormy.nin.NINLib.onChangeAppOrTextbox
import com.jormy.nin.NINLib.onExternalSelChange
import com.jormy.nin.NINLib.onTextSelection
import com.jormy.nin.NINLib.onWordDestruction
import com.lurebat.keyboard71.tasker.triggerBasicTaskerEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SoftKeyboard : InputMethodService() {
    val textBoxEventQueue: ConcurrentLinkedQueue<TextBoxEvent> = ConcurrentLinkedQueue()
    val textOpQueue: ConcurrentLinkedQueue<TextOp> = ConcurrentLinkedQueue()
    val textSyncController = TextSyncController()

    // With the screen off the keyboard's surface stays alive, so its render loop
    // would keep running with nothing pacing it. (The original app paused on lock
    // instead, which also stopped the keyboard on the lock screen.)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> ninView?.onPause()
                Intent.ACTION_SCREEN_ON -> ninView?.onResume()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        keyboard = this
        window.window?.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        // Field behavior from the launch page, kept current while the keyboard runs.
        FieldRules.current = runBlocking { FieldRules.flow(this@SoftKeyboard).first() }
        rulesScope.launch { FieldRules.flow(this@SoftKeyboard).collect { FieldRules.current = it } }
    }

    private val rulesScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        rulesScope.cancel()
        mainHandler.removeCallbacks(surfaceCheck)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        if (BuildConfig.DEBUG) {
            Log.d("NIN", "onUpdateSelection: old=($oldSelStart, $oldSelEnd), new=($newSelStart, $newSelEnd), cand=($candidatesStart, $candidatesEnd)")
        }
        textSyncController.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
            currentInputConnection, ::doTextEvent
        )
    }

    override fun onCreateInputView(): View {
        val view = ninView
        if (view == null) {
            ninView = NINView(this)
        } else {
            (view.parent as ViewGroup?)?.removeView(view)
        }
        // The keyboard view fills a frame that can also hold the autofill chips
        // and the boot screen.
        val root = FrameLayout(this)
        root.addView(ninView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val screen = bootScreen ?: BootScreen(this).also { bootScreen = it }
        (screen.parent as ViewGroup?)?.removeView(screen)
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inlineStrip.attach(root)
        return root
    }

    /** Shown over the keyboard while the engine starts (BootScreen.kt). */
    var bootScreen: BootScreen? = null
        private set

    // Password managers' inline autofill chips (Android 11+), over the top bar.
    @get:RequiresApi(Build.VERSION_CODES.R)
    private val inlineStrip by lazy { InlineSuggestionStrip(this) }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        inlineStrip.request()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        inlineStrip.show(response.inlineSuggestions)
        return true
    }

    override fun onStartInputView(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !restarting) inlineStrip.clear()
        bootScreen?.watch()

        Log.d("SoftKeyboard",
            "------------ jormoust Editor Info : ${attribute.packageName} | ${attribute.fieldName}|${attribute.inputType}"
        )
        val field = FieldRules.resolve(attribute)

        val fieldChange = TextBoxEvent.AppFieldChange(
            attribute.packageName, attribute.fieldName, field.mode, field.keyboardType, field.returnKeyType,
        )
        lastFieldChange = fieldChange
        doTextEvent(fieldChange)

        val initialBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialTextBeforeCursor(1000, 0) else currentInputConnection?.getTextBeforeCursor(1000, 0)
        val initialSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialSelectedText(0) else currentInputConnection?.getSelectedText(0)
        val initialAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialTextAfterCursor(1000, 0) else currentInputConnection?.getTextAfterCursor(1000, 0)

        textSyncController.onStartInputView(
            attribute.initialSelStart, attribute.initialSelEnd,
            initialBefore, initialSelected, initialAfter,
            currentInputConnection, ::doTextEvent
        )

        mainHandler.removeCallbacks(surfaceCheck)
        mainHandler.postDelayed(surfaceCheck, SURFACE_CHECK_DELAY_MS)
    }

    // Showing the keyboard again shortly after hiding it (seen on recent Android versions) can
    // bring the window back without its GL surface: the SurfaceView released it
    // on hide and is never told to recreate it, so the keyboard stays blank until
    // the input method is restarted. Toggling the view itself does not help;
    // cycling the window's visibility makes the SurfaceView create a new surface.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFieldChange: TextBoxEvent.AppFieldChange? = null

    /** Tells a freshly restarted engine about the current field and text (see NINView.restartEngine). */
    fun resendField() = mainHandler.post {
        lastFieldChange?.let { doTextEvent(it) }
        textSyncController.signalCursorCandidacyResult(currentInputConnection, "restart", ::doTextEvent)
    }
    private var surfaceRecoveries = 0
    private val surfaceCheck = Runnable { restoreLostSurface() }

    private fun restoreLostSurface() {
        val view = ninView ?: return
        if (!isInputViewShown || view.holder.surface.isValid) {
            if (surfaceRecoveries > 0) Log.i(TAG, "surface restored")
            surfaceRecoveries = 0
            return
        }
        if (surfaceRecoveries == MAX_SURFACE_RECOVERIES) {
            Log.e(TAG, "surface still missing after $surfaceRecoveries recoveries")
            surfaceRecoveries = 0
            return
        }
        surfaceRecoveries += 1
        Log.w(TAG, "keyboard shown without a surface; cycling window visibility")
        val decor = window.window?.decorView ?: return
        decor.visibility = View.INVISIBLE
        mainHandler.post { decor.visibility = View.VISIBLE }
        mainHandler.postDelayed(surfaceCheck, SURFACE_CHECK_DELAY_MS)
    }

    @Deprecated("Deprecated in Java")
    override fun onViewClicked(focusChanged: Boolean) {
        textSyncController.onViewClicked(currentInputConnection, ::doTextEvent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    fun relayDelayedEvents() {
        while (true) {
            val event = textBoxEventQueue.poll()
            if (event != null && BuildConfig.DEBUG) {
                Log.d("NIN", "Relaying delayed event: $event")
            }
            when (event) {
                is TextBoxEvent.AppFieldChange -> {
                    NINLib.setFieldTypes(event.keyboardType, event.returnKeyType)
                    onChangeAppOrTextbox(event.packageName, event.field, event.mode)
                }

                TextBoxEvent.Reset -> onExternalSelChange()

                is TextBoxEvent.Selection -> onTextSelection(
                    event.textBefore,
                    event.currentWord,
                    event.textAfter,
                    event.mode
                )

                is TextBoxEvent.WordDestruction -> onWordDestruction(
                    event.destroyedWord,
                    event.destroyedString
                )

                null -> break
            }
        }
    }

    fun processTextOps() {
        val ic = keyboard?.currentInputConnection ?: return
        textSyncController.processTextOps(
            textOpQueue,
            ic,
            currentInputEditorInfo,
            ::doTextEvent,
            ::parseSpecialText
        )
    }

    private fun parseSpecialText(args: String) {
        val ic = currentInputConnection ?: return
        val first = args.firstOrNull() ?: return
        val rest = args.substring(1).trim()
        val parts = rest.let {
            val temp = it.split("\\|".toRegex()).toTypedArray()
            if (temp.isEmpty()) {
                arrayOf(it)
            } else {
                temp
            }
        }

        when (first) {
            'k' -> {
                val code = WordHelper.parseKeyCode(parts[0])
                val modifiers = if (parts.size > 1) WordHelper.parseModifiers(parts[1]) else 0
                val repeat = if (parts.size > 2) parts[2].toInt() else 0
                val flags =
                    if (parts.size > 3) parts[3].toInt() else KeyEvent.FLAG_SOFT_KEYBOARD
                keyDownUp(ic, code, modifiers, repeat, flags)
            }

            'c' -> ic.performContextMenuAction(
                when (parts[0].uppercase()) {
                    "0", "CUT" -> android.R.id.cut
                    "1", "COPY" -> android.R.id.copy
                    "2", "PASTE" -> android.R.id.paste
                    "3", "SELECT_ALL" -> android.R.id.selectAll
                    "4", "START_SELECT" -> android.R.id.startSelectingText
                    "5", "STOP_SELECT" -> android.R.id.stopSelectingText
                    "6", "SWITCH_KEYBOARD" -> android.R.id.switchInputMethod
                    else -> parts[0].toIntOrNull() ?: 0
                }
            )

            's' -> textSyncController.selectionMode = !textSyncController.selectionMode

            't' -> {
                val before = currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                val after = currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""
                this.triggerBasicTaskerEvent(rest, before, after)
            }
        }
    }

    companion object {
        private const val TAG = "SoftKeyboard"

        private const val SURFACE_CHECK_DELAY_MS = 150L
        private const val MAX_SURFACE_RECOVERIES = 2

        var keyboard: SoftKeyboard? = null
        var ninView: NINView? = null

        fun doTextOp(op: TextOp) = keyboard?.let { k ->
            k.textOpQueue.add(op)
            Handler(Looper.getMainLooper()).post { k.processTextOps() }
        }

        fun doTextEvent(event: TextBoxEvent) = keyboard?.textBoxEventQueue?.add(event).also {
            NINView.wakeRenderer()  // relayed by the next frame
        }

        private fun keyDownUp(
            ic: InputConnection,
            keyEventCode: Int,
            modifiers: Int,
            repeat: Int,
            flags: Int
        ) {
            val eventTime = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(
                    eventTime,
                    eventTime,
                    KeyEvent.ACTION_DOWN,
                    keyEventCode,
                    repeat,
                    modifiers,
                    -1,
                    0,
                    flags
                )
            )
            ic.sendKeyEvent(
                KeyEvent(
                    eventTime,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    keyEventCode,
                    repeat,
                    modifiers,
                    -1,
                    0,
                    flags
                )
            )
        }
    }
}
