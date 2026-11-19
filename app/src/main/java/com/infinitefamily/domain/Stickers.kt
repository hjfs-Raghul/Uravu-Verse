package com.infinitefamily.domain

import java.time.LocalDate

/** Pure description of how a sticker should look. The canvas only renders this. */
data class StickerStyle(val alpha: Float, val desaturate: Boolean)

object StickerStyles {
    /** Deceased people fade the whole sticker. Marriage status never affects it. */
    fun forPerson(p: Person) = if (p.deceased) StickerStyle(0.42f, true) else StickerStyle(1f, false)
}

enum class StickerCategory(val id: String, val label: String, val variants: Int) {
    INFANT("infant", "Infant", 3),
    BOY("boy", "Young boy", 6),
    GIRL("girl", "Young girl", 6),
    YOUNG_MAN("young_man", "Young man", 4),
    YOUNG_WOMAN("young_woman", "Young woman", 4),
    MIDDLE_MAN("middle_man", "Middle-aged man", 3),
    MIDDLE_WOMAN("middle_woman", "Middle-aged woman", 3),
    ELDER_MAN("elder_man", "Elderly grandfather", 3),
    ELDER_WOMAN("elder_woman", "Elderly grandmother", 3);

    companion object {
        fun byId(id: String?) = values().firstOrNull { it.id == id }

        /** Category from age when known, otherwise from the generation relative to the reference person. */
        fun choose(p: Person, today: LocalDate, generationDelta: Int): StickerCategory {
            byId(p.stickerCategory)?.let { return it }
            val female = p.gender == Gender.FEMALE
            val age = Ages.ageYears(p, today)
            // For a deceased person with no death date, fall back to the generation estimate.
            val years = age ?: estimateYears(p, generationDelta)
            return when {
                years < 2 -> INFANT
                years < 14 -> if (female) GIRL else BOY
                years < 36 -> if (female) YOUNG_WOMAN else YOUNG_MAN
                years < 61 -> if (female) MIDDLE_WOMAN else MIDDLE_MAN
                else -> if (female) ELDER_WOMAN else ELDER_MAN
            }
        }

        private fun estimateYears(p: Person, gen: Int): Int {
            val base = when {
                gen <= -2 -> 70
                gen == -1 -> 50
                gen == 0 -> when (p.relativeAge) { RelativeAge.OLDER -> 45; RelativeAge.YOUNGER -> 22; else -> 32 }
                gen == 1 -> 10
                else -> 3
            }
            return base
        }
    }
}

object Generations {
    /** Generation of everyone relative to [rootId] (parent = -1, child = +1, spouse/sibling = 0). */
    fun compute(data: FamilyData): Map<String, Int> {
        val gen = HashMap<String, Int>()
        val idx = data.index
        val starts = buildList {
            data.rootId?.takeIf { it in data.persons }?.let { add(it) }
            addAll(data.persons.values.sortedBy { it.createdAt }.map { it.id })
        }
        for (s in starts) {
            if (s in gen) continue
            gen[s] = 0
            val q = ArrayDeque<String>().apply { add(s) }
            while (q.isNotEmpty()) {
                val n = q.removeFirst(); val g = gen.getValue(n)
                fun visit(o: String, og: Int) { if (o in data.persons && o !in gen) { gen[o] = og; q.add(o) } }
                idx.parentLinks(n).filter { it.type != LinkType.STEP }.forEach { visit(it.parentId, g - 1) }
                idx.childLinks(n).filter { it.type != LinkType.STEP }.forEach { visit(it.childId, g + 1) }
                idx.marriages(n).forEach { visit(it.other(n), g) }
                idx.parentLinks(n).filter { it.type == LinkType.STEP }.forEach { visit(it.parentId, g - 1) }
                idx.childLinks(n).filter { it.type == LinkType.STEP }.forEach { visit(it.childId, g + 1) }
            }
        }
        return gen
    }
}
