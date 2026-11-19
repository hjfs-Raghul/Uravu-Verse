package com.infinitefamily.domain

import java.util.UUID

enum class ErrorCode {
    NOT_FOUND, INVALID, DUPLICATE, SELF_RELATION, CYCLE, ALREADY_EXISTS, LIMIT, PROTECTED
}

sealed class EditResult {
    data class Ok(val data: FamilyData, val createdId: String? = null) : EditResult()
    data class Fail(val code: ErrorCode, val message: String) : EditResult()

    inline fun then(next: (FamilyData) -> EditResult): EditResult = when (this) {
        is Ok -> when (val r = next(data)) {
            is Ok -> Ok(r.data, r.createdId ?: createdId)
            is Fail -> r
        }
        is Fail -> this
    }
}

/** Where a new child/sibling goes in the birth order. */
sealed class Placement {
    object Youngest : Placement()
    object Eldest : Placement()
    object Unknown : Placement()
    data class ElderThan(val refId: String) : Placement()   // new person is elder than ref
    data class YoungerThan(val refId: String) : Placement() // new person is younger than ref
}

enum class SiblingShape { FULL, ADOPTIVE }

data class DeleteImpact(
    val person: Person,
    val removedMarriages: List<Marriage>,
    val removedLinks: List<ParentLink>,
    /** People who lose a recorded parent (they stay in the tree, attached to their other parent). */
    val childrenLosingParent: List<Person>,
    /** People who lose a recorded parent link where this person was their step/adoptive parent etc. are included above. */
    val becomeDisconnected: List<Person>,
    val blocked: String?,
)

object FamilyEditor {
    fun newId(): String = UUID.randomUUID().toString()

    private fun fail(code: ErrorCode, msg: String) = EditResult.Fail(code, msg)
    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    // ---------------------------------------------------------------- people

    fun validatePerson(p: Person): String? {
        if (p.name.isBlank()) return "Name is required."
        if (p.dob?.isNotBlank() == true && PartialDate.parse(p.dob) == null) return "Date of birth must be YYYY, YYYY-MM or YYYY-MM-DD."
        if (p.deathDate?.isNotBlank() == true && PartialDate.parse(p.deathDate) == null) return "Death date must be YYYY, YYYY-MM or YYYY-MM-DD."
        return null
    }

    /** Soft duplicate check for the UI: same normalised name. */
    fun possibleDuplicates(data: FamilyData, name: String, excludeId: String? = null): List<Person> =
        data.persons.values.filter { it.id != excludeId && norm(it.name) == norm(name) }

    private fun strongDuplicate(data: FamilyData, p: Person): Person? =
        data.persons.values.firstOrNull {
            it.id != p.id && norm(it.name) == norm(p.name) && it.gender == p.gender &&
                ((p.dob != null && it.dob == p.dob) ||
                    (p.dob == null && it.dob == null && p.location.isNotBlank() && norm(it.location) == norm(p.location)))
        }

    fun addPerson(data: FamilyData, person: Person, force: Boolean = false, makeRoot: Boolean = false): EditResult {
        validatePerson(person)?.let { return fail(ErrorCode.INVALID, it) }
        if (data.persons.containsKey(person.id)) return fail(ErrorCode.ALREADY_EXISTS, "A person with this id already exists.")
        if (!force) strongDuplicate(data, person)?.let {
            return fail(ErrorCode.DUPLICATE, "${it.name} already exists with the same details. Select the existing profile instead.")
        }
        val cleaned = person.copy(name = person.name.trim(), dob = person.dob?.takeIf { it.isNotBlank() },
            deathDate = person.deathDate?.takeIf { it.isNotBlank() })
        // The reference person is the yardstick for "older / younger than Raghul".
        val asRoot = if (makeRoot || data.rootId == null) cleaned.copy(relativeAge = RelativeAge.SAME) else cleaned
        val withVariant = if (asRoot.stickerVariant == 0) asRoot.copy(stickerVariant = autoVariant(data, asRoot)) else asRoot
        val root = if (makeRoot || data.rootId == null) withVariant.id else data.rootId
        return EditResult.Ok(data.copy(persons = data.persons + (withVariant.id to withVariant), rootId = root), withVariant.id)
    }

    /** Picks a sticker variant not yet used by the person's relatives where possible. */
    private fun autoVariant(data: FamilyData, p: Person): Int {
        val used = data.persons.values.map { it.stickerVariant }.toSet()
        val seed = p.id.hashCode().let { if (it < 0) -it else it }
        for (i in 0 until 12) {
            val v = ((seed + i) % 12) + 1
            if (v !in used) return v
        }
        return (seed % 12) + 1
    }

