package com.infinitefamily.domain

data class MarriageLine(val id: String, val aId: String, val bId: String, val status: MarriageStatus)

/** Children that share exactly the same set of real parents and the same link type. */
data class ChildGroup(val parentIds: List<String>, val childIds: List<String>, val type: LinkType, val marriageId: String?)

data class HalfSiblingLink(val aId: String, val bId: String)
data class StepLink(val parentId: String, val childId: String)

/** Everything the canvas draws between stickers, derived from relationship data. */
data class TreeScene(
    val marriages: List<MarriageLine>,
    val groups: List<ChildGroup>,
    val halfLinks: List<HalfSiblingLink>,
    val stepLinks: List<StepLink>,
) {
    companion object {
        fun build(data: FamilyData): TreeScene {
            val idx = data.index
            val marriages = data.marriages.values.sortedBy { it.order }.map { MarriageLine(it.id, it.personAId, it.personBId, it.status) }

            val groups = ArrayList<ChildGroup>()
            val byKey = LinkedHashMap<Pair<List<String>, LinkType>, MutableList<String>>()
            for (child in data.persons.values.sortedWith(compareBy({ it.birthOrder ?: Double.MAX_VALUE }, { it.dob ?: "9999" }, { it.createdAt }))) {
                val links = idx.parentLinks(child.id).filter { it.type != LinkType.STEP && it.parentId in data.persons }
                if (links.isEmpty()) continue
                for (type in links.map { it.type }.distinct()) {
                    val parents = links.filter { it.type == type }.map { it.parentId }.sorted()
                    byKey.getOrPut(parents to type) { mutableListOf() }.add(child.id)
                }
            }
            for ((k, kids) in byKey) {
                val (parents, type) = k
                val marriage = if (parents.size == 2) data.marriages.values.firstOrNull { it.involves(parents[0]) && it.involves(parents[1]) }?.id else null
                groups += ChildGroup(parents, kids, type, marriage)
            }

            // half-sibling links between neighbours in a parent's birth order that do not share both parents
            val half = LinkedHashSet<HalfSiblingLink>()
            for (p in data.persons.keys) {
                val kids = idx.realChildIds(p).mapNotNull { data.persons[it] }
                    .sortedWith(compareBy({ it.birthOrder ?: Double.MAX_VALUE }, { it.dob ?: "9999" }, { it.createdAt }))
                for (i in 0 until kids.size - 1) {
                    val a = kids[i]; val b = kids[i + 1]
                    if (idx.realParentIds(a.id) != idx.realParentIds(b.id)) {
                        val l = if (a.id < b.id) HalfSiblingLink(a.id, b.id) else HalfSiblingLink(b.id, a.id)
                        half += l
                    }
                }
            }

            // step links: explicit step links plus those implied by a current / widowed marriage
            val steps = LinkedHashSet<StepLink>()
            data.links.values.filter { it.type == LinkType.STEP }.forEach { steps += StepLink(it.parentId, it.childId) }
            for (m in data.marriages.values) {
                if (m.status != MarriageStatus.CURRENT && m.status != MarriageStatus.WIDOWED) continue
                for ((p, t) in listOf(m.personAId to m.personBId, m.personBId to m.personAId)) {
                    val tKids = idx.realChildIds(t).toSet()
                    for (c in idx.realChildIds(p)) if (c !in tKids && c in data.persons) steps += StepLink(t, c)
                }
            }
            return TreeScene(marriages, groups, half.toList(), steps.toList())
        }
    }
}
