package com.infinitefamily.domain

/**
 * Positions the user has dragged people to ("investigation board" arrangement). Stored as one setting
 * string, so it is saved with the app data and included in backups. The automatic layout is never
 * changed: resetting just forgets these positions.
 */
object ManualLayout {
    const val KEY = "layout_manual"

    fun decode(raw: String?): Map<String, Pair<Float, Float>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Pair<Float, Float>>()
        for (item in raw.split(';')) {
            val id = item.substringBefore(':', ""); val xy = item.substringAfter(':', "").split(',')
            val x = xy.getOrNull(0)?.toFloatOrNull(); val y = xy.getOrNull(1)?.toFloatOrNull()
            if (id.isNotEmpty() && x != null && y != null && x.isFinite() && y.isFinite()) out[id] = x to y
        }
        return out
    }

    fun encode(m: Map<String, Pair<Float, Float>>): String =
        m.entries.joinToString(";") { (id, p) -> "$id:${Math.round(p.first * 10) / 10f},${Math.round(p.second * 10) / 10f}" }

    /** Automatic layout with the dragged people moved to where the user put them. */
    fun apply(layout: TreeLayoutResult, manual: Map<String, Pair<Float, Float>>): TreeLayoutResult {
        if (manual.isEmpty()) return layout
        val moved = HashMap(layout.positions)
        for ((id, p) in manual) { val n = moved[id] ?: continue; moved[id] = n.copy(x = p.first, y = p.second) }
        return TreeLayoutResult(moved)
    }

    /** Number of saved positions that still belong to somebody in the tree. */
    fun count(layout: TreeLayoutResult, manual: Map<String, Pair<Float, Float>>) = manual.keys.count { it in layout.positions }
}