    fun updatePerson(data: FamilyData, person: Person): EditResult {
        val old = data.persons[person.id] ?: return fail(ErrorCode.NOT_FOUND, "Person not found.")
        validatePerson(person)?.let { return fail(ErrorCode.INVALID, it) }
        val updated = person.copy(createdAt = old.createdAt, name = person.name.trim())
        return EditResult.Ok(data.copy(persons = data.persons + (person.id to updated)), person.id)
    }

    fun setDeceased(data: FamilyData, id: String, deceased: Boolean, deathDate: String? = null): EditResult {
        val p = data.persons[id] ?: return fail(ErrorCode.NOT_FOUND, "Person not found.")
        // Deceased status is independent: marriages, children and links are untouched.
        return updatePerson(data, p.copy(deceased = deceased, deathDate = if (deceased) deathDate else null))
    }

    fun setRoot(data: FamilyData, id: String): EditResult {
        if (id !in data.persons) return fail(ErrorCode.NOT_FOUND, "Person not found.")
        return EditResult.Ok(data.copy(rootId = id))
    }

    // ------------------------------------------------------------ parentage

    fun addParentLink(data: FamilyData, parentId: String, childId: String, type: LinkType = LinkType.BIOLOGICAL): EditResult {
        if (parentId == childId) return fail(ErrorCode.SELF_RELATION, "A person cannot be their own parent.")
        if (parentId !in data.persons || childId !in data.persons) return fail(ErrorCode.NOT_FOUND, "Person not found.")
        if (data.links.values.any { it.parentId == parentId && it.childId == childId })
            return fail(ErrorCode.ALREADY_EXISTS, "These two people are already linked as parent and child.")
        if (parentId in data.index.descendants(childId))
            return fail(ErrorCode.CYCLE, "${data.persons[parentId]!!.name} is a descendant of ${data.persons[childId]!!.name}; this would create a loop in the family tree.")
        val existing = data.index.parentLinks(childId)
        if (type == LinkType.BIOLOGICAL && existing.count { it.type == LinkType.BIOLOGICAL } >= 2)
            return fail(ErrorCode.LIMIT, "${data.persons[childId]!!.name} already has two biological parents.")
        if (type == LinkType.ADOPTIVE && existing.count { it.type == LinkType.ADOPTIVE } >= 4)
            return fail(ErrorCode.LIMIT, "Too many adoptive parents recorded.")
        val link = ParentLink(newId(), parentId, childId, type)
        return EditResult.Ok(data.copy(links = data.links + (link.id to link)), link.id)
    }

    fun removeParentLink(data: FamilyData, linkId: String): EditResult {
        if (linkId !in data.links) return fail(ErrorCode.NOT_FOUND, "Link not found.")
        return EditResult.Ok(data.copy(links = data.links - linkId))
    }

    fun changeLinkType(data: FamilyData, linkId: String, type: LinkType): EditResult {
        val l = data.links[linkId] ?: return fail(ErrorCode.NOT_FOUND, "Link not found.")
        val others = data.index.parentLinks(l.childId).filter { it.id != linkId }
        if (type == LinkType.BIOLOGICAL && others.count { it.type == LinkType.BIOLOGICAL } >= 2)
            return fail(ErrorCode.LIMIT, "Already has two biological parents.")
        return EditResult.Ok(data.copy(links = data.links + (linkId to l.copy(type = type))))
    }

    /** Add a child to [parentId], optionally with a second parent, then place it in the birth order. */
    fun addChild(
        data: FamilyData, parentId: String, childId: String,
        otherParentId: String? = null, type: LinkType = LinkType.BIOLOGICAL,
        placement: Placement = Placement.Youngest,
    ): EditResult {
        var r: EditResult = addParentLink(data, parentId, childId, type)
        if (otherParentId != null && otherParentId != parentId) r = r.then { addParentLink(it, otherParentId, childId, type) }
        return r.then { placeInBirthOrder(it, childId, placement) }
    }

    /** Create parents for a person who has none, so siblings can be derived. */
    fun addParents(data: FamilyData, childId: String, father: Person?, mother: Person?): EditResult {
        var r: EditResult = EditResult.Ok(data)
        var fId: String? = null; var mId: String? = null
        if (father != null) r = r.then { addPerson(it, father, force = true) }.then { addParentLink(it, father.id, childId) }.also { fId = father.id }
        if (mother != null) r = r.then { addPerson(it, mother, force = true) }.then { addParentLink(it, mother.id, childId) }.also { mId = mother.id }
        if (fId != null && mId != null) r = r.then { addMarriage(it, fId!!, mId!!, MarriageStatus.CURRENT) }
        return r
    }

