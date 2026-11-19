package com.infinitefamily.domain

data class NodePos(val id: String, val x: Float, val y: Float, val generation: Int)

data class TreeLayoutResult(val positions: Map<String, NodePos>) {
    val bounds: FloatArray by lazy {
        if (positions.isEmpty()) floatArrayOf(0f, 0f, 0f, 0f) else {
            val xs = positions.values.map { it.x }; val ys = positions.values.map { it.y }
            floatArrayOf(xs.min() - TreeLayout.NODE_W / 2, ys.min() - TreeLayout.NODE_H / 2, xs.max() + TreeLayout.NODE_W / 2, ys.max() + TreeLayout.NODE_H / 2)
        }
    }
}

/**
 * Generates positions from relationship data (the canvas never stores lines or coordinates).
 *
 * 1. Generations by walking parent/child/marriage links from the reference person.
 * 2. Spouses of one generation form "clusters" that are laid out side by side (earlier marriages left,
 *    current marriage right).
 * 3. Clusters in a row are ordered by the barycentre of their parents, then by birth order, and
 *    positioned with an isotonic (pool-adjacent-violators) pass so nothing overlaps while staying close
 *    to the parents / children. Down and up sweeps are repeated.
 * 4. Disconnected groups are placed side by side.
 */
object TreeLayout {
    const val NODE_W = 150f
    const val NODE_H = 190f
    const val SPOUSE_GAP = 46f
    const val H_GAP = 56f
    const val ROW_PITCH = 360f

    private class Cluster(val members: List<String>) {
        val width = members.size * NODE_W + (members.size - 1) * SPOUSE_GAP
        fun offset(id: String) = -width / 2 + NODE_W / 2 + members.indexOf(id) * (NODE_W + SPOUSE_GAP)
        var x = 0f
    }

    fun compute(data: FamilyData): TreeLayoutResult {
        if (data.persons.isEmpty()) return TreeLayoutResult(emptyMap())
        val idx = data.index
        val gen = Generations.compute(data)

        // connected components
        val comp = HashMap<String, Int>()
        val order = buildList {
            data.rootId?.takeIf { it in data.persons }?.let { add(it) }
            addAll(data.persons.values.sortedBy { it.createdAt }.map { it.id })
        }
        var nComp = 0
        for (s in order) {
            if (s in comp) continue
            val q = ArrayDeque<String>().apply { add(s) }; comp[s] = nComp
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                val nb = idx.parentLinks(n).map { it.parentId } + idx.childLinks(n).map { it.childId } + idx.marriages(n).map { it.other(n) }
                for (o in nb) if (o in data.persons && o !in comp) { comp[o] = nComp; q.add(o) }
            }
            nComp++
        }

