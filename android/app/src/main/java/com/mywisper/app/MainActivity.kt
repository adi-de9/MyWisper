package com.mywisper.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.firebase.firestore.ListenerRegistration
import com.mywisper.core.Account
import com.mywisper.core.ClipboardStore
import com.mywisper.core.Settings
import com.mywisper.core.SyncRepo
import com.mywisper.core.ServerApi
import com.mywisper.ime.Fonts
import com.mywisper.ime.KbTheme
import com.mywisper.ime.KeyType
import com.mywisper.ime.KeyboardView
import com.mywisper.ime.MyWisperIme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The MyWisper app.
 * First run: a short setup, one step per screen (sign in → Groq key → microphone → floating mic or keyboard).
 * Afterwards: three tabs – Home (status, today, try it), History, Settings (a list; each row opens its own page).
 * Plain Views in code, in the same monochrome style as the desktop dashboard.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var regular: Typeface
    private lateinit var medium: Typeface

    private val cBg = 0xFF0A0A0A.toInt()
    private val cCard = 0xFF111111.toInt()
    private val cLine = 0xFF262626.toInt()
    private val cFg = 0xFFEDEDED.toInt()
    private val cFg2 = 0xFFA1A1A1.toInt()
    private val cFg3 = 0xFF707070.toInt()
    private val cRed = 0xFFE5484D.toInt()
    private val cGreen = 0xFF46A758.toInt()

    private lateinit var root: FrameLayout
    private val imeId by lazy { ComponentName(this, MyWisperIme::class.java) }

    // where we are
    private enum class Tab { HOME, HISTORY, SETTINGS }
    private var tab = Tab.HOME
    private var page: String? = null      // an open Settings page, e.g. "voice"
    private var setupStep = 0             // shown while setting up
    private var historyItems: List<Map<String, Any>> = emptyList()
    private var historyListener: ListenerRegistration? = null
    private var onHistory: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        regular = Fonts.sans(this, 400)
        medium = Fonts.sans(this, 560)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        root = FrameLayout(this).apply { setBackgroundColor(cBg) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }
    }

    // Back: close a Settings page → leave a tab → close the app.
    private val backCallback = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { handleBack() } else null

    @Deprecated("Only used before Android 13")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        when {
            inSetup() && setupStep > 0 -> { setupStep--; render() }
            page != null -> { page = null; render() }
            tab != Tab.HOME && !inSetup() -> { tab = Tab.HOME; render() }
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Account.isSignedIn) SyncRepo.start(this)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && inSetup()) render() // e.g. after a system settings screen or the keyboard picker
    }

    override fun onStop() {
        historyListener?.remove()
        historyListener = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- state ----------
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun keyboardEnabled() = getSystemService(InputMethodManager::class.java)
        .enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == imeId }
    private fun keyboardSelected() = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it) } == imeId

    private fun inSetup(): Boolean {
        if (!Account.isSignedIn) return true
        // People who already finished every step (e.g. before this screen existed) go straight to Home.
        if (!settings.onboarded && hasMic() && keyboardEnabled()) settings.onboarded = true
        return !settings.onboarded
    }

    // ---------- rendering ----------
    private fun render() {
        root.removeAllViews()
        if (inSetup()) root.addView(buildSetup()) else root.addView(buildShell())
        watchHistory(!inSetup())
    }

    private fun watchHistory(on: Boolean) {
        if (on && historyListener == null && Account.isSignedIn) {
            historyListener = SyncRepo.watchHistory { list -> historyItems = list; onHistory?.invoke() }
        }
    }

    // ================= first-run setup =================
    private fun buildSetup(): View {
        // Skip steps that are already done (e.g. returning from system settings).
        if (setupStep == 0 && Account.isSignedIn) setupStep = 1
        if (setupStep == 1 && hasMic()) setupStep = 2
        val total = 3
        val col = column(24f)
        // progress
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until total) {
            bars.addView(View(this).apply { background = round(if (i <= setupStep) cFg else cLine, null, 2f) },
                LinearLayout.LayoutParams(0, dp(4f), 1f).apply { if (i < total - 1) marginEnd = dp(6f) })
        }
        col.addView(bars)
        col.gap(10f)
        col.addView(label("Step ${setupStep + 1} of $total", 13f, cFg3))
        col.gap(28f)

        when (setupStep) {
            0 -> setupStep(col, "◎", "Welcome to MyWisper",
                "Speak Hindi or English, get clean Hinglish text in any app.\n\nSign in with the same Google account as on your PC. Your history, prompts and dictionary sync between them, encrypted.") {
                val status = label("", 13.5f, cFg2)
                val btn = button("Continue with Google", true) {}
                btn.setOnClickListener {
                    btn.isEnabled = false
                    status.text = "Choose your Google account…"
                    scope.launch {
                        val err = Account.signIn(this@MainActivity)
                        btn.isEnabled = true
                        if (err != null) { status.text = err; status.setTextColor(cRed) } else { SyncRepo.start(this@MainActivity); setupStep = 1; render() }
                    }
                }
                addView(btn, fullWidth())
                gap(10f)
                addView(status)
            }
            1 -> setupStep(col, "🎙", "Allow the microphone",
                "MyWisper only listens while the mic is red. The audio is turned into text and not kept.") {
                addView(button("Allow microphone", true) { askMic() }, fullWidth())
            }
            else -> setupStep(col, "⌨", "Turn on the MyWisper keyboard",
                "A full keyboard with the mic on top – plus numbers, clipboard and themes. Turn it on, then switch to it.") {
                val on = keyboardEnabled()
                choiceCard(this, "1. Turn it on", "In the list, switch on MyWisper. Android shows a standard warning for every keyboard – MyWisper never sends what you type.",
                    on) { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) }
                gap(10f)
                choiceCard(this, "2. Switch to it", "Pick MyWisper as the keyboard you type with.", keyboardSelected()) {
                    if (on) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    else Toast.makeText(this@MainActivity, "Turn it on first", Toast.LENGTH_SHORT).show()
                }
                gap(20f)
                addView(button(if (on) "Finish" else "Skip for now", on) { settings.onboarded = true; tab = Tab.HOME; render() }, fullWidth())
            }
        }
        if (setupStep > 0) {
            col.gap(12f)
            col.addView(textButton("Back") { setupStep--; render() })
        }
        return scroll(col)
    }

    private fun setupStep(col: LinearLayout, icon: String, title: String, body: String, content: LinearLayout.() -> Unit) {
        col.addView(label(icon, 30f).apply {
            gravity = Gravity.CENTER
            background = round(cCard, cLine, 999f)
        }, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        col.gap(20f)
        col.addView(label(title, 26f, bold = true))
        col.gap(10f)
        col.addView(label(body, 15f, cFg2))
        col.gap(28f)
        col.content()
    }

    private fun choiceCard(parent: LinearLayout, title: String, desc: String, done: Boolean, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(cCard, if (done) cFg2 else cLine, 12f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 16f, bold = true))
        texts.addView(label(desc, 13f, cFg2))
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(label(if (done) "On ✓" else "Turn on ›", 13.5f, if (done) cGreen else cFg).apply { setPadding(dp(12f), 0, 0, 0) })
        parent.addView(card, fullWidth())
    }

    // ================= main app: tabs =================
    private fun buildShell(): View {
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = FrameLayout(this)
        val open = page
        content.addView(if (open != null) buildPage(open) else when (tab) {
            Tab.HOME -> buildHome()
            Tab.HISTORY -> buildHistory()
            Tab.SETTINGS -> buildSettingsList()
        })
        shell.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (open == null) shell.addView(bottomNav())
        return shell
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        val line = View(this).apply { setBackgroundColor(cLine) }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        wrap.addView(nav)
        listOf(Tab.HOME to ("⌂" to "Home"), Tab.HISTORY to ("☰" to "History"), Tab.SETTINGS to ("⚙" to "Settings")).forEach { (t, v) ->
            val on = tab == t
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, dp(4f))
                setOnClickListener { tab = t; page = null; render() }
            }
            item.addView(label(v.first, 20f, if (on) cFg else cFg3).apply { gravity = Gravity.CENTER })
            item.addView(label(v.second, 12f, if (on) cFg else cFg3, bold = on).apply { gravity = Gravity.CENTER })
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return wrap
    }

    // ---------- Home ----------
    private fun buildHome(): View {
        val col = column(20f)
        val first = Account.user?.displayName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        col.addView(label(if (first != null) "Hi, $first" else "MyWisper", 28f, bold = true))
        col.gap(4f)
        col.addView(label("Tap the mic on the MyWisper keyboard, speak, tap it again.", 14f, cFg2))
        col.gap(18f)

        // Status: only problems are spelled out.
        val problems = buildList {
            if (!hasMic()) add("Microphone not allowed" to { askMic() })
            if (!keyboardEnabled()) add("MyWisper keyboard is off" to { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) })
            if (SyncRepo.status == "mismatch") add("Sync stopped: encryption key differs from your PC" to { page = "account"; render() })
        }
        val status = card(col)
        if (problems.isEmpty()) {
            status.addView(rowLine("●  Ready", "MyWisper keyboard · synced, encrypted", cGreen))
        } else {
            status.addView(label("Needs attention", 15f, cRed, bold = true))
            status.gap(6f)
            problems.forEach { (text, fix) ->
                status.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8f), 0, dp(8f))
                    setOnClickListener { fix() }
                    addView(label(text, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("Fix ›", 14f, cFg, bold = true))
                })
            }
        }
        col.gap(12f)

        usageCard(col)
        col.gap(12f)

        // Today
        val today = card(col)
        today.addView(label("Today", 13f, cFg3))
        today.gap(8f)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val words = stat("–", "words")
        val dict = stat("–", "dictations")
        val phone = stat("–", "on this phone")
        listOf(words, dict, phone).forEach { stats.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        today.addView(stats)
        today.gap(14f)
        today.addView(button("Create today's EOD", true) { createTodayEod() }, fullWidth())
        fun fill() {
            val day = LocalDate.now(ZoneId.systemDefault())
            val todays = historyItems.filter { isToday(it, day) && it["kind"] != "transform" }
            (words.getChildAt(0) as TextView).text = "%,d".format(todays.sumOf { (it["words"] as? Number)?.toInt() ?: 0 })
            (dict.getChildAt(0) as TextView).text = todays.size.toString()
            (phone.getChildAt(0) as TextView).text = todays.count { it["device"] == "android" }.toString()
        }
        fill()
        onHistory = { fill() }
        col.gap(12f)

        // Try it
        val tryCard = card(col)
        tryCard.addView(label("Try it", 15f, bold = true))
        tryCard.gap(4f)
        tryCard.addView(label("Tap the box, switch to the MyWisper keyboard and tap its mic.", 13f, cFg2))
        tryCard.gap(10f)
        tryCard.addView(EditText(this).apply {
            hint = "Say something…"
            setHintTextColor(0xFF555555.toInt())
            setTextColor(cFg)
            textSize = 15f
            typeface = regular
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = round(cBg, cLine, 8f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            tag = "tryit"
        }, fullWidth())
        return scroll(col)
    }

    /** The daily free limit (15 min of speech, all devices together), from the MyWisper server. */
    private fun usageCard(col: LinearLayout) {
        val c = card(col)
        c.addView(label("Free today", 13f, cFg3))
        c.gap(6f)
        val text = label("Checking…", 15f)
        c.addView(text)
        c.gap(10f)
        val fill = View(this).apply { background = round(cFg, null, 3f) }
        val rest = View(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cLine, null, 3f)
            addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
            addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        c.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)))
        c.gap(8f)
        val note = label("15 minutes of speech a day, on all your devices together.", 12.5f, cFg3)
        c.addView(note)
        scope.launch {
            val u = runCatching { ServerApi.usage() }.getOrElse { text.text = "Not available offline"; return@launch }
            val used = minOf(u.audioSec, u.limitSec)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = used.toFloat()
            (rest.layoutParams as LinearLayout.LayoutParams).weight = (u.limitSec - used).toFloat().coerceAtLeast(0.01f)
            bar.requestLayout()
            val reset = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(u.resetAt))
            if (u.reached) {
                text.text = "Daily free limit reached"
                text.setTextColor(cRed)
                fill.background = round(cRed, null, 3f)
                note.text = "Dictation works again after $reset."
            } else {
                text.text = "${used / 60} of ${u.limitSec / 60} min used"
                note.text = "15 minutes of speech a day, on all your devices together. Resets at $reset."
            }
        }
    }

    private fun stat(value: String, name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(value, 24f, bold = true))
        addView(label(name, 12.5f, cFg3))
    }

    private fun isToday(e: Map<String, Any>, day: LocalDate) =
        (e["time"] as? String)?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() == day }.getOrDefault(false) } == true

    private fun createTodayEod() {
        val day = LocalDate.now(ZoneId.systemDefault())
        val notes = historyItems.filter { it["kind"] != "transform" && isToday(it, day) }.reversed()
            .mapNotNull { (it["cleanText"] ?: it["rawText"])?.toString()?.takeIf { t -> t.isNotBlank() } }
        if (notes.isEmpty()) { Toast.makeText(this, "No dictations today yet", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, RewriteActivity::class.java)
            .putExtra(RewriteActivity.EXTRA_TEXT, notes.joinToString("\n"))
            .putExtra(RewriteActivity.EXTRA_PROMPT, "eod"))
    }

    // ---------- History ----------
    private val timeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault())

    private fun buildHistory(): View {
        val col = column(20f)
        col.addView(label("History", 28f, bold = true))
        col.gap(4f)
        val summary = label("Loading…", 13.5f, cFg2)
        col.addView(summary)
        col.gap(14f)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        fun fill() {
            val items = historyItems
            val words = items.sumOf { (it["words"] as? Number)?.toInt() ?: 0 }
            val phone = items.count { it["device"] == "android" }
            summary.text = "${items.size} entries · ${"%,d".format(words)} words · ${items.size - phone} from PC, $phone from phone"
            list.removeAllViews()
            if (items.isEmpty()) list.addView(label("Nothing yet. Dictations from your PC and this phone appear here.", 14f, cFg3))
            items.take(200).forEach { e ->
                val text = (e["cleanText"] ?: e["result"] ?: e["rawText"] ?: "").toString().ifBlank { "(no text)" }
                val time = (e["time"] as? String)?.let { runCatching { timeFmt.format(Instant.parse(it)) }.getOrNull() } ?: ""
                val where = (e["label"] ?: e["app"])?.toString()?.takeIf { it.isNotBlank() }
                val c = card(list, padding = 14f, margin = 8f)
                c.addView(label(listOfNotNull(time, if (e["device"] == "android") "Phone" else "PC", where).joinToString("  ·  "), 12f, cFg3))
                c.gap(4f)
                c.addView(label(text, 15f).apply { maxLines = 6; ellipsize = TextUtils.TruncateAt.END })
                c.setOnClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MyWisper", text))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
            if (items.size > 200) list.addView(label("Showing the latest 200.", 12.5f, cFg3))
        }
        fill()
        onHistory = { fill() }
        return scroll(col)
    }

    // ---------- Settings list ----------
    private fun buildSettingsList(): View {
        val col = column(20f)
        col.addView(label("Settings", 28f, bold = true))
        col.gap(16f)
        val group = card(col, padding = 0f)
        fun row(icon: String, title: String, sub: String, key: String, last: Boolean = false) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                setOnClickListener { page = key; render() }
            }
            r.addView(label(icon, 18f, cFg2).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0) }
            texts.addView(label(title, 15.5f, bold = true))
            texts.addView(label(sub, 12.5f, cFg3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(label("›", 20f, cFg3))
            group.addView(r)
            if (!last) group.addView(View(this).apply { setBackgroundColor(cLine) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        }
        row("◎", "Account", Account.user?.email ?: "Not signed in", "account")
        row("🎙", "Voice", langName(settings.language) +
            if (settings.liveTyping) " · live typing" else "", "voice")
        row("⌨", "MyWisper keyboard", when { keyboardSelected() -> "In use"; keyboardEnabled() -> "On"; else -> "Off" } + " · theme, numbers, clipboard", "keyboard")
        row("ⓘ", "About", "Version ${packageManager.getPackageInfo(packageName, 0).versionName} · privacy", "about", last = true)
        return scroll(col)
    }

    private fun langName(code: String) = when (code) { "hi" -> "Hindi"; "en" -> "English"; else -> "Auto language" }

    // ---------- Settings pages ----------
    private fun buildPage(key: String): View {
        val col = column(16f)
        val titles = mapOf("account" to "Account", "voice" to "Voice", "keyboard" to "MyWisper keyboard", "about" to "About")
        // header with back
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("‹", 30f).apply { setPadding(dp(4f), 0, dp(14f), dp(4f)); setOnClickListener { page = null; render() } })
            addView(label(titles[key] ?: "", 22f, bold = true))
        })
        col.gap(16f)
        when (key) {
            "account" -> pageAccount(col)
            "voice" -> pageVoice(col)
            "keyboard" -> pageKeyboard(col)
            "about" -> pageAbout(col)
        }
        return scroll(col)
    }

    private fun pageAccount(col: LinearLayout) {
        val c = card(col)
        val user = Account.user
        c.addView(label(user?.displayName ?: "Signed in", 16f, bold = true))
        c.addView(label(user?.email ?: "", 14f, cFg2))
        c.gap(10f)
        c.addView(label(if (SyncRepo.status == "mismatch")
            "Sync stopped: the encryption key doesn't match your other devices. Sign out and sign in again."
        else "History, prompts and dictionary sync with your PC, encrypted.", 13.5f, if (SyncRepo.status == "mismatch") cRed else cFg2))
        col.gap(12f)
        val actions = card(col)
        actions.addView(button("Sign out", false) {
            scope.launch { Account.signOut(this@MainActivity); SyncRepo.clear(this@MainActivity); page = null; setupStep = 0; render() }
        }, fullWidth())
        actions.gap(8f)
        actions.addView(button("Delete my cloud data", false) { confirmDeleteCloud() }.apply { setTextColor(cRed) }, fullWidth())
        actions.gap(6f)
        actions.addView(label("Removes your synced data from the cloud. Your PC keeps its own copy.", 12.5f, cFg3))
    }

    private fun pageVoice(col: LinearLayout) {
        val lang = card(col)
        segmented(lang, "Language you speak", listOf("auto" to "Auto", "hi" to "Hindi", "en" to "English"), { settings.language }, { settings.language = it })
        col.gap(12f)
        val live = card(col)
        live.addView(label("Live", 13f, cFg3))
        toggle(live, "Live typing", "Type each sentence as soon as you pause", { settings.liveTyping }, { settings.liveTyping = it })
    }

    private fun pageKeyboard(col: LinearLayout) {
        val st = card(col)
        st.addView(rowLine("Status", when { keyboardSelected() -> "In use"; keyboardEnabled() -> "Turned on, not in use"; else -> "Off" }, cFg))
        st.gap(12f)
        st.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Turn on", false) { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
            addView(button("Switch keyboard", false) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        col.gap(12f)

        // Look, with a live preview
        val look = card(col, padding = 12f)
        val preview = KeyboardView(this, PreviewListener, Fonts.sans(this)).apply { setStatus("Preview") }
        fun refreshPreview() {
            val scale = when (settings.kbHeight) { "small" -> 0.88f; "tall" -> 1.12f; else -> 1f }
            preview.configure(KbTheme.from(this, settings), settings.numberRow, scale)
            preview.clipboardEnabled = settings.clipboardHistory
        }
        refreshPreview()
        look.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14f) })
        segmented(look, "Theme", listOf("system" to "System", "dark" to "Dark", "light" to "Light"), { settings.kbTheme }, { settings.kbTheme = it; refreshPreview() })
        look.gap(10f)
        accentPicker(look) { refreshPreview() }
        look.gap(10f)
        segmented(look, "Height", listOf("small" to "Small", "normal" to "Normal", "tall" to "Tall"), { settings.kbHeight }, { settings.kbHeight = it; refreshPreview() })
        look.gap(6f)
        toggle(look, "Number row", "1–0 above the letters", { settings.numberRow }, { settings.numberRow = it; refreshPreview() })
        toggle(look, "Key borders", "Boxes around the letter keys", { settings.keyBorders }, { settings.keyBorders = it; refreshPreview() })
        col.gap(12f)

        val typing = card(col)
        typing.addView(label("Typing", 13f, cFg3))
        toggle(typing, "Vibrate on key press", null, { settings.vibrate }, { settings.vibrate = it })
        toggle(typing, "Sound on key press", null, { settings.sound }, { settings.sound = it })
        toggle(typing, "Double space adds a full stop", null, { settings.doubleSpacePeriod }, { settings.doubleSpacePeriod = it })
        col.gap(12f)

        val clip = card(col)
        clip.addView(label("Clipboard", 13f, cFg3))
        toggle(clip, "Clipboard history", "Copied text in the keyboard's clipboard panel · this phone only, cleared after an hour unless pinned",
            { settings.clipboardHistory }, { settings.clipboardHistory = it; refreshPreview() })
        clip.gap(6f)
        clip.addView(textButton("Clear clipboard history") {
            ClipboardStore(this).clear()
            Toast.makeText(this, "Cleared (pinned items kept)", Toast.LENGTH_SHORT).show()
        })
    }

    private fun pageAbout(col: LinearLayout) {
        val c = card(col)
        c.addView(label("MyWisper ${packageManager.getPackageInfo(packageName, 0).versionName}", 16f, bold = true))
        c.gap(8f)
        c.addView(label("Speak Hindi or English, get clean Hinglish text in any app. Works with the MyWisper app on your PC.", 13.5f, cFg2))
        col.gap(12f)
        val p = card(col)
        p.addView(label("Privacy", 15f, bold = true))
        p.gap(6f)
        p.addView(label("• Audio is recorded only while the mic is red. It's turned into text (via the MyWisper server and Groq) and not kept.\n" +
            "• The keyboard never sends what you type.\n" +
            "• Synced text is encrypted before it leaves the phone.\n" +
            "• Clipboard history never leaves this phone.", 13.5f, cFg2))
    }

    // ---------- shared actions ----------
    private fun askMic() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !settings.askedMicBefore) {
            settings.askedMicBefore = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else openAppInfo() // denied for good: only the app's system settings page can change it now
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun openAppInfo() = startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun confirmDeleteCloud() {
        AlertDialog.Builder(this)
            .setTitle("Delete my cloud data?")
            .setMessage("Removes your synced history, prompts and dictionary from the cloud. Your PC keeps its own copy. You'll be signed out.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    val n = runCatching { SyncRepo.deleteCloudData(this@MainActivity) }
                    Account.signOut(this@MainActivity)
                    SyncRepo.clear(this@MainActivity)
                    Toast.makeText(this@MainActivity, n.fold({ "Deleted $it item(s) from the cloud. Signed out." }, { "Couldn't delete: ${it.message}" }),
                        Toast.LENGTH_LONG).show()
                    page = null; setupStep = 0; render()
                }
            }
            .show()
    }

    // ================= small UI kit =================
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun round(fill: Int, stroke: Int?, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1f), stroke)
    }

    private fun label(text: String, sizeSp: Float, color: Int = cFg, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) medium else regular
        setLineSpacing(0f, 1.25f)
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14.5f
        typeface = medium
        stateListAnimator = null
        setTextColor(if (primary) cBg else cFg)
        background = round(if (primary) cFg else cBg, if (primary) null else cLine, 10f)
        minHeight = dp(48f); minimumHeight = dp(48f)
        setPadding(dp(16f), 0, dp(16f), 0)
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, onClick: () -> Unit) = label(text, 14f, cFg, bold = true).apply {
        setPadding(0, dp(8f), 0, dp(8f))
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.gap(h: Float) = addView(View(context), LinearLayout.LayoutParams(1, dp(h)))

    private fun column(pad: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun card(parent: LinearLayout, padding: Float = 16f, margin: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cCard, cLine, 14f)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        parent.addView(this, fullWidth().apply { bottomMargin = dp(margin) })
    }

    private fun rowLine(title: String, sub: String, titleColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 15f, titleColor, bold = true))
        addView(label(sub, 13f, cFg2))
    }

    @Suppress("DEPRECATION") // android.widget.Switch is fine without AppCompat
    private fun toggle(parent: LinearLayout, title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f))
        if (sub != null) texts.addView(label(sub, 12.5f, cFg3))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            text = title // accessibility + lets tests find it
            textSize = 0.1f
            isChecked = get()
            setOnCheckedChangeListener { _, on -> set(on) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row, fullWidth())
    }

    /** Short options side by side (one selected). */
    private fun segmented(parent: LinearLayout, title: String, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit) {
        parent.addView(label(title, 13f, cFg3))
        parent.gap(8f)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cBg, cLine, 10f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        val items = mutableListOf<TextView>()
        fun paint() = items.forEachIndexed { i, v ->
            val on = get() == options[i].first
            v.setTextColor(if (on) cBg else cFg2)
            v.background = if (on) round(cFg, null, 8f) else null
        }
        options.forEach { (id, name) ->
            val v = label(name, 13.5f, cFg2, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9f), 0, dp(9f))
                setOnClickListener { set(id); paint() }
            }
            items += v
            bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        parent.addView(bar, fullWidth())
    }

    /** Accent colour dots. */
    private fun accentPicker(parent: LinearLayout, changed: () -> Unit) {
        parent.addView(label("Accent (Enter key & mic)", 13f, cFg3))
        parent.gap(8f)
        val colors = listOf("mono" to cFg, "blue" to 0xFF3291FF.toInt(), "green" to 0xFF3FCF8E.toInt(), "purple" to 0xFF8E4EC6.toInt(), "orange" to 0xFFF5A524.toInt())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = mutableListOf<View>()
        fun paint() = dots.forEachIndexed { i, d ->
            val on = settings.kbAccent == colors[i].first
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colors[i].second)
                setStroke(dp(if (on) 3f else 1f), if (on) cFg else cLine)
            }
        }
        colors.forEach { (id, _) ->
            val d = View(this).apply {
                contentDescription = id
                setOnClickListener { settings.kbAccent = id; paint(); changed() }
            }
            dots += d
            row.addView(d, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(14f) })
        }
        paint()
        parent.addView(row)
    }

    /** The keyboard preview: keys and buttons do nothing. */
    private object PreviewListener : KeyboardView.Listener {
        override fun onChar(text: String) {}
        override fun onBackspace() {}
        override fun onDeleteWord() {}
        override fun onSpace() {}
        override fun onEnter() {}
        override fun onCursor(steps: Int) {}
        override fun onGlobe() {}
        override fun onGlobeLongPress() {}
        override fun onSettings() {}
        override fun onMic() {}
        override fun onStatusTap() {}
        override fun onKeyFeedback(type: KeyType) {}
        override fun onClipboardOpen() {}
        override fun onEditAction(action: KeyboardView.EditAction) {}
        override fun onClipPaste(text: String) {}
        override fun onClipPin(text: String) {}
        override fun onClipDelete(text: String) {}
        override fun onPasteChip() {}
    }
}