    fun addSibling(
        data: FamilyData, existingId: String, siblingId: String,
        shape: SiblingShape = SiblingShape.FULL, placement: Placement = Placement.Youngest,
    ): EditResult {
        if (existingId == siblingId) return fail(ErrorCode.SELF_RELATION, "A person cannot be their own sibling.")
        val links = data.index.parentLinks(existingId).filter { it.type != LinkType.STEP }
        if (links.isEmpty()) return fail(ErrorCode.INVALID, "No parents are recorded yet. Add parents first so siblings can be derived.")
        var r: EditResult = EditResult.Ok(data)
        for (l in links) {
            val t = if (shape == SiblingShape.ADOPTIVE) LinkType.ADOPTIVE else l.type
            r = r.then { addParentLink(it, l.parentId, siblingId, t) }
        }
        return r.then { placeInBirthOrder(it, siblingId, placement) }
    }

    // ------------------------------------------------------------ birth order

    /** All siblings (full and half) plus the person, elder first. Unknown pairs keep creation order. */
    fun orderedSiblings(data: FamilyData, id: String): List<Person> {
        val ids = data.index.siblingIds(id) + id
        val ps = ids.mapNotNull { data.persons[it] }.sortedBy { it.createdAt }
        return ps.sortedWith { a, b ->
            when (Ages.compareSiblings(a, b)) {
                AgeOrder.ELDER -> 1       // b is elder than a  -> a after b
                AgeOrder.YOUNGER -> -1
                AgeOrder.UNKNOWN -> 0
            }
        }
    }

    private fun assign(data: FamilyData, ordered: List<String>): FamilyData {
        var d = data
        ordered.forEachIndexed { i, pid ->
            val p = d.persons[pid] ?: return@forEachIndexed
            d = d.copy(persons = d.persons + (pid to p.copy(birthOrder = (i + 1).toDouble())))
        }
        return d
    }

    fun placeInBirthOrder(data: FamilyData, id: String, placement: Placement): EditResult {
        if (placement == Placement.Unknown) return EditResult.Ok(data)
        val others = orderedSiblings(data, id).filter { it.id != id }.map { it.id }
        if (others.isEmpty()) return EditResult.Ok(data)
        val list = others.toMutableList()
        when (placement) {
            Placement.Youngest -> list.add(id)
            Placement.Eldest -> list.add(0, id)
            is Placement.ElderThan -> list.add(list.indexOf(placement.refId).coerceAtLeast(0), id)
            is Placement.YoungerThan -> list.add((list.indexOf(placement.refId) + 1).coerceIn(0, list.size), id)
            Placement.Unknown -> {}
        }
        return EditResult.Ok(assign(data, list))
    }

    /**
     * Drag-and-drop result. The members keep using the same set of order "slots", so their position
     * relative to siblings that are not part of the list is preserved.
     */
    fun reorderSiblings(data: FamilyData, newOrder: List<String>): EditResult {
        if (newOrder.any { it !in data.persons }) return fail(ErrorCode.NOT_FOUND, "Person not found.")
        val slots = newOrder.mapNotNull { data.persons[it]?.birthOrder }.sorted().toMutableList()
        var next = (slots.maxOrNull() ?: 0.0)
        while (slots.size < newOrder.size) { next += 1.0; slots.add(next) }
        var d = data
        newOrder.forEachIndexed { i, pid ->
            val p = d.persons[pid]!!
            d = d.copy(persons = d.persons + (pid to p.copy(birthOrder = slots[i])))
        }
        return EditResult.Ok(d)
    }

    // ------------------------------------------------------------ marriages

    fun addMarriage(
        data: FamilyData, aId: String, bId: String, status: MarriageStatus = MarriageStatus.CURRENT,
        startDate: String? = null, endDate: String? = null, note: String = "",
    ): EditResult {
        if (aId == bId) return fail(ErrorCode.SELF_RELATION, "A person cannot marry themselves.")
        if (aId !in data.persons || bId !in data.persons) return fail(ErrorCode.NOT_FOUND, "Person not found.")
        if (data.links.values.any { it.type != LinkType.STEP && ((it.parentId == aId && it.childId == bId) || (it.parentId == bId && it.childId == aId)) })
            return fail(ErrorCode.INVALID, "A parent and child cannot be recorded as married.")
        if (data.marriages.values.any { it.involves(aId) && it.involves(bId) && it.status == MarriageStatus.CURRENT })
            return fail(ErrorCode.ALREADY_EXISTS, "These two people already have a current marriage.")
        for (d in listOf(startDate, endDate)) if (!d.isNullOrBlank() && PartialDate.parse(d) == null)
            return fail(ErrorCode.INVALID, "Marriage dates must be YYYY, YYYY-MM or YYYY-MM-DD.")
        val order = (data.index.marriages(aId) + data.index.marriages(bId)).maxOfOrNull { it.order }?.plus(1) ?: 0
        val m = Marriage(newId(), aId, bId, status, startDate?.takeIf { it.isNotBlank() }, endDate?.takeIf { it.isNotBlank() }, order, note)
        return EditResult.Ok(data.copy(marriages = data.marriages + (m.id to m)), m.id)
    }

