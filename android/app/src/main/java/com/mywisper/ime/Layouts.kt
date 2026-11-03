package com.mywisper.ime

enum class KeyType { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, TO_SYMBOLS, TO_SYMBOLS2, TO_LETTERS, GLOBE, GAP }

/** One key. width is relative: every row adds up to 10. alternates = long-press choices. */
data class Key(val type: KeyType, val label: String = "", val width: Float = 1f, val alternates: List<String> = emptyList())

enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }

/** The keyboard pages as data. */
object Layouts {
    // Long-press choices: top-row digits, accents, and common punctuation.
    private val ALTERNATES = mapOf(
        "q" to "1", "w" to "2", "e" to "3 é è ê ë ē", "r" to "4", "t" to "5", "y" to "6 ý ÿ",
        "u" to "7 ú ù û ü ū", "i" to "8 í ì î ï", "o" to "9 ó ò ô ö õ ø", "p" to "0",
        "a" to "á à â ä ã å æ", "s" to "ß ś š", "d" to "ð", "l" to "ł", "z" to "ž ź ż", "c" to "ç ć č", "n" to "ñ ń",
        "." to ", ? ! ' \" - : ;", "," to "; : ' \" !",
        "1" to "¹ ½ ⅓ ¼", "2" to "² ⅔", "3" to "³ ¾", "0" to "° ∅",
        "-" to "– — _", "/" to "\\ |", "(" to "[ { <", ")" to "] } >", "₹" to "$ € £ ¥", "\"" to "“ ” „", "'" to "‘ ’ `",
        "?" to "¿", "!" to "¡", "*" to "★ †", "+" to "± ±",
    )

    private fun c(label: String) = Key(KeyType.CHAR, label, alternates = ALTERNATES[label]?.split(' ')?.distinct().orEmpty())
    private fun chars(s: String) = s.split(' ').map { c(it) }

    private fun bottomRow(toggle: Key) = listOf(
        toggle, c(","), Key(KeyType.GLOBE), Key(KeyType.SPACE, width = 4f), c("."), Key(KeyType.ENTER, width = 1.5f),
    )

    val letters: List<List<Key>> = listOf(
        chars("q w e r t y u i o p"),
        listOf(Key(KeyType.GAP, width = 0.5f)) + chars("a s d f g h j k l") + Key(KeyType.GAP, width = 0.5f),
        listOf(Key(KeyType.SHIFT, width = 1.5f)) + chars("z x c v b n m") + Key(KeyType.BACKSPACE, width = 1.5f),
        bottomRow(Key(KeyType.TO_SYMBOLS, "?123", width = 1.5f)),
    )

    val symbols: List<List<Key>> = listOf(
        chars("1 2 3 4 5 6 7 8 9 0"),
        chars("@ # ₹ _ & - + ( ) /"),
        listOf(Key(KeyType.TO_SYMBOLS2, "=\\<", width = 1.5f)) + chars("* \" ' : ; ! ?") + Key(KeyType.BACKSPACE, width = 1.5f),
        bottomRow(Key(KeyType.TO_LETTERS, "ABC", width = 1.5f)),
    )

    val symbols2: List<List<Key>> = listOf(
        chars("~ ` | • √ π ÷ × { }"),
        chars("€ £ $ ^ = % [ ] < >"),
        listOf(Key(KeyType.TO_SYMBOLS, "?123", width = 1.5f)) + chars("\\ © ® ™ ¶ ° ✓") + Key(KeyType.BACKSPACE, width = 1.5f),
        bottomRow(Key(KeyType.TO_LETTERS, "ABC", width = 1.5f)),
    )

    // Optional number row above the letters (long-press for the symbol on that key, like a PC keyboard).
    private val NUMBER_ROW_ALTS = listOf("!", "@", "#", "₹", "%", "^", "&", "*", "(", ")")
    private val numberRow = (1..10).map { i ->
        val d = (i % 10).toString()
        Key(KeyType.CHAR, d, alternates = listOf(NUMBER_ROW_ALTS[i - 1]))
    }

    /** Phone-style pad for number, phone and PIN fields. */
    val numpad: List<List<Key>> = listOf(
        chars("1 2 3") + Key(KeyType.CHAR, "-", alternates = listOf("+", "/", "(", ")")),
        chars("4 5 6") + Key(KeyType.SPACE),
        chars("7 8 9") + Key(KeyType.BACKSPACE),
        listOf(Key(KeyType.TO_LETTERS, "ABC"), Key(KeyType.CHAR, "0", alternates = listOf("+", "*", "#")),
            Key(KeyType.CHAR, ".", alternates = listOf(",", ":", "/")), Key(KeyType.ENTER)),
    )

    fun of(page: Page, withNumberRow: Boolean = false) = when (page) {
        Page.LETTERS -> if (withNumberRow) listOf(numberRow) + letters else letters
        Page.SYMBOLS -> symbols
        Page.SYMBOLS2 -> symbols2
        Page.NUMPAD -> numpad
    }
}
