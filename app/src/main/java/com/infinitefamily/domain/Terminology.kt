package com.infinitefamily.domain

/** One word with an optional transliteration and a usage note. */
data class Term(val text: String, val roman: String = "", val note: String? = null)

/**
 * Structured terminology for one kinship code (see [RelationshipEngine] for the code notation).
 * Data only: correcting or extending terms never requires touching the relationship engine.
 */
data class TermEntry(
    val code: String,
    val relationEn: String,
    val addressEn: String,
    val ta: List<Term> = emptyList(),
    val ml: List<Term> = emptyList(),
    val note: String? = null,
)

data class ResolvedTerms(
    val code: String,
    val relationEn: String,
    val addressEn: String,
    val ta: List<Term>,
    val ml: List<Term>,
    /** True when Tamil/Malayalam terms come from the equivalent plain relationship (step/half/adoptive/ex stripped). */
    val taApproximate: Boolean,
    val mlApproximate: Boolean,
    val note: String?,
    /** True when the terms were built by joining the terms of two or more shorter relationships. */
    val taComposed: Boolean = false,
    val mlComposed: Boolean = false,
    val enComposed: Boolean = false,
)

class Terminology(
    private val entries: Map<String, TermEntry> = DefaultTerminology.entries,
    private val overrides: List<TermOverride> = emptyList(),
) {
    fun allCodes(): List<TermEntry> = entries.values.toList()

    private fun ladder(code: String): List<String> {
        val tilde = code.indexOf('~')
        val base = if (tilde >= 0) code.substring(0, tilde) else code
        val tail = if (tilde >= 0) code.substring(tilde) else ""
        fun noAge(s: String) = s.replace("e", "").replace("y", "")
        fun noBond(s: String) = s.replace(Regex("[sahx]"), "")
        val out = LinkedHashSet<String>()
        for (b in listOf(base, noBond(base))) {
            out += b + tail; out += noAge(b) + tail
            if (tail.isNotEmpty()) { out += b; out += noAge(b) }
        }
        return out.toList()
    }

    private fun pickTerms(code: String, lang: Language, sel: (TermEntry) -> List<Term>): Pair<List<Term>, Boolean> {
        for (c in ladder(code)) {
            overrides.firstOrNull { it.code == c && it.language == lang }?.let {
                return listOf(Term(it.text, "", "Custom for this family")) to (c != code)
            }
            val l = entries[c]?.let(sel).orEmpty()
            if (l.isNotEmpty()) return l to (c != code)
        }
        return emptyList<Term>() to false
    }

    private val tokRe = Regex("[sahx]?[ey]?[FMSDBZHWXPCG]")

    /** Every way of cutting a code into consecutive parts, fewest parts first, then the longest first part. */
    private fun splits(code: String): List<List<String>> {
        val base = code.substringBefore('~')
        val toks = tokRe.findAll(base).map { it.value }.toList()
        if (toks.size < 2 || toks.joinToString("") != base) return emptyList()
        val out = ArrayList<List<String>>()
        for (mask in 0 until (1 shl (toks.size - 1))) {
            val parts = ArrayList<String>(); val cur = StringBuilder()
            for (i in toks.indices) {
                cur.append(toks[i])
                if (i == toks.size - 1 || (mask shr i) and 1 == 1) { parts += cur.toString(); cur.clear() }
            }
            if (parts.size >= 2) out += parts
        }
        return out.sortedWith(compareBy<List<String>> { it.size }.thenByDescending { it.first().length })
    }

    /**
     * Tamil / Malayalam terms for a relationship with no term of its own: the code is cut into the
     * fewest, largest parts that each have a term, and the terms are joined ("Machan-oda magan").
     * Nothing is invented: every word comes from the terminology table or a family override.
     */
    private fun compose(code: String, lang: Language, sel: (TermEntry) -> List<Term>): List<Term> {
        val tail = if ('~' in code) code.substring(code.indexOf('~')) else ""
        for (parts in splits(code)) {
            val lists = parts.mapIndexed { i, pc ->
                val withTail = if (i == parts.size - 1 && tail.isNotEmpty()) pickTerms(pc + tail, lang, sel).first else emptyList()
                withTail.ifEmpty { pickTerms(pc, lang, sel).first }
            }
            if (lists.any { it.isEmpty() }) continue
            val label = parts.joinToString(" + ") { entries[it]?.relationEn ?: EnglishNamer.describe(it) }
            // up to two words per part, at most four combinations
            var combos: List<List<Term>> = listOf(emptyList())
            for (l in lists) combos = combos.flatMap { c -> l.take(2).map { c + it } }.take(4)
            return combos.map { c ->
                val text = c.mapIndexed { i, t -> if (i < c.size - 1) Genitive.apply(lang, t.text, false) else t.text }.joinToString(" ")
                val roman = c.mapIndexed { i, t -> if (i < c.size - 1) Genitive.apply(lang, t.text, true, t.roman) else t.roman }.joinToString(" ")
                Term(text, roman, "Built from: $label. Not a standard single term; confirm with your family.")
            }.distinctBy { it.text }
        }
        return emptyList()
    }

    private fun entryFor(code: String): TermEntry? = ladder(code).firstNotNullOfOrNull { entries[it] }

    /** English way of addressing a relationship that has no entry: "Cousin's son". */
    private fun composeEnglish(code: String): String? {
        for (parts in splits(code)) {
            val es = parts.map { entryFor(it) }
            if (es.any { it == null }) continue
            return es.mapIndexed { i, e ->
                if (i == 0) (if (" / " in e!!.addressEn) e.relationEn else e.addressEn) else e!!.relationEn.lowercase()
            }.joinToString("'s ")
        }
        return null
    }

    fun resolve(code: String): ResolvedTerms {
        val steps = ladder(code)
        var (ta, taApprox) = pickTerms(code, Language.TA) { it.ta }
        var (ml, mlApprox) = pickTerms(code, Language.ML) { it.ml }
        var taComposed = false; var mlComposed = false
        if (ta.isEmpty()) compose(code, Language.TA) { it.ta }.let { if (it.isNotEmpty()) { ta = it; taComposed = true; taApprox = false } }
        if (ml.isEmpty()) compose(code, Language.ML) { it.ml }.let { if (it.isNotEmpty()) { ml = it; mlComposed = true; mlApprox = false } }
        val exact = entries[code]
        val relation = exact?.relationEn ?: EnglishNamer.describe(code)
        val enOverride = overrides.firstOrNull { it.code == code && it.language == Language.EN }?.text
        val bondCount = code.count { it in "sahx" }
        val sameBond = steps.firstNotNullOfOrNull { c -> entries[c]?.takeIf { c.count { ch -> ch in "sahx" } == bondCount } }
        val composedEn = if (enOverride == null && exact == null && sameBond == null) composeEnglish(code) else null
        val address = enOverride ?: exact?.addressEn ?: sameBond?.addressEn ?: composedEn ?: relation
        val note = exact?.note ?: steps.firstNotNullOfOrNull { entries[it]?.note }
        return ResolvedTerms(code, relation, address, ta, ml, taApprox, mlApprox, note, taComposed, mlComposed, composedEn != null)
    }
}

