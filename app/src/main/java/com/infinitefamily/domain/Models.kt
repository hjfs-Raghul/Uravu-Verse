package com.infinitefamily.domain

enum class Gender { MALE, FEMALE, OTHER }

/** Age of a person relative to the reference person (Raghul). Only a fallback when DOB is unknown. */
enum class RelativeAge { UNKNOWN, OLDER, SAME, YOUNGER }

enum class LinkType { BIOLOGICAL, ADOPTIVE, STEP }

/** Marriage status is independent of whether either person is deceased. */
enum class MarriageStatus { CURRENT, PREVIOUS, DIVORCED, WIDOWED }

enum class Language { EN, TA, ML }

/** One profile per real person. Position on the canvas is NEVER stored here: it is generated. */
data class Person(
    val id: String,
    val name: String,
    val gender: Gender,
    val dob: String? = null,          // "YYYY", "YYYY-MM" or "YYYY-MM-DD"
    val deceased: Boolean = false,
    val deathDate: String? = null,
    val occupation: String = "",
    val location: String = "",        // city level
    val notes: String = "",
    val photoFile: String? = null,    // file name inside the app photo directory
    val relativeAge: RelativeAge = RelativeAge.UNKNOWN,
    /** Position among all siblings (full + half). null = unknown. Smaller = elder. */
    val birthOrder: Double? = null,
    val stickerVariant: Int = 0,
    val stickerCategory: String? = null, // manual override of the default sticker category
    val isSample: Boolean = false,
    val createdAt: Long = 0L,
)

/** parent -> child. Biological, adoptive or explicit step link. Marriage never rewrites these. */
data class ParentLink(
    val id: String,
    val parentId: String,
    val childId: String,
    val type: LinkType = LinkType.BIOLOGICAL,
)

/** Each marriage is its own record, so a person can have any number of them. */
data class Marriage(
    val id: String,
    val personAId: String,
    val personBId: String,
    val status: MarriageStatus = MarriageStatus.CURRENT,
    val startDate: String? = null,
    val endDate: String? = null,
    /** Chronological position among the marriages of the people involved. */
    val order: Int = 0,
    val note: String = "",
) {
    fun other(id: String): String = if (personAId == id) personBId else personAId
    fun involves(id: String) = personAId == id || personBId == id
}

data class TermOverride(val code: String, val language: Language, val text: String)

data class FamilyData(
    val persons: Map<String, Person> = emptyMap(),
    val links: Map<String, ParentLink> = emptyMap(),
    val marriages: Map<String, Marriage> = emptyMap(),
    val rootId: String? = null,
) {
    val index: FamilyIndex by lazy(LazyThreadSafetyMode.NONE) { FamilyIndex(this) }
    fun person(id: String): Person? = persons[id]
    val root: Person? get() = rootId?.let { persons[it] }
}

/** Fast lookups derived from [FamilyData]. Rebuilt whenever the data changes. */
class FamilyIndex(val data: FamilyData) {
    val linksByChild: Map<String, List<ParentLink>> = data.links.values.groupBy { it.childId }
    val linksByParent: Map<String, List<ParentLink>> = data.links.values.groupBy { it.parentId }
    val marriagesOf: Map<String, List<Marriage>> = buildMap<String, MutableList<Marriage>> {
        data.marriages.values.sortedBy { it.order }.forEach { m ->
            getOrPut(m.personAId) { mutableListOf() }.add(m)
            getOrPut(m.personBId) { mutableListOf() }.add(m)
        }
    }

    fun parentLinks(child: String): List<ParentLink> = linksByChild[child].orEmpty()
    fun childLinks(parent: String): List<ParentLink> = linksByParent[parent].orEmpty()
    fun marriages(person: String): List<Marriage> = marriagesOf[person].orEmpty()

    /** Biological + adoptive parents (the "legal" parents). Step links excluded. */
    fun realParentIds(child: String): Set<String> =
        parentLinks(child).filter { it.type != LinkType.STEP }.map { it.parentId }.toSet()

    fun realChildIds(parent: String): List<String> =
        childLinks(parent).filter { it.type != LinkType.STEP }.map { it.childId }

    /** Everyone reachable downwards through any parent link. */
    fun descendants(id: String): Set<String> {
        val out = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            for (l in childLinks(p)) if (out.add(l.childId)) stack.add(l.childId)
        }
        return out
    }

    /** People who share at least one real (non-step) parent with [id], excluding [id]. */
    fun siblingIds(id: String): Set<String> =
        realParentIds(id).flatMap { realChildIds(it) }.filter { it != id }.toSet()

    fun sharedParents(a: String, b: String): Set<String> = realParentIds(a) intersect realParentIds(b)
}
