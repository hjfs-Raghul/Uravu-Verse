package com.infinitefamily.domain

/** One relationship from the person's own tree that has no complete Tamil / Malayalam term yet. */
data class TermSuggestion(
    val code: String,
    val relationEn: String,
    /** Name of a real person in the tree who has this relationship, so the suggestion feels concrete. */
    val example: String,
    val missingTa: Boolean,
    val missingMl: Boolean,
)

/** One link in a relationship path built by the user, e.g. "father's" + "elder" + "sister". */
data class CodeStep(val letter: Char, val bond: Char? = null, val age: Char? = null)

object TermSuggestions {
    val letters: List<Pair<Char, String>> = listOf(
        'F' to "Father", 'M' to "Mother", 'S' to "Son", 'D' to "Daughter",
        'B' to "Brother", 'Z' to "Sister", 'H' to "Husband", 'W' to "Wife",
    )
    val bonds: List<Pair<Char, String>> = listOf('s' to "Step", 'a' to "Adoptive", 'h' to "Half", 'x' to "Ex")
    val siblingLetters = setOf('B', 'Z')

    /** Kinship code (see [DefaultTerminology]) for a chain of steps. Age only applies to siblings. */
    fun buildCode(steps: List<CodeStep>, targetAge: Char? = null): String {
        val body = steps.joinToString("") { st ->
            (st.bond?.toString() ?: "") + (if (st.letter in siblingLetters) st.age?.toString() ?: "" else "") + st.letter
        }
        return if (body.isNotEmpty() && targetAge != null) "$body~$targetAge" else body
    }

    /**
     * Relationships found in the tree (as seen from the reference person) that still lack a Tamil or
     * Malayalam term of their own. Worst gaps first. Nothing is invented: this only lists what is missing.
     */
    fun fromFamily(engine: RelationshipEngine, terminology: Terminology, limit: Int = 12): List<TermSuggestion> {
        val data = engine.data
        val rootId = data.rootId ?: return emptyList()
        val found = LinkedHashMap<String, TermSuggestion>()
        for (p in data.persons.values.sortedBy { it.createdAt }) {
            if (p.id == rootId) continue
            val r = engine.relation(rootId, p.id)
            val paths = listOfNotNull(r.primary) + r.alternatives
            for (path in paths) for (reading in path.readings) {
                val code = reading.code
                if (code.isEmpty() || code in found) continue
                val t = terminology.resolve(code)
                val noTa = t.ta.isEmpty() || t.taApproximate
                val noMl = t.ml.isEmpty() || t.mlApproximate
                if (noTa || noMl) found[code] = TermSuggestion(code, t.relationEn, p.name, noTa, noMl)
            }
        }
        return found.values.sortedByDescending { (if (it.missingTa) 1 else 0) + (if (it.missingMl) 1 else 0) }.take(limit)
    }
}
