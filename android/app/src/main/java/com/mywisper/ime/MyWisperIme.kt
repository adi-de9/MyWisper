package com.mywisper.ime

import android.Manifest
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.mywisper.BuildConfig
import com.mywisper.app.MainActivity
import com.mywisper.audio.Recorder
import com.mywisper.core.Account
import com.mywisper.core.AppCategory
import com.mywisper.core.SyncRepo
import com.mywisper.core.ClipboardStore
import com.mywisper.core.Dictation
import com.mywisper.core.LiveQueue
import com.mywisper.core.Settings
import com.mywisper.core.TextRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The MyWisper keyboard: a normal QWERTY keyboard plus a mic in the toolbar.
 * Tap the mic, speak, tap again → Whisper + cleanup → the text is typed into the focused field.
 */
class MyWisperIme : InputMethodService(), KeyboardView.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var dictation: Dictation
    private var kb: KeyboardView? = null

    private var recorder: Recorder? = null
    private var job: Job? = null
    private var liveQueue: LiveQueue? = null
    /** The field the recording is for; the text goes there only if it is still focused. */
    private data class Target(val pkg: String?, val fieldId: Int, val category: String)
    private var target: Target? = null
    private var inputActive = false
    private var lastSpaceAt = 0L
    private var vibrate = true
    private var sound = false

    // clipboard panel + "Paste" chip
    private lateinit var clips: ClipboardStore
    private var clipboard: ClipboardManager? = null
    private var lastSeenClip: String? = null
    private val clearChip = Runnable { kb?.pasteChip = null }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClipboard(offerChip = true) }

    private val autoStop = Runnable { if (recorder?.isRecording == true) stopAndProcess() }
    private val resetStatus = Runnable { showIdleStatus() }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        dictation = Dictation(this)
        clips = ClipboardStore(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        SyncRepo.start(this) // keeps the synced dictionary fresh
        if (BuildConfig.DEBUG) registerTestHooks()
    }

    // Debug/qa builds only – automated tests without speaking (the keyboard must be open in a text box):
    //   adb shell am broadcast -a com.mywisper.TEST_AUDIO --es file /data/user/0/com.mywisper.app/files/x.wav -p com.mywisper.app
    //   adb shell am broadcast -a com.mywisper.TEST_TAP -p com.mywisper.app        (= tap the mic)
    private var testHooks: BroadcastReceiver? = null

    private fun registerTestHooks() {
        testHooks = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_TEST_TAP -> onMic()
                    ACTION_TEST_AUDIO -> {
                        val file = java.io.File(intent.getStringExtra("file") ?: return)
                        if (!file.exists() || recorder?.isRecording == true) return
                        Recorder.fakeAudio = file
                        startRecording()
                        // the WAV plays as the mic; stop when it has finished (16 kHz mono 16-bit + 0.4 s)
                        main.postDelayed({ if (recorder?.isRecording == true) stopAndProcess() }, (file.length() - 44) / 32 + 400)
                    }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_TEST_AUDIO); addAction(ACTION_TEST_TAP) }
        registerReceiver(testHooks, filter, RECEIVER_EXPORTED)
    }

    override fun onCreateInputView(): View = KeyboardView(this, this, Fonts.sans(this)).also { kb = it }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = true
    }

    override fun onFinishInput() {
        inputActive = false
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = kb ?: return
        vibrate = settings.vibrate
        sound = settings.sound
        // Look + layout from Settings → Keyboard.
        val scale = when (settings.kbHeight) { "small" -> 0.88f; "tall" -> 1.12f; else -> 1f }
        v.configure(KbTheme.from(this, settings), settings.numberRow, scale)
        v.clipboardEnabled = settings.clipboardHistory
        v.enterLabel = enterLabel(info)
        if (!restarting) {
            v.closeClipboard()
            v.page = when (info.inputType and InputType.TYPE_MASK_CLASS) {
                // Phone-style number pad for numbers, phone numbers, PINs and dates.
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Page.NUMPAD
                else -> Page.LETTERS
            }
        }
        readClipboard(offerChip = false)
        updateCaps()
        if (recorder?.isRecording != true && job?.isActive != true) showIdleStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard closed while recording: finish it (the text lands in the field, or on the clipboard).
        if (recorder?.isRecording == true) stopAndProcess()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateCaps()
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        testHooks?.let { unregisterReceiver(it) }
        main.removeCallbacksAndMessages(null)
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- clipboard ----------
    /**
     * Saves newly copied text into the keyboard's clipboard history (Android only lets the keyboard read the
     * clipboard while it's in use). offerChip = also show a one-tap "Paste" chip for ~1 minute.
     */
    private fun readClipboard(offerChip: Boolean) {
        if (!settings.clipboardHistory) return
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return // passwords etc.
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (text == lastSeenClip) return
        lastSeenClip = text
        clips.add(text)
        if (kb?.clipboardOpen == true) kb?.clipItems = clips.items()
        if (offerChip) {
            kb?.pasteChip = text
            main.removeCallbacks(clearChip)
            main.postDelayed(clearChip, PASTE_CHIP_MS)
        }
    }

    override fun onClipboardOpen() {
        readClipboard(offerChip = false)
        kb?.clipItems = clips.items()
    }

    override fun onClipPaste(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onClipPin(text: String) {
        clips.togglePin(text)
        kb?.clipItems = clips.items()
        flash(if (clips.items().firstOrNull { it.text == text }?.pinned == true) "Pinned" else "Unpinned", KeyboardView.Tone.DONE)
    }

    override fun onClipDelete(text: String) {
        clips.delete(text)
        kb?.clipItems = clips.items()
    }

    override fun onPasteChip() {
        val text = kb?.pasteChip ?: return
        currentInputConnection?.commitText(text, 1)
        kb?.pasteChip = null
    }

    override fun onEditAction(action: KeyboardView.EditAction) {
        val ic = currentInputConnection ?: return
        val id = when (action) {
            KeyboardView.EditAction.SELECT_ALL -> android.R.id.selectAll
            KeyboardView.EditAction.CUT -> android.R.id.cut
            KeyboardView.EditAction.COPY -> android.R.id.copy
            KeyboardView.EditAction.PASTE -> android.R.id.paste
        }
        ic.performContextMenuAction(id)
        if (action == KeyboardView.EditAction.CUT || action == KeyboardView.EditAction.COPY) main.postDelayed({ readClipboard(offerChip = false) }, 150)
    }

    // ---------- status ----------
    private fun isPasswordField() = currentInputEditorInfo?.let { AppCategory.isPassword(it.inputType) } == true
    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showIdleStatus() {
        val v = kb ?: return
        when {
            isPasswordField() -> { v.mic = KeyboardView.Mic.DISABLED; v.setStatus("Voice is off in password fields") }
            !Account.isSignedIn -> { v.mic = KeyboardView.Mic.IDLE; v.setStatus("Sign in to MyWisper – tap here", opensApp = true) }
            !hasMicPermission() -> { v.mic = KeyboardView.Mic.IDLE; v.setStatus("Allow the microphone – tap here", opensApp = true) }
            else -> { v.mic = KeyboardView.Mic.IDLE; v.setStatus("Tap the mic to speak") }
        }
    }

    private fun flash(text: String, tone: KeyboardView.Tone, opensApp: Boolean = false) {
        kb?.setStatus(text, tone, opensApp)
        main.removeCallbacks(resetStatus)
        main.postDelayed(resetStatus, STATUS_MS)
    }

    private fun updateCaps() {
        val v = kb ?: return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        if (v.page == Page.LETTERS) v.setAutoCaps(info.inputType != 0 && ic.getCursorCapsMode(info.inputType) != 0)
    }

    private fun enterLabel(info: EditorInfo): String? {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> null
        }
    }

    // ---------- voice ----------
    override fun onMic() {
        onKeyFeedback(KeyType.ENTER)
        when {
            job?.isActive == true || liveQueue?.isActive == true && recorder?.isRecording != true -> {} // still working
            recorder?.isRecording == true -> stopAndProcess()
            else -> startRecording()
        }
    }

    private fun startRecording() {
        val v = kb ?: return
        val info = currentInputEditorInfo
        if (info == null || isPasswordField()) return showIdleStatus()
        if (!Account.isSignedIn || !hasMicPermission()) { showIdleStatus(); return openApp() }
        dictation.prepare() // connect to Groq while the user speaks
        val t = Target(info.packageName, info.fieldId, AppCategory.of(info.packageName, info.inputType))
        target = t
        // Live typing (Settings → Models): each sentence is typed when you pause.
        val queue = if (settings.liveTyping) LiveQueue(
            scope, dictation, t.category, AppCategory.label(this, t.pkg),
            insert = { text -> insert(text, t) },
            onQueued = { n -> if (recorder?.isRecording != true) kb?.setStatus(if (n > 0) "Typing… $n left" else "Typing…") },
            onDone = { typed, notTyped ->
                liveQueue = null
                showIdleStatus()
                if (notTyped.isNotEmpty()) {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("MyWisper", notTyped))
                    flash("Some text couldn't be typed – copied", KeyboardView.Tone.ERROR)
                } else flash(if (typed == 0) "No speech heard" else "Done · $typed sentence${if (typed == 1) "" else "s"}", KeyboardView.Tone.DONE)
            },
        ) else null
        val rec = Recorder(onFrame = queue?.let { q -> { f -> q.feed(f) } }, encodeDir = if (queue == null) cacheDir else null) { level ->
            main.post { kb?.pushLevel(level) }
        }
        if (!rec.start()) { queue?.cancel(); return flash("Microphone is busy (in a call?)", KeyboardView.Tone.ERROR) }
        recorder = rec
        liveQueue = queue
        main.removeCallbacks(resetStatus)
        v.clearLevels()
        v.recordingSince = SystemClock.elapsedRealtime()
        v.mic = KeyboardView.Mic.RECORDING
        main.postDelayed(autoStop, MAX_RECORD_MS)
        Log.i(TAG, "recording for ${target?.category}")
    }

    private fun stopAndProcess() {
        main.removeCallbacks(autoStop)
        val rec = recorder?.stop() ?: return
        recorder = null
        val t = target ?: return
        kb?.mic = KeyboardView.Mic.PROCESSING
        liveQueue?.let { q -> q.finish(); kb?.setStatus("Typing…"); return }
        kb?.setStatus("Transcribing")
        job = scope.launch {
            val result = dictation.process(rec, t.category, AppCategory.label(this@MyWisperIme, t.pkg)) { stage -> kb?.setStatus(stage) }
            showIdleStatus()
            when (result) {
                is Dictation.Result.Text -> {
                    val typed = insert(result.text, t)
                    val secs = "%.1f s".format(result.ms / 1000.0)
                    when {
                        !typed -> flash("Copied – paste it where you want", KeyboardView.Tone.DONE)
                        result.note != null -> flash(result.note, KeyboardView.Tone.ERROR)
                        else -> flash("Done · $secs", KeyboardView.Tone.DONE)
                    }
                }
                is Dictation.Result.Empty -> flash(result.reason, KeyboardView.Tone.NORMAL)
                is Dictation.Result.Failed -> flash(result.message, KeyboardView.Tone.ERROR, opensApp = "key" in result.message)
            }
        }
    }

    /** Types the text into the field it was recorded for; otherwise puts it on the clipboard. */
    private fun insert(text: String, t: Target): Boolean {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (inputActive && ic != null && info != null && info.packageName == t.pkg && info.fieldId == t.fieldId) {
            val before = ic.getTextBeforeCursor(1, 0)
            ic.commitText(TextRules.withLeadingSpace(before, text), 1)
            return true
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("MyWisper", text))
        return false
    }

    private fun stopRecordingForTyping() {
        if (recorder?.isRecording == true) stopAndProcess()
    }

    // ---------- keys ----------
    override fun onChar(text: String) {
        stopRecordingForTyping()
        currentInputConnection?.commitText(text, 1)
        lastSpaceAt = 0
    }

    override fun onSpace() {
        stopRecordingForTyping()
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (settings.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            // "word␣" + space → "word. "
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpaceAt = now
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        lastSpaceAt = 0
        val selected = ic.getSelectedText(0)
        when {
            !selected.isNullOrEmpty() -> ic.commitText("", 1)
            ic.getTextBeforeCursor(1, 0).isNullOrEmpty() -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) // apps that don't expose text
            else -> ic.deleteSurroundingTextInCodePoints(1, 0) // emoji-safe
        }
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0) ?: return
        val n = TextRules.wordDeleteLength(before)
        if (n > 0) ic.deleteSurroundingText(n, 0)
        onKeyFeedback(KeyType.BACKSPACE)
    }

    override fun onEnter() {
        stopRecordingForTyping()
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val hasAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (hasAction) currentInputConnection?.performEditorAction(action) else sendKeyChar('\n')
    }

    override fun onCursor(steps: Int) {
        val key = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(steps)) { sendDownUpKeyEvents(key) }
    }

    override fun onGlobe() {
        if (!switchToPreviousInputMethod()) onGlobeLongPress()
    }

    override fun onGlobeLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onSettings() = openApp()
    override fun onStatusTap() = openApp()

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onKeyFeedback(type: KeyType) {
        if (vibrate) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
        if (sound) {
            val fx = when (type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f)
        }
    }

    private companion object {
        const val TAG = "MyWisper"
        const val MAX_RECORD_MS = 5 * 60 * 1000L // same as desktop
        const val STATUS_MS = 4000L
        const val DOUBLE_SPACE_MS = 600L
        const val PASTE_CHIP_MS = 60_000L
        const val ACTION_TEST_AUDIO = "com.mywisper.TEST_AUDIO"
        const val ACTION_TEST_TAP = "com.mywisper.TEST_TAP"
    }
}
