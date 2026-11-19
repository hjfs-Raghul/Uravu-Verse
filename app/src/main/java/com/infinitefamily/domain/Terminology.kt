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

    fun resolve(code: String): ResolvedTerms {
        val steps = ladder(code)
        fun pick(lang: Language, sel: (TermEntry) -> List<Term>): Pair<List<Term>, Boolean> {
            for (c in steps) {
                overrides.firstOrNull { it.code == c && it.language == lang }?.let {
                    return listOf(Term(it.text, "", "Custom for this family")) to (c != code)
                }
                val l = entries[c]?.let(sel).orEmpty()
                if (l.isNotEmpty()) return l to (c != code)
            }
            return emptyList<Term>() to false
        }
        val (ta, taApprox) = pick(Language.TA) { it.ta }
        val (ml, mlApprox) = pick(Language.ML) { it.ml }
        val exact = entries[code]
        val relation = exact?.relationEn ?: EnglishNamer.describe(code)
        val enOverride = overrides.firstOrNull { it.code == code && it.language == Language.EN }?.text
        val bondCount = code.count { it in "sahx" }
        val sameBond = steps.firstNotNullOfOrNull { c -> entries[c]?.takeIf { c.count { ch -> ch in "sahx" } == bondCount } }
        val address = enOverride ?: exact?.addressEn ?: sameBond?.addressEn ?: relation
        val note = exact?.note ?: steps.firstNotNullOfOrNull { entries[it]?.note }
        return ResolvedTerms(code, relation, address, ta, ml, taApprox, mlApprox, note)
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