        val result = HashMap<String, NodePos>()
        var cursorX = 0f
        for (c in 0 until nComp) {
            val ids = order.filter { comp[it] == c }
            val local = layoutComponent(data, ids, gen)
            if (local.isEmpty()) continue
            val minX = local.values.minOf { it.first - NODE_W / 2 }
            val maxX = local.values.maxOf { it.first + NODE_W / 2 }
            val shift = if (c == 0) 0f else cursorX - minX
            for ((id, p) in local) result[id] = NodePos(id, p.first + shift, p.second * ROW_PITCH, gen.getValue(id))
            cursorX = (if (c == 0) maxX else maxX + shift) + NODE_W
        }
        // Centre the reference person at the origin.
        val root = data.rootId?.let { result[it] }
        if (root != null) {
            val dx = root.x; val dy = root.y
            for ((id, p) in result.toMap()) result[id] = p.copy(x = p.x - dx, y = p.y - dy)
        }
        return TreeLayoutResult(result)
    }

    private fun clusterOrder(data: FamilyData, ids: List<String>, gen: Map<String, Int>): List<Cluster> {
        val idx = data.index
        val placed = HashSet<String>()
        val clusters = ArrayList<Cluster>()
        for (start in ids) {
            if (start in placed) continue
            // collect the same-generation marriage component
            val comp = LinkedHashSet<String>(); val q = ArrayDeque<String>().apply { add(start) }; comp.add(start)
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                for (m in idx.marriages(n)) { val o = m.other(n); if (gen[o] == gen[n] && o in data.persons && comp.add(o)) q.add(o) }
            }
            val pivot = comp.maxByOrNull { p -> idx.marriages(p).count { it.other(p) in comp } } ?: start
            val visited = HashSet<String>()
            fun expand(p: String, side: Int): List<String> {
                visited += p
                val sp = idx.marriages(p).filter { it.other(p) in comp && it.other(p) !in visited }
                val ended = sp.filter { it.status != MarriageStatus.CURRENT }
                val current = sp.filter { it.status == MarriageStatus.CURRENT }
                val left = if (side == 1) emptyList() else if (side == -1) ended + current else ended
                val right = if (side == -1) emptyList() else if (side == 1) ended + current else current
                val leftBlocks = left.map { it.other(p) }.filter { it !in visited }.map { expand(it, -1) }
                val rightBlocks = right.map { it.other(p) }.filter { it !in visited }.map { expand(it, 1) }
                return leftBlocks.reversed().flatten() + p + rightBlocks.flatten()
            }
            val arranged = expand(pivot, 0)
            placed += arranged
            clusters += Cluster(arranged)
        }
        return clusters
    }

    /** Returns local (x, generation row) for each person of one component. */
    private fun layoutComponent(data: FamilyData, ids: List<String>, gen: Map<String, Int>): Map<String, Pair<Float, Float>> {
        val idx = data.index
        val clusters = clusterOrder(data, ids, gen)
        val clusterOf = HashMap<String, Cluster>().also { m -> clusters.forEach { c -> c.members.forEach { m[it] = c } } }
        val rows = clusters.groupBy { gen.getValue(it.members.first()) }.toSortedMap()
        val rowList = rows.mapValues { it.value.toMutableList() }

        // initial left-to-right order per row, in discovery order
        for ((_, list) in rowList) {
            var x = 0f
            for (c in list) { c.x = x + c.width / 2; x += c.width + H_GAP }
        }

        fun nodeX(id: String): Float? = clusterOf[id]?.let { it.x + it.offset(id) }
        fun parentAnchor(id: String): Float? {
            val ps = idx.realParentIds(id).mapNotNull { nodeX(it) }
            return if (ps.isEmpty()) null else ps.average().toFloat()
        }
        fun childAnchor(id: String): Float? {
            val cs = idx.realChildIds(id).mapNotNull { nodeX(it) }
            return if (cs.isEmpty()) null else cs.average().toFloat()
        }
        fun siblingRank(id: String): Double = data.persons[id]?.birthOrder ?: Double.MAX_VALUE

        fun place(list: List<Cluster>, desired: List<Float?>) {
            if (list.isEmpty()) return
            // isotonic regression on centres with minimum spacing
            val n = list.size
            val off = FloatArray(n)
            for (i in 1 until n) off[i] = off[i - 1] + (list[i - 1].width + list[i].width) / 2 + H_GAP
            val target = FloatArray(n) { (desired[it] ?: list[it].x) - off[it] }
            val blocksMean = ArrayList<Float>(); val blocksCount = ArrayList<Int>()
            for (i in 0 until n) {
                blocksMean.add(target[i]); blocksCount.add(1)
                while (blocksMean.size > 1 && blocksMean[blocksMean.size - 2] > blocksMean.last()) {
                    val c2 = blocksCount.removeAt(blocksCount.size - 1); val m2 = blocksMean.removeAt(blocksMean.size - 1)
                    val c1 = blocksCount.last(); val m1 = blocksMean.last()
                    blocksMean[blocksMean.size - 1] = (m1 * c1 + m2 * c2) / (c1 + c2)
                    blocksCount[blocksCount.size - 1] = c1 + c2
                }
            }
            var i = 0
            for (b in blocksMean.indices) repeat(blocksCount[b]) { list[i].x = blocksMean[b] + off[i]; i++ }
        }

        fun clusterDesiredDown(c: Cluster): Float? {
            val v = c.members.mapNotNull { m -> parentAnchor(m)?.let { it - c.offset(m) } }
            return if (v.isEmpty()) null else v.average().toFloat()
        }
        fun clusterDesiredUp(c: Cluster): Float? {
            val v = c.members.mapNotNull { m -> childAnchor(m)?.let { it - c.offset(m) } }
            return if (v.isEmpty()) null else v.average().toFloat()
        }

        val keys = rowList.keys.toList()
        repeat(5) {
            // downward sweep: order by parents, then birth order
            for (g in keys.drop(1)) {
                val list = rowList.getValue(g)
                val sortKey = list.associateWith { c ->
                    val d = clusterDesiredDown(c)
                    d ?: c.x
                }
                list.sortWith(compareBy<Cluster> { sortKey.getValue(it) }.thenBy { c -> c.members.minOf { siblingRank(it) } }.thenBy { it.x })
                place(list, list.map { clusterDesiredDown(it) })
            }
            // upward sweep: parents move above their children
            for (g in keys.reversed().drop(1)) {
                val list = rowList.getValue(g)
                place(list, list.map { clusterDesiredUp(it) })
            }
        }
        for (g in keys.drop(1)) { val list = rowList.getValue(g); place(list, list.map { clusterDesiredDown(it) ?: it.x }) }

        val out = HashMap<String, Pair<Float, Float>>()
        for (c in clusters) for (m in c.members) out[m] = (c.x + c.offset(m)) to gen.getValue(m).toFloat()
        return out
    }
}
