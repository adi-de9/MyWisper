package com.mywisper.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mywisper.audio.AacEncoder
import com.mywisper.audio.Recorder
import com.mywisper.core.Account
import com.mywisper.core.GroqClient
import com.mywisper.core.GroqException
import com.mywisper.core.PromptEntry
import com.mywisper.core.PromptLibrary
import com.mywisper.core.Prompts
import com.mywisper.core.Settings
import com.mywisper.core.SyncRepo
import com.mywisper.core.TextRules
import com.mywisper.core.Transform
import com.mywisper.ime.Fonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "MyWisper · Rewrite" for selected text, as a bottom sheet (desktop: Rewrite menu → preview).
 * Opened from Android's text-selection menu (ACTION_PROCESS_TEXT) or from the app (e.g. today's EOD).
 * Steps: pick a prompt (or 🎤 say what to do) → working → preview → Replace / Copy / Try again.
 */
class RewriteActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var transform: Transform
    private lateinit var settings: Settings
    private lateinit var body: LinearLayout
    private lateinit var sheet: LinearLayout
    private lateinit var scroll: ScrollView

    private var text = ""
    private var canReplace = false
    private var sourceApp: String? = null
    private var job: Job? = null
    private var recorder: Recorder? = null

    // the last run, for "Try again"
    private var lastPromptId: String? = null
    private var lastExtra = ""
    private var lastInstruction: String? = null

    private val cBg = 0xFF0A0A0A.toInt()
    private val cCard = 0xFF111111.toInt()
    private val cLine = 0xFF262626.toInt()
    private val cFg = 0xFFEDEDED.toInt()
    private val cFg2 = 0xFFA1A1A1.toInt()
    private val cFg3 = 0xFF707070.toInt()
    private val cRed = 0xFFE5484D.toInt()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transform = Transform(this)
        settings = Settings(this)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        when {
            intent.action == Intent.ACTION_PROCESS_TEXT -> {
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                canReplace = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                sourceApp = callingPackage
            }
            else -> {
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                canReplace = false
                sourceApp = intent.getStringExtra(EXTRA_APP)
            }
        }

        // Dimmed background (tap to close) + the sheet at the bottom.
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { finish() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cBg); setStroke(dp(1f), cLine)
                cornerRadii = floatArrayOf(dp(18f).toFloat(), dp(18f).toFloat(), dp(18f).toFloat(), dp(18f).toFloat(), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(18f), dp(10f), dp(18f), dp(18f))
            isClickable = true // don't close when tapping inside
        }
        sheet.addView(View(this).apply { background = round(cLine, null, 3f) }, LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12f)
        })
        scroll = ScrollView(this).apply { isFillViewport = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            sheet.setPadding(dp(18f), dp(10f), dp(18f), dp(18f) + bars.bottom)
            scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            insets
        }
        setContentView(root)
        // Re-fit the sheet whenever its content changes: as tall as needed, at most ~80% of the screen.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) fitSheet()
        }

        when {
            !Account.isSignedIn -> message("Sign in to MyWisper first", "Open the MyWisper app and continue with Google.")
            text.isBlank() -> message("Nothing selected", "Select some text first, then choose MyWisper.")
            else -> {
                SyncRepo.start(this)
                val direct = intent.getStringExtra(EXTRA_PROMPT)
                if (direct != null) run(direct, "", null) else showMenu()
            }
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun fitSheet() {
        val max = (resources.displayMetrics.heightPixels * 0.8).toInt()
        val want = if (body.height > max) max else ViewGroup.LayoutParams.WRAP_CONTENT
        if (scroll.layoutParams.height != want) scroll.post { scroll.layoutParams = scroll.layoutParams.apply { height = want } }
    }

    // ---------- small UI helpers ----------
    private fun round(fill: Int, stroke: Int?, r: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1f), stroke)
    }

    private fun label(s: String, size: Float, color: Int = cFg, weight: Int = 400) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = Fonts.sans(this@RewriteActivity, weight); setLineSpacing(0f, 1.2f)
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 14f; typeface = Fonts.sans(this@RewriteActivity, 560); stateListAnimator = null
        setTextColor(if (primary) cBg else cFg)
        background = round(if (primary) cFg else cBg, if (primary) null else cLine, 8f)
        minHeight = dp(44f); minimumHeight = dp(44f)
        setOnClickListener { onClick() }
    }

    private fun gap(h: Float) = body.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

    private fun header(title: String) {
        body.removeAllViews()
        body.addView(label(title, 18f, weight = 600))
        gap(4f)
        body.addView(label(text.replace('\n', ' '), 13f, cFg3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        gap(14f)
    }

    private fun row(icon: String, name: String, desc: String?, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = round(cCard, cLine, 10f)
            setOnClickListener { onClick() }
        }
        r.addView(label("$icon  $name", 15f, weight = 500))
        if (!desc.isNullOrBlank()) r.addView(label(desc, 12.5f, cFg3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(26f), dp(2f), 0, 0) })
        body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
    }

    private fun buttons(vararg views: Button) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, b ->
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < views.lastIndex) marginEnd = dp(8f) })
        }
        body.addView(r)
    }

    private fun message(title: String, text: String) {
        body.removeAllViews()
        body.addView(label(title, 18f, weight = 600))
        gap(6f)
        body.addView(label(text, 14f, cFg2))
        gap(14f)
        buttons(button("Close", false) { finish() })
    }

    private fun working(what: String) {
        header(what)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        r.addView(label("Working…", 14f, cFg2).apply { setPadding(dp(10f), 0, 0, 0) })
        body.addView(r)
        gap(14f)
        buttons(button("Cancel", false) { job?.cancel(); showMenu() })
    }

    // ---------- steps ----------
    private fun icon(id: String) = when (id) {
        "prompt" -> "✦"; "polish" -> "✎"; "grammar" -> "✓"; "translate" -> "⇄"; "eod" -> "▤"; else -> "≡"
    }

    private fun showMenu(showAll: Boolean = false) {
        header("Rewrite selection")
        row("🎤", "Say what to do", "e.g. \"make EOD, keep it short\"") { startVoice() }
        val all = PromptLibrary.list(this)
        val shown = if (showAll) all else all.filter { it.pinned }.ifEmpty { all.take(5) }
        shown.forEach { p -> row(icon(p.id), p.name, p.description) { run(p.id, "", null) } }
        if (!showAll && shown.size < all.size) row("…", "More prompts", "${all.size - shown.size} more") { showMenu(true) }
    }

    private fun run(promptId: String?, extra: String, instruction: String?) {
        lastPromptId = promptId; lastExtra = extra; lastInstruction = instruction
        val name = promptId?.let { PromptLibrary.get(this, it)?.name } ?: "Your instruction"
        working(name)
        job = scope.launch {
            try {
                val out = transform.run(text, promptId, extra, instruction, sourceApp)
                if (out.text.isBlank()) message("Empty answer", "The AI returned nothing. Try again.") else showResult(name, out.text)
            } catch (e: GroqException) {
                message("Couldn't rewrite", e.message ?: "Something went wrong")
            }
        }
    }

    private fun showResult(title: String, result: String) {
        body.removeAllViews()
        body.addView(label(title, 18f, weight = 600))
        gap(10f)
        body.addView(label(result, 15f).apply {
            setTextIsSelectable(true)
            background = round(cCard, cLine, 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        })
        gap(14f)
        val copy = button("Copy", !canReplace) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MyWisper", result))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            if (!canReplace) finish()
        }
        val again = button("Try again", false) { run(lastPromptId, lastExtra, lastInstruction) }
        if (canReplace) {
            buttons(button("Replace", true) { replace(result) })
            gap(8f)
            buttons(copy, again)
        } else buttons(copy, again)
    }

    private fun replace(result: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
        finish()
    }

    // ---------- "Say what to do" ----------
    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        val levelView = label("●  Listening…", 15f, cRed, 500)
        header("Say what to do")
        body.addView(levelView)
        gap(4f)
        body.addView(label("e.g. \"EOD banao\", \"make it formal\", \"Jira ticket, keep it short\"", 13f, cFg3))
        gap(14f)
        val rec = Recorder { }
        if (!rec.start()) { message("Microphone is busy", "Close other apps using the mic and try again."); return }
        recorder = rec
        val started = SystemClock.elapsedRealtime()
        buttons(button("Cancel", false) { recorder?.stop(); recorder = null; showMenu() }, button("Done", true) { finishVoice() })
        levelView.postDelayed(object : Runnable {
            override fun run() {
                if (recorder == null) return
                levelView.text = "●  Listening…  ${(SystemClock.elapsedRealtime() - started) / 1000}s"
                levelView.postDelayed(this, 500)
            }
        }, 500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    private fun finishVoice() {
        val rec = recorder?.stop() ?: return
        recorder = null
        if (rec.voiceMs < 250) { message("No speech heard", "Tap Say what to do and speak a little louder."); return }
        working("Understanding")
        job = scope.launch {
            try {
                val prompts = Prompts.get(this@RewriteActivity)
                val m4a = AacEncoder.encode(rec.pcm, Recorder.SAMPLE_RATE, cacheDir)
                val groq = GroqClient()
                val raw = if (m4a != null) groq.transcribe(m4a, settings.whisperModel, prompts.whisperPrompt, settings.language, "speech.m4a", "audio/mp4")
                else groq.transcribe(rec.wav(), settings.whisperModel, prompts.whisperPrompt, settings.language)
                val said = TextRules.filterTranscript(raw, strict = rec.voiceMs < 1000, hallucinations = prompts.hallucinations, always = prompts.hallucinationsAlways)
                if (said.isBlank()) { message("No speech heard", "Try again and speak clearly."); return@launch }
                val match = transform.matchPrompt(said)
                if (match.id == null) confirmWords(said, match.extra) else confirm(said, match.id, match.extra)
            } catch (e: GroqException) {
                message("Couldn't understand", e.message ?: "Something went wrong")
            }
        }
    }

    /** "Use 'EOD report'? + keep it short"  [No] [Yes] – desktop confirm view. */
    private fun confirm(said: String, id: String, extra: String) {
        val p = PromptLibrary.get(this, id) ?: return confirmWords(said, extra)
        header("Use \"${p.name}\"?")
        body.addView(label("You said: \"$said\"", 13.5f, cFg2))
        if (extra.isNotBlank()) { gap(4f); body.addView(label("+ $extra", 13.5f, cFg)) }
        gap(14f)
        buttons(button("No", false) { pick(said) }, button("Yes", true) { run(id, extra, said) })
    }

    private fun confirmWords(said: String, words: String) {
        header("No saved prompt matched")
        body.addView(label("You said: \"$said\"", 13.5f, cFg2))
        gap(14f)
        buttons(button("Pick a prompt", false) { pick(said) }, button("Use my words", true) { run(null, words, said) })
    }

    /** No → every saved prompt + "Use my words" (desktop pick view). */
    private fun pick(said: String) {
        header("Pick a prompt")
        row("🗣", "Use my words", "\"$said\"") { run(null, said, said) }
        PromptLibrary.list(this).forEach { p: PromptEntry -> row(icon(p.id), p.name, p.description) { run(p.id, "", said) } }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_APP = "app"
        /** Run this prompt straight away (e.g. "eod" from the History screen). */
        const val EXTRA_PROMPT = "prompt"
    }
}
