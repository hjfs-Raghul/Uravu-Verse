package com.infinitefamily.domain

/** One way of reading a path: a kinship code plus the terms for it, with the condition it depends on. */
data class Reading(val code: String, val condition: String?, val terms: ResolvedTerms)

data class KinPathResult(
    val steps: List<Edge>,
    /** Human readable steps, e.g. "Raghul's father is Appaji". */
    val stepTexts: List<String>,
    /** More than one entry when an age order is unknown: both outcomes are shown, nothing is guessed. */
    val readings: List<Reading>,
    val summary: String,
) {
    val ambiguous: Boolean get() = readings.size > 1
    val includesNonBiological: Boolean get() = steps.any { it.bond == Bond.ADOPTIVE || it.bond == Bond.STEP || it.bond == Bond.HALF }
}

/** "[toId] is [fromId]'s ..." and how [fromId] addresses [toId]. */
data class RelationResult(
    val fromId: String,
    val toId: String,
    val isSelf: Boolean,
    val connected: Boolean,
    val primary: KinPathResult?,
    val alternatives: List<KinPathResult>,
)

data class PairResult(val aToB: RelationResult, val bToA: RelationResult)

/**
 * Computes how any two people are related.
 *
 * 1. [KinGraph] provides parent / child / spouse / sibling edges (with biological, adoptive, step,
 *    half and ex qualifiers).
 * 2. A pruned depth-first search lists every simple path (shortest first, up to two extra hops).
 *    A few shortcut rules remove redundant detours (e.g. "mother's husband" when he is already my father).
 * 3. A path is turned into a kinship code (see [DefaultTerminology]); unknown sibling age order produces
 *    several candidate codes instead of a guess.
 * 4. [Terminology] maps each code to English / Tamil / Malayalam terms. The terms are data, not logic.
 */
