package com.infinitefamily.domain

import java.time.LocalDate

/** Pure description of how a sticker should look. The canvas only renders this. */
data class StickerStyle(val alpha: Float, val desaturate: Boolean)

object StickerStyles {
    /** Deceased people fade the whole sticker. Marriage status never affects it. */
    fun forPerson(p: Person) = if (p.deceased) StickerStyle(0.42f, true) else StickerStyle(1f, false)
}

/** Number of distinct drawings available in every sticker category. */
const val STICKER_VARIANTS = 12

enum class StickerStage { BABY, KID, BOY_GIRL, TEEN, ADULT, FAMILY, SENIOR, OLD }

enum class StickerCategory(val id: String, val label: String, val stage: StickerStage, val female: Boolean) {
    BABY_M("baby_m", "Baby boy", StickerStage.BABY, false),
    BABY_F("baby_f", "Baby girl", StickerStage.BABY, true),
    KID_M("kid_m", "Kid (boy)", StickerStage.KID, false),
    KID_F("kid_f", "Kid (girl)", StickerStage.KID, true),
    BOY("boy", "Boy", StickerStage.BOY_GIRL, false),
    GIRL("girl", "Girl", StickerStage.BOY_GIRL, true),
    TEEN_M("teen_m", "Teen boy", StickerStage.TEEN, false),
    TEEN_F("teen_f", "Teen girl", StickerStage.TEEN, true),
    ADULT_M("adult_m", "Adult man", StickerStage.ADULT, false),
    ADULT_F("adult_f", "Adult woman", StickerStage.ADULT, true),
    FAMILY_M("family_m", "Family man", StickerStage.FAMILY, false),
    FAMILY_F("family_f", "Family woman", StickerStage.FAMILY, true),
    SENIOR_M("senior_m", "50s-60s man", StickerStage.SENIOR, false),
    SENIOR_F("senior_f", "50s-60s woman", StickerStage.SENIOR, true),
    OLD_M("old_m", "Old man", StickerStage.OLD, false),
    OLD_F("old_f", "Old woman", StickerStage.OLD, true);

    val variants: Int get() = STICKER_VARIANTS

    companion object {
        private val legacy = mapOf(
            "infant" to (BABY_M to BABY_F), "young_man" to (ADULT_M to ADULT_M), "young_woman" to (ADULT_F to ADULT_F),
            "middle_man" to (FAMILY_M to FAMILY_M), "middle_woman" to (FAMILY_F to FAMILY_F),
            "elder_man" to (OLD_M to OLD_M), "elder_woman" to (OLD_F to OLD_F),
        )

        /** Finds a category by id. Ids saved by older versions are mapped to their closest new category. */
        fun byId(id: String?, female: Boolean = false): StickerCategory? {
            if (id == null) return null
            values().firstOrNull { it.id == id }?.let { return it }
            val pair = legacy[id] ?: return null
            return if (female) pair.second else pair.first
        }

        fun of(stage: StickerStage, female: Boolean) = values().first { it.stage == stage && it.female == female }

        /** All categories of one gender, in life order (used by the chooser). */
        fun forGender(female: Boolean) = values().filter { it.female == female }

        /** Category from age when known, otherwise from the generation relative to the reference person. */
        fun choose(p: Person, today: LocalDate, generationDelta: Int): StickerCategory {
            val female = p.gender == Gender.FEMALE
            byId(p.stickerCategory, female)?.let { return it }
            val age = Ages.ageYears(p, today)
            // For a deceased person with no death date, fall back to the generation estimate.
            val years = age ?: estimateYears(p, generationDelta)
            val stage = when {
                years < 2 -> StickerStage.BABY
                years < 9 -> StickerStage.KID
                years < 13 -> StickerStage.BOY_GIRL
                years < 20 -> StickerStage.TEEN
                years < 35 -> StickerStage.ADULT
                years < 50 -> StickerStage.FAMILY
                years < 65 -> StickerStage.SENIOR
                else -> StickerStage.OLD
            }
            return of(stage, female)
        }

        private fun estimateYears(p: Person, gen: Int): Int = when {
            gen <= -2 -> 72
            gen == -1 -> 52
            gen == 0 -> when (p.relativeAge) { RelativeAge.OLDER -> 45; RelativeAge.YOUNGER -> 22; else -> 32 }
            gen == 1 -> 10
            else -> 3
        }
    }
}