/** Joins two words the way Tamil / Malayalam do ("Machan" + "magan" = "Machan-oda magan"). */
object Genitive {
    fun apply(lang: Language, word: String, roman: Boolean, romanWord: String = ""): String = when (lang) {
        Language.TA -> tamil(word, roman, romanWord)
        Language.ML -> malayalam(word, roman, romanWord)
        Language.EN -> if (roman) romanWord else "$word's"
    }

    private const val PULLI = '\u0BCD'
    private val vSigns = "\u0BBE\u0BC1\u0BC2\u0BCA\u0BCB\u0BCC" // aa u uu o oo au
    private val ySigns = "\u0BBF\u0BC0\u0BC6\u0BC7\u0BC8"        // i ii e ee ai

    private fun tamil(word: String, roman: Boolean, rw: String): String {
        val w = word.trim(); if (w.isEmpty()) return w
        val last = w.last()
        val kind = when { last == PULLI -> 'N'; last in vSigns -> 'V'; last in ySigns -> 'Y'; else -> 'V' }
        return if (roman) rw + when (kind) { 'N' -> "oda"; 'V' -> "voda"; else -> "yoda" }
        else when (kind) { 'N' -> w.dropLast(1) + "\u0BCB\u0B9F"; 'V' -> w + "\u0BB5\u0BCB\u0B9F"; else -> w + "\u0BAF\u0BCB\u0B9F" }
    }

    private fun malayalam(word: String, roman: Boolean, rw: String): String {
        val w = word.trim(); if (w.isEmpty()) return w
        val r = rw.trim()
        return when {
            w.endsWith("\u0D7B") -> if (roman) r + "te" else w.dropLast(1) + "\u0D28\u0D4D\u0D31\u0D46"          // -an -> -ante
            w.endsWith("\u0D7E") -> if (roman) r + "ude" else w.dropLast(1) + "\u0D33\u0D41\u0D1F\u0D46"         // -al -> -alude
            else -> if (roman) r + "yude" else w + "\u0D2F\u0D41\u0D1F\u0D46"                                         // -yude
        }
    }
}

object EnglishNamer {
    private val tokenRe = Regex("([sahx]?)([ey?]?)([FMSDBZHWXPCG])")

    data class Tok(val bond: Char?, val age: Char?, val letter: Char)

    fun parse(code: String): List<Tok> =
        tokenRe.findAll(code.substringBefore('~')).map {
            Tok(it.groupValues[1].firstOrNull(), it.groupValues[2].firstOrNull(), it.groupValues[3][0])
        }.toList()

    private fun noun(c: Char) = when (c) {
        'F' -> "father"; 'M' -> "mother"; 'P' -> "parent"; 'S' -> "son"; 'D' -> "daughter"; 'C' -> "child"
        'B' -> "brother"; 'Z' -> "sister"; 'G' -> "sibling"; 'H' -> "husband"; 'W' -> "wife"; else -> "partner"
    }

    fun tokenName(t: Tok): String {
        val bond = when (t.bond) { 's' -> "step-"; 'h' -> "half-"; 'x' -> "ex-"; 'a' -> "adoptive "; else -> "" }
        val age = when (t.age) { 'e' -> "elder "; 'y' -> "younger "; else -> "" }
        return age + bond + noun(t.letter)
    }

    fun describe(code: String): String {
        val toks = parse(code)
        if (toks.isEmpty()) return "Self"
        val plain = toks.all { it.bond == null }
        if (plain && toks.size >= 2 && toks.all { it.letter in "FMP" }) {
            val side = when (toks[0].letter) { 'F' -> "Paternal "; 'M' -> "Maternal "; else -> "" }
            val last = toks.last().letter
            val base = when (last) { 'F' -> "grandfather"; 'M' -> "grandmother"; else -> "grandparent" }
            return side + "great-".repeat(toks.size - 2) + base
        }
        if (plain && toks.size >= 2 && toks.all { it.letter in "SDC" }) {
            val base = when (toks.last().letter) { 'S' -> "grandson"; 'D' -> "granddaughter"; else -> "grandchild" }
            return "great-".repeat(toks.size - 2) + base
        }
        val s = toks.joinToString("'s ") { tokenName(it) }
        return s.replaceFirstChar { it.uppercase() }
    }
}
