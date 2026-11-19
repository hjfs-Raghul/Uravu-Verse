package com.infinitefamily.domain

enum class EdgeKind { PARENT, CHILD, SPOUSE, SIBLING }

/**
 * Quality of a connection.
 * BIO: biological (or a current/widowed marriage). ADOPTIVE, STEP as named.
 * HALF: half-sibling. EX: ended marriage (divorced / previous).
 */
enum class Bond { BIO, ADOPTIVE, STEP, HALF, EX }

data class Edge(val from: String, val to: String, val kind: EdgeKind, val bond: Bond)

/**
 * Directed view of the family data that the relationship engine walks.
 *
 * Besides the stored parent links and marriages it derives:
 *  - SIBLING edges (full / half / adoptive) from shared parents,
 *  - STEP parent/child edges from a current or widowed marriage to someone who is not the real
 *    parent of that spouse's children. Nothing derived is ever written back as a biological link.
 */
class KinGraph(val data: FamilyData) {
    private val index = data.index
    private val cache = HashMap<String, List<Edge>>()

    fun edges(id: String): List<Edge> = cache.getOrPut(id) { compute(id) }

    private fun bond(t: LinkType) = when (t) {
        LinkType.BIOLOGICAL -> Bond.BIO
        LinkType.ADOPTIVE -> Bond.ADOPTIVE
        LinkType.STEP -> Bond.STEP
    }

    private fun isActiveMarriage(m: Marriage) = m.status == MarriageStatus.CURRENT || m.status == MarriageStatus.WIDOWED

    private fun compute(id: String): List<Edge> {
        val out = LinkedHashMap<Pair<EdgeKind, String>, Edge>()
        fun put(e: Edge) { out.putIfAbsent(e.kind to e.to, e) }
        if (data.persons[id] == null) return emptyList()

        // Stored parents and children.
        for (l in index.parentLinks(id)) if (l.parentId in data.persons) put(Edge(id, l.parentId, EdgeKind.PARENT, bond(l.type)))
        for (l in index.childLinks(id)) if (l.childId in data.persons) put(Edge(id, l.childId, EdgeKind.CHILD, bond(l.type)))

        // Derived step-parents: the active spouse of one of my real parents who is not my parent.
        val myParents = index.realParentIds(id)
        for (p in myParents) for (m in index.marriages(p)) if (isActiveMarriage(m)) {
            val t = m.other(p)
            if (t !in myParents && t in data.persons) put(Edge(id, t, EdgeKind.PARENT, Bond.STEP))
        }
        // Derived step-children: children of my active spouse that are not mine.
        val myChildren = index.realChildIds(id).toSet()
        for (m in index.marriages(id)) if (isActiveMarriage(m)) {
            val t = m.other(id)
            for (c in index.realChildIds(t)) if (c !in myChildren && c in data.persons) put(Edge(id, c, EdgeKind.CHILD, Bond.STEP))
        }

        // Marriages.
        for (m in index.marriages(id)) {
            val o = m.other(id)
            if (o in data.persons) put(Edge(id, o, EdgeKind.SPOUSE, if (isActiveMarriage(m)) Bond.BIO else Bond.EX))
        }

        // Siblings derived from shared real parents.
        for (s in index.siblingIds(id)) {
            if (s !in data.persons) continue
            val shared = index.sharedParents(id, s)
            val adoptive = (index.parentLinks(id) + index.parentLinks(s))
                .any { it.parentId in shared && it.type == LinkType.ADOPTIVE }
            val full = shared.size >= 2 || (index.realParentIds(id).size == 1 && index.realParentIds(s).size == 1)
            put(Edge(id, s, EdgeKind.SIBLING, if (adoptive) Bond.ADOPTIVE else if (full) Bond.BIO else Bond.HALF))
        }
        return out.values.toList()
    }

    fun adjacent(a: String, b: String) = edges(a).any { it.to == b }

    /** Unrestricted BFS distances from [target] (every edge has an inverse, so this is symmetric). */
    fun distancesTo(target: String): Map<String, Int> {
        val dist = HashMap<String, Int>()
        dist[target] = 0
        val q = ArrayDeque<String>().apply { add(target) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            for (e in edges(n)) if (e.to !in dist) { dist[e.to] = dist.getValue(n) + 1; q.add(e.to) }
        }
        return dist
    }
}