enum class Hair { SHORT, SPIKY, PARTED, CURLY, BUN, LONG, PONY, PIGTAILS, BRAIDS, WISPS, BALD, TUFT, CAP, BUZZ, WAVY, BOB, TOPKNOT, BALD_SIDES }
enum class Garment { SHIRT, TEE, KURTA, SAREE, DRESS, SWADDLE, SHAWL, HOODIE, SWEATER, SUIT }

/** Optional face details. */
data class Acc(
    val glasses: Boolean = false, val moustache: Boolean = false, val beard: Boolean = false, val bindi: Boolean = false,
    val wrinkles: Boolean = false, val earrings: Boolean = false, val freckles: Boolean = false, val bow: Boolean = false,
)

data class Look(val hair: Hair, val garment: Garment, val acc: Acc = Acc())

/** The catalogue of drawings: [STICKER_VARIANTS] different looks for every category. */
object StickerLooks {
    private val none = Acc()
    private val gl = Acc(glasses = true)
    private val mo = Acc(moustache = true)
    private val be = Acc(beard = true)
    private val fr = Acc(freckles = true)
    private val bow = Acc(bow = true)
    private val ear = Acc(earrings = true)
    private val bin = Acc(bindi = true)
    private val wr = Acc(wrinkles = true)

    private class Cfg(val hairs: List<Hair>, val garments: List<Garment>, val accs: List<Acc>)