class RelationshipEngine(
    val data: FamilyData,
    private val terminology: Terminology = Terminology(),
) {
    private val graph = KinGraph(data)

    companion object {
        private const val MAX_PATHS = 60
        private const val MAX_DEPTH = 9
    }

    fun pair(aId: String, bId: String) = PairResult(relation(aId, bId), relation(bId, aId))

    fun relation(fromId: String, toId: String): RelationResult {
        if (fromId == toId) return RelationResult(fromId, toId, true, true, null, emptyList())
        val from = data.persons[fromId]; val to = data.persons[toId]
        if (from == null || to == null) return RelationResult(fromId, toId, false, false, null, emptyList())
        val paths = findPaths(fromId, toId)
        if (paths.isEmpty()) return RelationResult(fromId, toId, false, false, null, emptyList())

        val results = paths.sortedBy { score(it) }.map { buildResult(it) }
        val primary = results.first()
        val seen = HashSet<String>().apply { add(signature(primary)) }
        val alts = results.drop(1).filter { seen.add(signature(it)) }
        return RelationResult(fromId, toId, false, true, primary, alts.take(8))
    }

    private fun signature(r: KinPathResult) = r.readings.joinToString("|") { it.terms.relationEn }

    private fun score(path: List<Edge>): Int =
        path.size * 10 + path.count { it.bond != Bond.BIO } * 3 + path.count { it.kind == EdgeKind.SPOUSE } * 2

    // ------------------------------------------------------------------ search

    private fun allowed(prev: Edge?, e: Edge): Boolean {
        if (prev == null) return true
        val a = prev.from
        // up then down is a sibling relationship (handled by SIBLING edges), except through a step parent
        if (prev.kind == EdgeKind.PARENT && e.kind == EdgeKind.CHILD && !(prev.bond == Bond.STEP && e.bond != Bond.STEP)) return false
        if (prev.kind == EdgeKind.CHILD && e.kind == EdgeKind.PARENT) return false
        if (prev.kind == EdgeKind.SIBLING && e.kind == EdgeKind.SIBLING) return false
        if (prev.kind == EdgeKind.SPOUSE && e.kind == EdgeKind.SPOUSE) return false
        // never take a two-step detour to someone who is directly connected to where we started the window
        if (graph.adjacent(a, e.to)) return false
        return true
    }

    private fun findPaths(fromId: String, toId: String): List<List<Edge>> {
        val dist = graph.distancesTo(toId)
        val d0 = dist[fromId] ?: return emptyList()
        val maxDepth = minOf(d0 + 2, MAX_DEPTH)
        val results = ArrayList<List<Edge>>()
        val path = ArrayList<Edge>()
        val visited = HashSet<String>().apply { add(fromId) }

        fun dfs(node: String) {
            if (results.size >= MAX_PATHS) return
            for (e in graph.edges(node)) {
                if (e.to in visited) continue
                val remaining = maxDepth - path.size - 1
                val dn = dist[e.to] ?: continue
                if (dn > remaining) continue
                if (!allowed(path.lastOrNull(), e)) continue
                if (e.bond == Bond.EX && e.to != toId) continue // never route through an ex-spouse
                path.add(e); visited.add(e.to)
                if (e.to == toId) results.add(path.toList()) else dfs(e.to)
                path.removeAt(path.size - 1); visited.remove(e.to)
            }
        }
        dfs(fromId)
        return results
    }

    // ------------------------------------------------------------------ codes

    private data class Slot(val description: String, val options: List<Char>)

    private fun letter(e: Edge, to: Person): Char = when (e.kind) {
        EdgeKind.PARENT -> when (to.gender) { Gender.MALE -> 'F'; Gender.FEMALE -> 'M'; else -> 'P' }
        EdgeKind.CHILD -> when (to.gender) { Gender.MALE -> 'S'; Gender.FEMALE -> 'D'; else -> 'C' }
        EdgeKind.SIBLING -> when (to.gender) { Gender.MALE -> 'B'; Gender.FEMALE -> 'Z'; else -> 'G' }
        EdgeKind.SPOUSE -> when (to.gender) { Gender.MALE -> 'H'; Gender.FEMALE -> 'W'; else -> 'X' }
    }

    private fun bondChar(b: Bond): String = when (b) {
        Bond.STEP -> "s"; Bond.ADOPTIVE -> "a"; Bond.HALF -> "h"; Bond.EX -> "x"; Bond.BIO -> ""
    }

    private fun buildResult(path: List<Edge>): KinPathResult {
        val persons = path.map { data.persons.getValue(it.to) }
        val ego = data.persons.getValue(path.first().from)
        val target = persons.last()

        // Build token templates; '?' marks an age order that cannot be established.
        val tokens = ArrayList<String>()
        val slots = ArrayList<Pair<Int, Slot>>() // token index -> slot
        path.forEachIndexed { i, e ->
            val to = persons[i]
            val from = if (i == 0) ego else persons[i - 1]
            var age = ""
            if (e.kind == EdgeKind.SIBLING) {
                age = when (Ages.compareSiblings(from, to)) { AgeOrder.ELDER -> "e"; AgeOrder.YOUNGER -> "y"; AgeOrder.UNKNOWN -> "?" }
                if (age == "?") slots += tokens.size to Slot("${to.name} is § than ${from.name}", listOf('e', 'y'))
            }
            tokens += bondChar(e.bond) + age + letter(e, to)
        }
        var suffix = ""
        var suffixSlot: Slot? = null
        if (path.map { it.kind } == listOf(EdgeKind.PARENT, EdgeKind.SIBLING, EdgeKind.CHILD)) {
            suffix = when (Ages.compare(ego, target)) { AgeOrder.ELDER -> "~e"; AgeOrder.YOUNGER -> "~y"; AgeOrder.UNKNOWN -> "~?" }
            if (suffix == "~?") suffixSlot = Slot("${target.name} is § than ${ego.name}", listOf('e', 'y'))
        }

        // Expand unknown choices into every candidate code (capped).
        data class Cand(val toks: List<String>, val suf: String, val cond: List<String>)
        var cands = listOf(Cand(tokens, suffix, emptyList()))
        for ((idx, slot) in slots.take(3)) {
            cands = cands.flatMap { c ->
                slot.options.map { o ->
                    val t = c.toks.toMutableList()
                    t[idx] = t[idx].replace("?", o.toString())
                    Cand(t, c.suf, c.cond + slot.description.replace("§", if (o == 'e') "elder" else "younger"))
                }
            }
        }
        if (suffixSlot != null) cands = cands.flatMap { c ->
            suffixSlot.options.map { o -> Cand(c.toks, "~$o", c.cond + suffixSlot.description.replace("§", if (o == 'e') "elder" else "younger")) }
        }
        // Any remaining unknowns beyond the cap are treated as unknown/neutral.
        val readings = cands.map { c ->
            val code = c.toks.joinToString("").replace("?", "") + c.suf
            Reading(code, c.cond.takeIf { it.isNotEmpty() }?.joinToString(" and "), terminology.resolve(code))
        }.distinctBy { it.code }

        val stepTexts = path.mapIndexed { i, e ->
            val from = if (i == 0) ego else persons[i - 1]
            val to = persons[i]
            val ageChar = if (e.kind == EdgeKind.SIBLING) when (Ages.compareSiblings(from, to)) {
                AgeOrder.ELDER -> 'e'; AgeOrder.YOUNGER -> 'y'; AgeOrder.UNKNOWN -> null
            } else null
            val name = EnglishNamer.tokenName(EnglishNamer.Tok(bondChar(e.bond).firstOrNull(), ageChar, letter(e, to)))
            "${from.name}'s $name is ${to.name}"
        }
        val summary = readings.map { it.terms.relationEn }.distinct().joinToString(" / ")
        return KinPathResult(path, stepTexts, readings, summary)
    }
}
