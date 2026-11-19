package com.infinitefamily.domain

import java.time.LocalDate

/** A date that may only be known to year or month precision. */
data class PartialDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    fun toIso(): String = buildString {
        append(year.toString().padStart(4, '0'))
        if (month != null) { append('-'); append(month.toString().padStart(2, '0')) }
        if (month != null && day != null) { append('-'); append(day.toString().padStart(2, '0')) }
    }

    companion object {
        fun parse(s: String?): PartialDate? {
            if (s.isNullOrBlank()) return null
            val parts = s.trim().split('-')
            val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
            if (y !in 1..9999) return null
            val m = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size > 1 && (m == null || m !in 1..12)) return null
            val d = parts.getOrNull(2)?.toIntOrNull()
            if (parts.size > 2) {
                if (m == null || d == null) return null
                val ok = runCatching { LocalDate.of(y, m, d) }.isSuccess
                if (!ok) return null
            }
            if (parts.size > 3) return null
            return PartialDate(y, m, d)
        }

        /**
         * Compares two dates. Returns negative when [a] is earlier, positive when later and null when
         * the available precision cannot separate them (e.g. both only "1980").
         */
        fun compare(a: PartialDate, b: PartialDate): Int? {
            if (a.year != b.year) return a.year.compareTo(b.year)
            if (a.month == null || b.month == null) return null
            if (a.month != b.month) return a.month.compareTo(b.month)
            if (a.day == null || b.day == null) return null
            if (a.day != b.day) return a.day.compareTo(b.day)
            return 0
        }
    }
}

enum class AgeOrder { ELDER, YOUNGER, UNKNOWN }

object Ages {
    fun ageYears(p: Person, today: LocalDate): Int? {
        val dob = PartialDate.parse(p.dob) ?: return null
        val end = if (p.deceased) PartialDate.parse(p.deathDate)?.let {
            LocalDate.of(it.year, it.month ?: 12, it.day ?: 28)
        } ?: return null else today
        var age = end.year - dob.year
        if (dob.month != null) {
            val dm = dob.month; val dd = dob.day ?: 1
            if (end.monthValue < dm || (end.monthValue == dm && end.dayOfMonth < dd)) age--
        }
        return age.coerceAtLeast(0)
    }

    fun ageText(p: Person, today: LocalDate): String? {
        val a = ageYears(p, today) ?: return null
        return if (p.deceased) "Died aged $a" else "$a years"
    }

    private fun rank(r: RelativeAge) = when (r) {
        RelativeAge.OLDER -> 0; RelativeAge.SAME -> 1; RelativeAge.YOUNGER -> 2; RelativeAge.UNKNOWN -> -1
    }

    /** Is [other] elder or younger than [ego]? Never guesses: UNKNOWN when nothing establishes it. */
    fun compare(ego: Person, other: Person): AgeOrder {
        val a = PartialDate.parse(ego.dob)
        val b = PartialDate.parse(other.dob)
        if (a != null && b != null) {
            val c = PartialDate.compare(b, a)
            if (c != null && c != 0) return if (c < 0) AgeOrder.ELDER else AgeOrder.YOUNGER
        }
        val ra = rank(ego.relativeAge); val rb = rank(other.relativeAge)
        if (ra >= 0 && rb >= 0 && ra != rb) {
            return if (rb < ra) AgeOrder.ELDER else AgeOrder.YOUNGER
        }
        return AgeOrder.UNKNOWN
    }

    /**
     * Order between two siblings. An explicit birth order (set by the user, e.g. drag and drop)
     * wins, then dates of birth, then relative-age hints.
     */
    fun compareSiblings(ego: Person, sib: Person): AgeOrder {
        val oa = ego.birthOrder; val ob = sib.birthOrder
        if (oa != null && ob != null && oa != ob) return if (ob < oa) AgeOrder.ELDER else AgeOrder.YOUNGER
        return compare(ego, sib)
    }
}