    private fun cfg(c: StickerCategory): Cfg {
        return when (c) {
            StickerCategory.BABY_M -> Cfg(listOf(Hair.TUFT, Hair.BALD, Hair.CURLY, Hair.BUZZ), listOf(Garment.SWADDLE, Garment.TEE, Garment.HOODIE), listOf(none, fr))
            StickerCategory.BABY_F -> Cfg(listOf(Hair.TUFT, Hair.CURLY, Hair.WISPS, Hair.BUZZ), listOf(Garment.SWADDLE, Garment.TEE, Garment.DRESS), listOf(none, bow, ear))
            StickerCategory.KID_M -> Cfg(listOf(Hair.SHORT, Hair.SPIKY, Hair.CURLY, Hair.PARTED, Hair.BUZZ, Hair.CAP), listOf(Garment.TEE, Garment.HOODIE, Garment.SWEATER), listOf(none, gl, fr))
            StickerCategory.KID_F -> Cfg(listOf(Hair.PIGTAILS, Hair.PONY, Hair.BRAIDS, Hair.BUN, Hair.CURLY, Hair.BOB), listOf(Garment.DRESS, Garment.TEE, Garment.SWEATER), listOf(none, bow, gl, fr))
            StickerCategory.BOY -> Cfg(listOf(Hair.SHORT, Hair.SPIKY, Hair.PARTED, Hair.CURLY, Hair.CAP, Hair.BUZZ, Hair.WAVY), listOf(Garment.TEE, Garment.SHIRT, Garment.HOODIE, Garment.KURTA), listOf(none, gl, fr))
            StickerCategory.GIRL -> Cfg(listOf(Hair.LONG, Hair.PONY, Hair.BRAIDS, Hair.BOB, Hair.BUN, Hair.PIGTAILS, Hair.WAVY), listOf(Garment.DRESS, Garment.TEE, Garment.KURTA, Garment.SWEATER), listOf(none, bin, gl, ear))
            StickerCategory.TEEN_M -> Cfg(listOf(Hair.SPIKY, Hair.WAVY, Hair.PARTED, Hair.BUZZ, Hair.CURLY, Hair.SHORT, Hair.CAP), listOf(Garment.HOODIE, Garment.TEE, Garment.SHIRT, Garment.KURTA), listOf(none, gl, fr))
            StickerCategory.TEEN_F -> Cfg(listOf(Hair.LONG, Hair.PONY, Hair.BOB, Hair.WAVY, Hair.BRAIDS, Hair.BUN, Hair.TOPKNOT), listOf(Garment.HOODIE, Garment.KURTA, Garment.TEE, Garment.DRESS), listOf(none, gl, bin, ear))
            StickerCategory.ADULT_M -> Cfg(listOf(Hair.SHORT, Hair.PARTED, Hair.WAVY, Hair.CURLY, Hair.SPIKY, Hair.BUZZ), listOf(Garment.SHIRT, Garment.TEE, Garment.KURTA, Garment.SUIT), listOf(none, gl, mo, be))
            StickerCategory.ADULT_F -> Cfg(listOf(Hair.LONG, Hair.PONY, Hair.BUN, Hair.BOB, Hair.WAVY, Hair.BRAIDS), listOf(Garment.KURTA, Garment.SAREE, Garment.DRESS, Garment.SHIRT), listOf(none, bin, gl, ear))
            StickerCategory.FAMILY_M -> Cfg(listOf(Hair.PARTED, Hair.SHORT, Hair.BALD, Hair.WAVY, Hair.BUZZ, Hair.CURLY), listOf(Garment.SHIRT, Garment.SUIT, Garment.KURTA, Garment.SWEATER), listOf(none, mo, gl, be, Acc(moustache = true, glasses = true)))
            StickerCategory.FAMILY_F -> Cfg(listOf(Hair.BUN, Hair.LONG, Hair.BOB, Hair.PONY, Hair.WAVY, Hair.BRAIDS), listOf(Garment.SAREE, Garment.KURTA, Garment.DRESS, Garment.SWEATER), listOf(bin, Acc(bindi = true, earrings = true), Acc(bindi = true, glasses = true), ear, none))
            StickerCategory.SENIOR_M -> Cfg(listOf(Hair.BALD_SIDES, Hair.SHORT, Hair.PARTED, Hair.BUZZ, Hair.WAVY, Hair.BALD), listOf(Garment.SHIRT, Garment.KURTA, Garment.SWEATER, Garment.SUIT), listOf(mo, Acc(moustache = true, glasses = true), Acc(beard = true, wrinkles = true), gl, wr))
            StickerCategory.SENIOR_F -> Cfg(listOf(Hair.BUN, Hair.BOB, Hair.WISPS, Hair.LONG, Hair.PONY, Hair.WAVY), listOf(Garment.SAREE, Garment.KURTA, Garment.SHAWL, Garment.SWEATER), listOf(bin, Acc(bindi = true, glasses = true), Acc(glasses = true, wrinkles = true), Acc(earrings = true, bindi = true), wr))
            StickerCategory.OLD_M -> Cfg(listOf(Hair.BALD, Hair.BALD_SIDES, Hair.TUFT, Hair.WISPS, Hair.SHORT, Hair.BUZZ), listOf(Garment.SHAWL, Garment.SHIRT, Garment.KURTA, Garment.SWEATER),
                listOf(Acc(moustache = true, wrinkles = true), Acc(glasses = true, wrinkles = true), Acc(beard = true, wrinkles = true), Acc(moustache = true, glasses = true, wrinkles = true), wr))
            StickerCategory.OLD_F -> Cfg(listOf(Hair.WISPS, Hair.BUN, Hair.BOB, Hair.TOPKNOT, Hair.WAVY, Hair.SHORT), listOf(Garment.SAREE, Garment.SHAWL, Garment.KURTA, Garment.SWEATER),
                listOf(Acc(bindi = true, wrinkles = true), Acc(glasses = true, wrinkles = true), Acc(bindi = true, glasses = true, wrinkles = true), Acc(earrings = true, wrinkles = true), wr))
        }
    }

    private val cache = HashMap<StickerCategory, List<Look>>()

    /** Exactly [STICKER_VARIANTS] distinct looks, spread over the hair/garment/detail combinations. */
    fun forCategory(c: StickerCategory): List<Look> = cache.getOrPut(c) {
        val k = cfg(c)
        val total = k.hairs.size * k.garments.size * k.accs.size
        var stride = 7
        while (gcd(stride, total) != 1) stride++
        List(STICKER_VARIANTS) { i ->
            val n = (i * stride + c.ordinal * 5) % total
            Look(k.hairs[n % k.hairs.size], k.garments[(n / k.hairs.size) % k.garments.size], k.accs[(n / (k.hairs.size * k.garments.size)) % k.accs.size])
        }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
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