    /** Changing status never touches parent-child links. */
    fun setMarriageStatus(data: FamilyData, marriageId: String, status: MarriageStatus, endDate: String? = null): EditResult {
        val m = data.marriages[marriageId] ?: return fail(ErrorCode.NOT_FOUND, "Marriage not found.")
        val end = if (status == MarriageStatus.CURRENT) null else (endDate ?: m.endDate)
        return EditResult.Ok(data.copy(marriages = data.marriages + (marriageId to m.copy(status = status, endDate = end))))
    }

    fun updateMarriage(data: FamilyData, m: Marriage): EditResult {
        if (m.id !in data.marriages) return fail(ErrorCode.NOT_FOUND, "Marriage not found.")
        return EditResult.Ok(data.copy(marriages = data.marriages + (m.id to m)))
    }

    fun removeMarriage(data: FamilyData, marriageId: String): EditResult {
        if (marriageId !in data.marriages) return fail(ErrorCode.NOT_FOUND, "Marriage not found.")
        return EditResult.Ok(data.copy(marriages = data.marriages - marriageId))
    }

    // ------------------------------------------------------------- deletion

    private fun reachableFromRoot(data: FamilyData): Set<String> {
        val root = data.rootId ?: return emptySet()
        if (root !in data.persons) return emptySet()
        val adj = HashMap<String, MutableList<String>>()
        fun edge(a: String, b: String) { adj.getOrPut(a) { mutableListOf() }.add(b); adj.getOrPut(b) { mutableListOf() }.add(a) }
        data.links.values.forEach { edge(it.parentId, it.childId) }
        data.marriages.values.forEach { edge(it.personAId, it.personBId) }
        val seen = HashSet<String>().apply { add(root) }
        val q = ArrayDeque<String>().apply { add(root) }
        while (q.isNotEmpty()) for (n in adj[q.removeFirst()].orEmpty()) if (seen.add(n)) q.add(n)
        return seen
    }

    fun deleteImpact(data: FamilyData, id: String): DeleteImpact {
        val p = data.persons[id] ?: error("Person not found")
        val links = data.links.values.filter { it.parentId == id || it.childId == id }
        val marriages = data.marriages.values.filter { it.involves(id) }
        val children = links.filter { it.parentId == id }.mapNotNull { data.persons[it.childId] }
        val after = removeInternal(data, id)
        val reach = reachableFromRoot(after)
        val orphans = after.persons.values.filter { it.id !in reach }
        val blocked = if (id == data.rootId) "This is the reference person. Choose another reference person before deleting." else null
        return DeleteImpact(p, marriages, links, children, orphans, blocked)
    }

    private fun removeInternal(data: FamilyData, id: String) = data.copy(
        persons = data.persons - id,
        links = data.links.filterValues { it.parentId != id && it.childId != id },
        marriages = data.marriages.filterValues { !it.involves(id) },
    )

    /** Removes only the person and the relationship records that point at them. Nobody else is deleted. */
    fun deletePerson(data: FamilyData, id: String): EditResult {
        if (id !in data.persons) return fail(ErrorCode.NOT_FOUND, "Person not found.")
        if (id == data.rootId) return fail(ErrorCode.PROTECTED, "The reference person cannot be deleted.")
        return EditResult.Ok(removeInternal(data, id))
    }

    // ------------------------------------------------------------ integrity

    /** Full structural validation used after restore and in tests. Returns a list of problems. */
    fun validate(data: FamilyData): List<String> {
        val out = mutableListOf<String>()
        data.links.values.forEach {
            if (it.parentId !in data.persons || it.childId !in data.persons) out += "Link ${it.id} points to a missing person."
            if (it.parentId == it.childId) out += "Person ${it.parentId} is their own parent."
        }
        data.marriages.values.forEach {
            if (it.personAId !in data.persons || it.personBId !in data.persons) out += "Marriage ${it.id} points to a missing person."
            if (it.personAId == it.personBId) out += "Person ${it.personAId} is married to themselves."
        }
        if (out.isEmpty()) data.persons.keys.forEach { if (it in data.index.descendants(it)) out += "Cycle in ancestry at $it." }
        if (data.rootId != null && data.rootId !in data.persons) out += "Reference person is missing."
        return out
    }
}
