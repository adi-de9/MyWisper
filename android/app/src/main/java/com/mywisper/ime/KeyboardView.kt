package com.mywisper.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.mywisper.core.ClipboardStore
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole keyboard in one View: a toolbar (settings · clipboard · status · mic) above a key area.
 * Keys are drawn on a Canvas and hit-tested here, so there are no per-key views.
 * The key area can also show the clipboard panel. Colours come from KbTheme (dark / light + accent).
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener, private val font: Typeface) : View(context) {

    interface Listener {
        fun onChar(text: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onSpace()
        fun onEnter()
        fun onCursor(steps: Int)
        fun onGlobe()
        fun onGlobeLongPress()
        fun onSettings()
        fun onMic()
        fun onStatusTap()
        fun onKeyFeedback(type: KeyType)
        // clipboard
        fun onClipboardOpen()                 // the IME refreshes clipItems
        fun onEditAction(action: EditAction)
        fun onClipPaste(text: String)
        fun onClipPin(text: String)
        fun onClipDelete(text: String)
        fun onPasteChip()
    }

    enum class Shift { OFF, ONCE, LOCK }
    enum class Mic { IDLE, RECORDING, PROCESSING, DISABLED }
    enum class Tone { NORMAL, ERROR, DONE }
    enum class EditAction(val label: String) { SELECT_ALL("Select all"), CUT("Cut"), COPY("Copy"), PASTE("Paste") }

    // ---------- public state (set by the IME) ----------
    var page = Page.LETTERS
        set(value) { field = value; layoutKeys(); invalidate() }
    var shift = Shift.OFF
        set(value) { field = value; invalidate() }
    var mic = Mic.IDLE
        set(value) { field = value; invalidate() }
    /** Label on Enter (e.g. "Send"); null = the return arrow. */
    var enterLabel: String? = null
        set(value) { field = value; invalidate() }
    var recordingSince = 0L
    /** Recently copied text offered as a one-tap "Paste" chip in the toolbar (null = none). */
    var pasteChip: String? = null
        set(value) { field = value; invalidate() }
    var clipItems: List<ClipboardStore.Item> = emptyList()
        set(value) { field = value; clipScroll = clipScroll.coerceIn(0f, maxClipScroll()); invalidate() }
    var clipboardEnabled = true
        set(value) { field = value; layoutKeys(); invalidate() }

    private var status = ""
    private var tone = Tone.NORMAL
    private var statusOpensApp = false
    private val levels = FloatArray(28)
    private var levelIndex = 0

    fun setStatus(text: String, tone: Tone = Tone.NORMAL, opensApp: Boolean = false) {
        status = text; this.tone = tone; statusOpensApp = opensApp; invalidate()
    }

    fun pushLevel(level: Float) {
        levels[levelIndex] = level
        levelIndex = (levelIndex + 1) % levels.size
    }

    fun clearLevels() = levels.fill(0f)

    /** Auto-capitalisation from the editor; Caps Lock is left alone. */
    fun setAutoCaps(on: Boolean) {
        if (shift != Shift.LOCK) shift = if (on) Shift.ONCE else Shift.OFF
    }

    /** Look and layout options from Settings (applied each time the keyboard opens). */
    fun configure(theme: KbTheme, numberRow: Boolean, heightScale: Float) {
        val changed = numberRow != this.numberRow || heightScale != this.heightScale
        t = theme
        this.numberRow = numberRow
        this.heightScale = heightScale
        if (changed) requestLayout()
        layoutKeys()
        invalidate()
    }

    /** Show the clipboard panel instead of the keys (or back). */
    var clipboardOpen = false
        private set

    fun closeClipboard() {
        if (!clipboardOpen) return
        clipboardOpen = false
        invalidate()
    }

    // ---------- sizes ----------
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private var numberRow = false
    private var heightScale = 1f
    private val toolbarH = dp(48f)
    private val baseRowH get() = dp(54f) * heightScale
    /** The key area is as tall as the tallest page (5 rows with the number row), so it never jumps. */
    private val keyAreaH get() = baseRowH * (if (numberRow) 5 else 4)
    private var rowH = dp(54f)
    private val gapX = dp(5f)
    private val gapY = dp(8f)
    private val sidePad = dp(3f)
    private val topPad = dp(4f)
    private val bottomPad = dp(6f)
    private val radius = dp(6f)
    private var bottomInset = 0

    // ---------- colours ----------
    private var t = KbTheme.from(context, com.mywisper.core.Settings(context))
    private val cRed = 0xFFE5484D.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = dp(1.7f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val tmp = RectF()

    // ---------- layout ----------
    private class Cell(val key: Key, val rect: RectF, val hit: RectF)
    private var rows: List<List<Cell>> = emptyList()
    private val gearRect = RectF()
    private val clipRect = RectF()
    private val micRect = RectF()
    private val statusRect = RectF()

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            if (bottom != bottomInset) { bottomInset = bottom; requestLayout() }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (toolbarH + topPad + keyAreaH + bottomPad).toInt() + bottomInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        gearRect.set(0f, 0f, dp(48f), toolbarH)
        clipRect.set(gearRect.right, 0f, gearRect.right + if (clipboardEnabled) dp(44f) else 0f, toolbarH)
        micRect.set(w - dp(64f), 0f, w, toolbarH)
        statusRect.set(clipRect.right, 0f, micRect.left, toolbarH)
        val layout = Layouts.of(page, numberRow)
        rowH = keyAreaH / layout.size
        var y = toolbarH + topPad
        rows = layout.map { row ->
            val unit = (w - sidePad * 2) / row.sumOf { it.width.toDouble() }.toFloat()
            var x = sidePad
            val cells = row.map { key ->
                val cw = key.width * unit
                val cell = Cell(key, RectF(x + gapX / 2, y + gapY / 2, x + cw - gapX / 2, y + rowH - gapY / 2), RectF(x, y, x + cw, y + rowH))
                x += cw
                cell
            }
            y += rowH
            cells
        }
    }

    // ---------- touch ----------
    private val handler = Handler(Looper.getMainLooper())
    private var pointerId = -1
    private var pressed: Cell? = null
    private var toolbarTarget = 0 // 1 gear, 2 status, 3 mic, 4 clipboard
    private var downX = 0f
    private var downY = 0f
    private var longHandled = false
    private var swiping = false
    private var swipeAnchor = 0f
    private var deleteSwiping = false
    private var repeatCount = 0
    private var repeatStart = 0L
    private var lastShiftTap = 0L
    private var popup: List<String> = emptyList()
    private val popupRects = mutableListOf<RectF>()
    private var popupSel = -1

    private val longPress = Runnable {
        val cell = pressed ?: return@Runnable
        when (cell.key.type) {
            KeyType.CHAR -> if (cell.key.alternates.isNotEmpty()) openPopup(cell)
            KeyType.GLOBE -> { longHandled = true; listener.onGlobeLongPress() }
            else -> {}
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            if (pressed?.key?.type != KeyType.BACKSPACE || deleteSwiping) return
            listener.onBackspace()
            repeatCount++
            val fast = SystemClock.uptimeMillis() - repeatStart > 1000
            handler.postDelayed(this, if (fast) 30 else 70)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (clipboardOpen && (e.actionMasked != MotionEvent.ACTION_DOWN || e.y >= toolbarH) && toolbarTarget == 0) {
            return clipTouch(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger while a key is held: type the first key now (fast two-thumb typing).
                if (pointerId != -1) finishPress(commit = true)
                val i = e.actionIndex
                pointerId = e.getPointerId(i)
                press(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) move(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pointerId) {
                    val i = e.actionIndex
                    release(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_CANCEL -> finishPress(commit = false)
        }
        return true
    }

    private fun press(x: Float, y: Float) {
        downX = x
        if (y < toolbarH) {
            toolbarTarget = when {
                gearRect.contains(x, y) -> 1
                clipboardEnabled && clipRect.contains(x, y) -> 4
                micRect.contains(x, y) -> 3
                else -> 2
            }
            invalidate()
            return
        }
        val cell = hitKey(x, y) ?: return
        pressed = cell
        longHandled = false; swiping = false; deleteSwiping = false; repeatCount = 0
        swipeAnchor = x
        listener.onKeyFeedback(cell.key.type)
        when (cell.key.type) {
            KeyType.CHAR, KeyType.GLOBE -> handler.postDelayed(longPress, LONG_PRESS_MS)
            KeyType.BACKSPACE -> { repeatStart = SystemClock.uptimeMillis() + LONG_PRESS_MS; handler.postDelayed(repeat, LONG_PRESS_MS) }
            else -> {}
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val cell = pressed ?: return
        if (popup.isNotEmpty()) {
            popupSel = popupRects.indexOfFirst { x >= it.left && x < it.right }.let { idx ->
                if (idx >= 0) idx else if (x < popupRects.first().left) 0 else popupRects.lastIndex
            }
            invalidate()
            return
        }
        when (cell.key.type) {
            KeyType.SPACE -> { // slide on the space bar moves the cursor
                if (!swiping && abs(x - downX) > dp(14f)) { swiping = true; swipeAnchor = x }
                if (swiping) {
                    val step = dp(11f)
                    val steps = ((x - swipeAnchor) / step).toInt()
                    if (steps != 0) { listener.onCursor(steps); swipeAnchor += steps * step }
                }
            }
            KeyType.BACKSPACE -> { // slide left from backspace deletes whole words
                if (!deleteSwiping && downX - x > dp(30f)) {
                    deleteSwiping = true; handler.removeCallbacks(repeat); swipeAnchor = x
                    listener.onDeleteWord()
                } else if (deleteSwiping && swipeAnchor - x > dp(40f)) {
                    swipeAnchor = x; listener.onDeleteWord()
                }
            }
            KeyType.CHAR -> { // sliding onto another letter before long-press switches to it
                val other = hitKey(x, y)
                if (other != null && other !== cell && other.key.type == KeyType.CHAR) {
                    handler.removeCallbacks(longPress)
                    pressed = other
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    invalidate()
                }
            }
            else -> {}
        }
    }

    private fun release(x: Float, y: Float) {
        if (toolbarTarget != 0) {
            val target = toolbarTarget
            toolbarTarget = 0
            pointerId = -1
            invalidate()
            if (y <= toolbarH + dp(12f)) when (target) {
                1 -> if (gearRect.contains(x, min(y, toolbarH - 1))) { listener.onKeyFeedback(KeyType.CHAR); listener.onSettings() }
                3 -> if (micRect.contains(x, min(y, toolbarH - 1)) || abs(x - micRect.centerX()) < dp(40f)) listener.onMic()
                4 -> if (clipRect.contains(x, min(y, toolbarH - 1))) toggleClipboard()
                2 -> when {
                    pasteChip != null && mic == Mic.IDLE && pasteChipRect.contains(x, min(y, toolbarH - 1)) -> listener.onPasteChip()
                    statusOpensApp -> listener.onStatusTap()
                }
            }
            return
        }
        finishPress(commit = true)
    }

    private fun toggleClipboard() {
        listener.onKeyFeedback(KeyType.CHAR)
        clipboardOpen = !clipboardOpen
        if (clipboardOpen) { clipScroll = 0f; listener.onClipboardOpen() }
        invalidate()
    }

    private fun finishPress(commit: Boolean) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeat)
        val cell = pressed
        pressed = null
        pointerId = -1
        toolbarTarget = 0
        if (popup.isNotEmpty()) {
            val choice = popup.getOrNull(popupSel)
            closePopup()
            if (commit && choice != null) typeChar(choice)
            invalidate()
            return
        }
        if (cell != null && commit && !longHandled) {
            when (cell.key.type) {
                KeyType.CHAR -> typeChar(cell.key.label)
                KeyType.SHIFT -> tapShift()
                KeyType.BACKSPACE -> if (repeatCount == 0 && !deleteSwiping) listener.onBackspace()
                KeyType.SPACE -> if (!swiping) listener.onSpace()
                KeyType.ENTER -> listener.onEnter()
                KeyType.TO_SYMBOLS -> page = Page.SYMBOLS
                KeyType.TO_SYMBOLS2 -> page = Page.SYMBOLS2
                KeyType.TO_LETTERS -> page = Page.LETTERS
                KeyType.GLOBE -> listener.onGlobe()
                KeyType.GAP -> {}
            }
        }
        invalidate()
    }

    private fun typeChar(label: String) {
        listener.onChar(applyShift(label))
        if (page == Page.LETTERS && shift == Shift.ONCE && label.first().isLetter()) shift = Shift.OFF
    }

    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK // double-tap = Caps Lock
            shift == Shift.ONCE -> Shift.OFF
            else -> Shift.ONCE
        }
        lastShiftTap = now
    }

    private fun applyShift(label: String) =
        if (page == Page.LETTERS && shift != Shift.OFF) label.uppercase() else label

    private fun openPopup(cell: Cell) {
        popup = cell.key.alternates.map { applyShift(it) }
        val cw = max(cell.rect.width(), dp(34f))
        val total = cw * popup.size
        var left = cell.rect.centerX() - cw / 2
        left = left.coerceIn(sidePad, width - sidePad - total)
        val top = max(dp(6f), cell.rect.top - rowH - dp(6f))
        popupRects.clear()
        popup.indices.forEach { i -> popupRects.add(RectF(left + i * cw, top, left + (i + 1) * cw, top + rowH)) }
        popupSel = 0
        invalidate()
    }

    private fun closePopup() {
        popup = emptyList(); popupRects.clear(); popupSel = -1
    }

    /** The key under (x, y); gaps and the space between keys count as the nearest key in that row. */
    private fun hitKey(x: Float, y: Float): Cell? {
        if (rows.isEmpty()) return null
        val first = rows.first().first().hit.top
        val rowIndex = ((y - first) / rowH).toInt().coerceIn(0, rows.lastIndex)
        return rows[rowIndex].filter { it.key.type != KeyType.GAP }.minByOrNull { cell ->
            if (x >= cell.hit.left && x < cell.hit.right) 0f else min(abs(x - cell.hit.left), abs(x - cell.hit.right))
        }
    }

    // ---------- clipboard panel ----------
    // [⌨ ABC] [Select all] [Cut] [Copy] [Paste]   then a 2-column list of copied items (scrolls).
    private var clipScroll = 0f
    private var clipDownY = 0f
    private var clipScrolling = false
    private var clipLongDone = false
    private val actionRects = mutableListOf<Pair<RectF, EditAction?>>() // null = back to the keys
    private val itemRects = mutableListOf<Pair<RectF, ClipboardStore.Item>>()
    private val clipLong = Runnable {
        val item = itemRects.firstOrNull { it.first.contains(downX, clipDownY) }?.second ?: return@Runnable
        clipLongDone = true
        listener.onKeyFeedback(KeyType.CHAR)
        listener.onClipPin(item.text)
    }

    private val listTop get() = toolbarH + topPad + dp(52f)
    private val itemH get() = dp(64f)
    private fun maxClipScroll(): Float {
        val rowsNeeded = (clipItems.size + 1) / 2
        val listH = toolbarH + topPad + keyAreaH - listTop
        return max(0f, rowsNeeded * (itemH + dp(8f)) - listH)
    }

    private fun clipTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; clipDownY = e.y; downY = e.y; clipScrolling = false; clipLongDone = false
                if (itemRects.any { it.first.contains(e.x, e.y) }) handler.postDelayed(clipLong, LONG_PRESS_MS + 100)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!clipScrolling && abs(e.y - clipDownY) > dp(8f)) { clipScrolling = true; handler.removeCallbacks(clipLong) }
                if (clipScrolling) {
                    clipScroll = (clipScroll - (e.y - downY)).coerceIn(0f, maxClipScroll())
                    downY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(clipLong)
                if (clipScrolling || clipLongDone) return true
                actionRects.firstOrNull { it.first.contains(e.x, e.y) }?.let { (_, action) ->
                    listener.onKeyFeedback(KeyType.CHAR)
                    if (action == null) { clipboardOpen = false; invalidate() } else listener.onEditAction(action)
                    return true
                }
                itemRects.firstOrNull { it.first.contains(e.x, e.y) }?.let { (r, item) ->
                    listener.onKeyFeedback(KeyType.CHAR)
                    // The small ✕ in the corner deletes; anywhere else pastes.
                    if (e.x > r.right - dp(30f) && e.y < r.top + dp(26f)) listener.onClipDelete(item.text)
                    else listener.onClipPaste(item.text)
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(clipLong)
        }
        return true
    }

    private fun drawClipboard(canvas: Canvas) {
        val w = width.toFloat()
        val top = toolbarH + topPad
        // action row
        actionRects.clear()
        val actions = listOf<EditAction?>(null) + EditAction.values()
        val aw = (w - sidePad * 2) / actions.size
        actions.forEachIndexed { i, a ->
            val r = RectF(sidePad + i * aw + gapX / 2, top + dp(4f), sidePad + (i + 1) * aw - gapX / 2, top + dp(44f))
            actionRects += r to a
            fill.color = t.keySpecial
            canvas.drawRoundRect(r, radius, radius, fill)
            drawLabel(canvas, a?.label ?: "ABC", r.centerX(), r.centerY(), sp(12.5f), if (a == null) t.fg else t.fg2, 500)
        }
        // items
        itemRects.clear()
        val bottom = top + keyAreaH
        canvas.save()
        canvas.clipRect(0f, listTop, w, bottom)
        if (clipItems.isEmpty()) {
            drawLabel(canvas, "Copied text shows up here. Long-press an item to pin it.", w / 2, listTop + dp(40f), sp(13f), t.fg3, 400)
        }
        val colW = (w - sidePad * 2) / 2
        clipItems.forEachIndexed { i, item ->
            val col = i % 2
            val row = i / 2
            val y = listTop + row * (itemH + dp(8f)) - clipScroll
            val r = RectF(sidePad + col * colW + gapX / 2, y, sidePad + (col + 1) * colW - gapX / 2, y + itemH)
            if (r.bottom < listTop || r.top > bottom) return@forEachIndexed
            itemRects += r to item
            fill.color = t.key
            canvas.drawRoundRect(r, radius * 1.4f, radius * 1.4f, fill)
            if (item.pinned) { stroke.color = t.fg2; stroke.strokeWidth = dp(1.2f); canvas.drawRoundRect(r, radius * 1.4f, radius * 1.4f, stroke); stroke.strokeWidth = dp(1.7f) }
            // text: up to 2 lines
            text.textAlign = Paint.Align.LEFT
            text.textSize = sp(13f)
            text.color = t.fg
            text.isFakeBoldText = false
            val lines = wrap(item.text.replace('\n', ' ').trim(), r.width() - dp(40f), 2)
            lines.forEachIndexed { li, line -> canvas.drawText(line, r.left + dp(10f), r.top + dp(24f) + li * sp(17f), text) }
            text.textAlign = Paint.Align.CENTER
            // corner: pin dot or ✕
            val cx = r.right - dp(15f)
            val cy = r.top + dp(14f)
            stroke.color = t.fg3
            val s = dp(4f)
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, stroke)
            canvas.drawLine(cx + s, cy - s, cx - s, cy + s, stroke)
            if (item.pinned) { fill.color = t.fg2; canvas.drawCircle(r.right - dp(15f), r.bottom - dp(14f), dp(3f), fill) }
        }
        canvas.restore()
    }

    private fun wrap(s: String, maxW: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.isNotEmpty() && out.size < maxLines) {
            var n = text.breakText(rest, true, maxW, null).coerceAtLeast(1)
            if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > n / 2 }?.let { n = it }
            var line = rest.take(n).trim()
            rest = rest.drop(n).trim()
            if (out.size == maxLines - 1 && rest.isNotEmpty()) line = line.take(text.breakText(line, true, maxW - text.measureText("…"), null)) + "…"
            out += line
        }
        return out
    }

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(t.bg)
        drawToolbar(canvas)
        if (clipboardOpen) {
            drawClipboard(canvas)
        } else {
            for (row in rows) for (cell in row) drawKey(canvas, cell)
            val p = pressed
            if (popup.isNotEmpty()) drawPopup(canvas)
            else if (p != null && p.key.type == KeyType.CHAR && !swiping) drawPreview(canvas, p)
        }
        if (mic == Mic.RECORDING || mic == Mic.PROCESSING) postInvalidateOnAnimation()
    }

    private fun drawKey(canvas: Canvas, cell: Cell) {
        val k = cell.key
        if (k.type == KeyType.GAP) return
        val isPressed = pressed === cell && popup.isEmpty()
        val plain = k.type == KeyType.CHAR || k.type == KeyType.SPACE
        // Key borders off: letter keys have no background (like Gboard), only a highlight when pressed.
        val showBg = isPressed || k.type == KeyType.ENTER || t.borders || !plain || k.type == KeyType.SPACE
        if (showBg) {
            fill.color = when {
                isPressed -> t.keyPressed
                k.type == KeyType.ENTER -> t.accent
                plain -> t.key
                else -> t.keySpecial
            }
            canvas.drawRoundRect(cell.rect, radius, radius, fill)
        }
        val cx = cell.rect.centerX()
        val cy = cell.rect.centerY()
        val ink = if (k.type == KeyType.ENTER && !isPressed) t.accentInk else t.fg
        when (k.type) {
            KeyType.CHAR -> {
                val big = page == Page.NUMPAD || k.label.first().isLetter()
                drawLabel(canvas, applyShift(k.label), cx, cy, if (big) sp(21f) else sp(19f), t.fg, 400)
            }
            KeyType.SPACE -> drawLabel(canvas, if (page == Page.NUMPAD) "␣" else "MyWisper", cx, cy, sp(12f), t.fg3, 500)
            KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS -> drawLabel(canvas, k.label, cx, cy, sp(13.5f), t.fg, 500)
            KeyType.ENTER -> enterLabel?.let { drawLabel(canvas, it, cx, cy, sp(13.5f), ink, 600) } ?: drawEnter(canvas, cx, cy, ink)
            KeyType.SHIFT -> drawShift(canvas, cx, cy)
            KeyType.BACKSPACE -> drawBackspace(canvas, cx, cy)
            KeyType.GLOBE -> drawGlobe(canvas, cx, cy)
            KeyType.GAP -> {}
        }
    }

    private fun drawLabel(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, weight: Int) {
        text.textSize = size
        text.color = color
        text.isFakeBoldText = weight >= 600 && font === Typeface.DEFAULT
        val y = cy - (text.descent() + text.ascent()) / 2
        canvas.drawText(s, cx, y, text)
    }

    private fun drawPreview(canvas: Canvas, cell: Cell) {
        val w = max(cell.rect.width() * 1.25f, dp(40f))
        val h = rowH * 1.1f
        val left = (cell.rect.centerX() - w / 2).coerceIn(sidePad, width - sidePad - w)
        val top = max(dp(2f), cell.rect.top - h - dp(4f)) // top row: stay inside the keyboard window
        tmp.set(left, top, left + w, top + h)
        fill.color = t.keyPressed
        canvas.drawRoundRect(tmp, radius * 1.4f, radius * 1.4f, fill)
        drawLabel(canvas, applyShift(cell.key.label), tmp.centerX(), tmp.centerY(), sp(28f), t.fg, 400)
    }

    private fun drawPopup(canvas: Canvas) {
        tmp.set(popupRects.first().left - dp(4f), popupRects.first().top - dp(4f), popupRects.last().right + dp(4f), popupRects.last().bottom + dp(4f))
        fill.color = t.popup
        canvas.drawRoundRect(tmp, radius * 1.6f, radius * 1.6f, fill)
        popup.forEachIndexed { i, s ->
            val r = popupRects[i]
            if (i == popupSel) { fill.color = t.fg; canvas.drawRoundRect(r, radius, radius, fill) }
            drawLabel(canvas, s, r.centerX(), r.centerY(), sp(21f), if (i == popupSel) t.bg else t.fg, 400)
        }
    }

    private val pasteChipRect = RectF()

    private fun drawToolbar(canvas: Canvas) {
        fill.color = t.line
        canvas.drawRect(0f, toolbarH - dp(1f), width.toFloat(), toolbarH, fill)
        drawGear(canvas, gearRect.centerX(), gearRect.centerY(), if (toolbarTarget == 1) t.fg else t.fg2)
        if (clipboardEnabled) drawClipIcon(canvas, clipRect.centerX(), clipRect.centerY(), if (clipboardOpen || toolbarTarget == 4) t.fg else t.fg2)

        val left = statusRect.left + dp(6f)
        val cy = statusRect.centerY()
        val now = SystemClock.uptimeMillis()
        text.textAlign = Paint.Align.LEFT
        pasteChipRect.setEmpty()
        when (mic) {
            Mic.RECORDING -> {
                val pulse = (0.35f + 0.65f * ((sin(now / 190.0) + 1) / 2)).toFloat()
                fill.color = cRed; fill.alpha = (255 * pulse).toInt()
                canvas.drawCircle(left + dp(5f), cy, dp(4.5f), fill)
                fill.alpha = 255
                val secs = ((SystemClock.elapsedRealtime() - recordingSince) / 1000).toInt()
                drawLeft(canvas, "%d:%02d".format(secs / 60, secs % 60), left + dp(16f), cy, sp(14f), t.fg)
                drawLevels(canvas, left + dp(62f), cy, statusRect.right - dp(8f))
            }
            Mic.PROCESSING -> {
                // A time-based spinner, so it keeps turning even with system animations switched off.
                val angle = (now % 900) / 900f * 360f
                stroke.color = t.keyPressed
                canvas.drawCircle(left + dp(7f), cy, dp(6.5f), stroke)
                stroke.color = t.fg
                tmp.set(left + dp(0.5f), cy - dp(6.5f), left + dp(13.5f), cy + dp(6.5f))
                canvas.drawArc(tmp, angle, 90f, false, stroke)
                drawLeft(canvas, status, left + dp(22f), cy, sp(14f), t.fg2)
            }
            else -> {
                val chip = pasteChip
                if (chip != null && tone == Tone.NORMAL) {
                    // "Paste: …" chip for text you just copied.
                    text.textSize = sp(13f)
                    val label = "Paste: " + chip.replace('\n', ' ')
                    val maxW = statusRect.right - left - dp(28f)
                    val shown = if (text.measureText(label) <= maxW) label else label.take(text.breakText(label, true, maxW - text.measureText("…"), null)) + "…"
                    pasteChipRect.set(left, cy - dp(15f), left + text.measureText(shown) + dp(24f), cy + dp(15f))
                    fill.color = if (toolbarTarget == 2) t.keyPressed else t.key
                    canvas.drawRoundRect(pasteChipRect, dp(15f), dp(15f), fill)
                    text.color = t.fg
                    canvas.drawText(shown, left + dp(12f), cy - (text.descent() + text.ascent()) / 2, text)
                } else {
                    val color = when (tone) { Tone.ERROR -> cRed; Tone.DONE -> t.fg; Tone.NORMAL -> t.fg3 }
                    drawLeft(canvas, status, left, cy, sp(13.5f), color)
                }
            }
        }
        text.textAlign = Paint.Align.CENTER
        drawMic(canvas)
    }

    private fun drawLeft(canvas: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        text.isFakeBoldText = false
        val maxW = statusRect.right - x - dp(4f)
        if (maxW <= 0) return
        val shown = if (text.measureText(s) <= maxW) s else s.take(text.breakText(s, true, maxW - text.measureText("…"), null)) + "…"
        canvas.drawText(shown, x, cy - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawLevels(canvas: Canvas, x0: Float, cy: Float, x1: Float) {
        val bar = dp(3f)
        val gap = dp(2.5f)
        val count = min(levels.size, ((x1 - x0) / (bar + gap)).toInt())
        fill.color = t.fg2
        for (i in 0 until count) {
            val v = levels[(levelIndex - count + i + levels.size * 2) % levels.size]
            val h = max(dp(3f), v * dp(22f))
            val x = x0 + i * (bar + gap)
            tmp.set(x, cy - h / 2, x + bar, cy + h / 2)
            canvas.drawRoundRect(tmp, bar / 2, bar / 2, fill)
        }
    }

    private fun drawMic(canvas: Canvas) {
        val cx = micRect.centerX()
        val cy = micRect.centerY()
        val r = dp(17f)
        when (mic) {
            Mic.RECORDING -> {
                val tt = (SystemClock.uptimeMillis() % 1400) / 1400f
                fill.color = cRed; fill.alpha = (90 * (1 - tt)).toInt()
                canvas.drawCircle(cx, cy, r + dp(9f) * tt, fill)
                fill.alpha = 255
                canvas.drawCircle(cx, cy, r, fill)
                fill.color = 0xFFFFFFFF.toInt()
                val s = dp(5.5f)
                tmp.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(tmp, dp(2f), dp(2f), fill) // stop square
            }
            Mic.IDLE -> {
                fill.color = t.accent
                if (toolbarTarget == 3) fill.alpha = 200
                canvas.drawCircle(cx, cy, r, fill)
                fill.alpha = 255
                drawMicGlyph(canvas, cx, cy, t.accentInk)
            }
            Mic.PROCESSING, Mic.DISABLED -> {
                fill.color = t.key
                canvas.drawCircle(cx, cy, r, fill)
                drawMicGlyph(canvas, cx, cy, t.fg3)
            }
        }
    }

    // ---------- icons (simple strokes, 24-unit grid scaled to dp) ----------
    private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        stroke.color = color
        val u = dp(0.75f)
        tmp.set(cx - 3 * u, cy - 9 * u, cx + 3 * u, cy + 2 * u)
        canvas.drawRoundRect(tmp, 3 * u, 3 * u, stroke)
        tmp.set(cx - 6.5f * u, cy - 5.5f * u, cx + 6.5f * u, cy + 5.5f * u)
        canvas.drawArc(tmp, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + 5.5f * u, cx, cy + 9 * u, stroke)
    }

    private fun drawClipIcon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val u = dp(0.85f)
        stroke.color = color
        tmp.set(cx - 7 * u, cy - 8 * u, cx + 7 * u, cy + 10 * u)
        canvas.drawRoundRect(tmp, 2 * u, 2 * u, stroke)
        tmp.set(cx - 3.5f * u, cy - 10 * u, cx + 3.5f * u, cy - 6 * u)
        canvas.drawRoundRect(tmp, 1.5f * u, 1.5f * u, stroke)
        canvas.drawLine(cx - 3.5f * u, cy - 1 * u, cx + 3.5f * u, cy - 1 * u, stroke)
        canvas.drawLine(cx - 3.5f * u, cy + 4 * u, cx + 2 * u, cy + 4 * u, stroke)
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(0.9f)
        path.reset()
        path.moveTo(cx, cy - 8 * u)
        path.lineTo(cx + 7 * u, cy - 1 * u); path.lineTo(cx + 3.5f * u, cy - 1 * u); path.lineTo(cx + 3.5f * u, cy + 5 * u)
        path.lineTo(cx - 3.5f * u, cy + 5 * u); path.lineTo(cx - 3.5f * u, cy - 1 * u); path.lineTo(cx - 7 * u, cy - 1 * u)
        path.close()
        if (shift == Shift.OFF) { stroke.color = t.fg; canvas.drawPath(path, stroke) }
        else { fill.color = t.fg; canvas.drawPath(path, fill); stroke.color = t.fg; canvas.drawPath(path, stroke) }
        if (shift == Shift.LOCK) { stroke.color = t.fg; canvas.drawLine(cx - 3.5f * u, cy + 8 * u, cx + 3.5f * u, cy + 8 * u, stroke) }
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(0.9f)
        stroke.color = t.fg
        path.reset()
        path.moveTo(cx - 10 * u, cy)
        path.lineTo(cx - 4 * u, cy - 6 * u); path.lineTo(cx + 9 * u, cy - 6 * u); path.lineTo(cx + 9 * u, cy + 6 * u)
        path.lineTo(cx - 4 * u, cy + 6 * u); path.close()
        canvas.drawPath(path, stroke)
        canvas.drawLine(cx - 1 * u, cy - 3 * u, cx + 5 * u, cy + 3 * u, stroke)
        canvas.drawLine(cx + 5 * u, cy - 3 * u, cx - 1 * u, cy + 3 * u, stroke)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val u = dp(0.9f)
        stroke.color = color
        path.reset()
        path.moveTo(cx + 7 * u, cy - 7 * u); path.lineTo(cx + 7 * u, cy + 2 * u); path.lineTo(cx - 7 * u, cy + 2 * u)
        canvas.drawPath(path, stroke)
        canvas.drawLine(cx - 7 * u, cy + 2 * u, cx - 3 * u, cy - 2 * u, stroke)
        canvas.drawLine(cx - 7 * u, cy + 2 * u, cx - 3 * u, cy + 6 * u, stroke)
    }

    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(0.9f)
        stroke.color = t.fg
        canvas.drawCircle(cx, cy, 8 * u, stroke)
        tmp.set(cx - 3.5f * u, cy - 8 * u, cx + 3.5f * u, cy + 8 * u)
        canvas.drawOval(tmp, stroke)
        canvas.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, stroke)
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val u = dp(0.85f)
        stroke.color = color
        canvas.drawCircle(cx, cy, 3 * u, stroke)
        canvas.drawCircle(cx, cy, 7 * u, stroke)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            canvas.drawLine(cx + (7 * u * cos(a)).toFloat(), cy + (7 * u * sin(a)).toFloat(),
                cx + (9.5f * u * cos(a)).toFloat(), cy + (9.5f * u * sin(a)).toFloat(), stroke)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 380L
        const val DOUBLE_TAP_MS = 300L
    }
}
