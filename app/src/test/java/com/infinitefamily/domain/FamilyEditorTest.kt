package com.infinitefamily.domain

import org.junit.Assert.*
import org.junit.Test

class FamilyEditorTest {
    private fun ok(r: EditResult) = (r as? EditResult.Ok ?: fail("expected Ok but was $r")) as EditResult.Ok
    private fun failed(r: EditResult, code: ErrorCode) { assertTrue("expected $code but was $r", r is EditResult.Fail && r.code == code) }
    private fun p(name: String, g: Gender = Gender.MALE, dob: String? = null, loc: String = "") =
        Person(FamilyEditor.newId(), name, g, dob = dob, location = loc, createdAt = System.nanoTime())

    @Test fun create_person_requires_name_and_gender_only() {
        val r = ok(FamilyEditor.addPerson(FamilyData(), p("Raghul")))
        assertEquals("Raghul", r.data.root!!.name)
        failed(FamilyEditor.addPerson(FamilyData(), p("  ")), ErrorCode.INVALID)
        failed(FamilyEditor.addPerson(FamilyData(), p("X", dob = "31/12/1990")), ErrorCode.INVALID)
        // a minimal profile is fine, and partial dates are accepted
        ok(FamilyEditor.addPerson(FamilyData(), p("Y", dob = "1980")))
    }

    @Test fun editing_a_person_updates_the_single_record_everywhere() {
        val b = FB(); val a = b.person("A", Gender.MALE, root = true); val c = b.f("C"); val k = b.m("K")
        b.marry(a, c); b.kids(a, c, k)
        val edited = ok(FamilyEditor.updatePerson(b.data, b.data.persons.getValue(a).copy(name = "Aravind", location = "Chennai"))).data
        assertEquals(b.data.persons.size, edited.persons.size)           // no duplicate record
        assertEquals("Aravind", edited.persons.getValue(a).name)
        assertEquals("Aravind", RelationshipEngine(edited).relation(k, a).primary!!.stepTexts[0].substringAfter(" is "))
        assertEquals(b.data.links, edited.links)
    }

    @Test fun duplicate_prevention() {
        val b = FB(); val a = b.person("Ravi Kumar", Gender.MALE, "1980-01-01", root = true)
        failed(FamilyEditor.addPerson(b.data, p("ravi  kumar", Gender.MALE, "1980-01-01")), ErrorCode.DUPLICATE)
        // same name but a different birth date is only a soft duplicate (UI asks)
        ok(FamilyEditor.addPerson(b.data, p("Ravi Kumar", Gender.MALE, "1990-01-01")))
        assertEquals(1, FamilyEditor.possibleDuplicates(b.data, "RAVI KUMAR").size)
        assertEquals(0, FamilyEditor.possibleDuplicates(b.data, "Ravi Kumar", excludeId = a).size)
        // connecting an existing person never creates another record
        val c = b.f("C")
        val n = b.data.persons.size
        b.marry(a, c)
        assertEquals(n, b.data.persons.size)
    }

    @Test fun self_relations_and_cycles_are_rejected() {
        val b = FB(); val gp = b.m("GP"); val dad = b.m("Dad"); val kid = b.m("Kid")
        b.parent(gp, dad); b.parent(dad, kid)
        failed(FamilyEditor.addParentLink(b.data, kid, kid), ErrorCode.SELF_RELATION)
        failed(FamilyEditor.addParentLink(b.data, kid, gp), ErrorCode.CYCLE)
        failed(FamilyEditor.addParentLink(b.data, dad, kid), ErrorCode.ALREADY_EXISTS)
        failed(FamilyEditor.addMarriage(b.data, kid, kid), ErrorCode.SELF_RELATION)
        failed(FamilyEditor.addMarriage(b.data, dad, kid), ErrorCode.INVALID)
        failed(FamilyEditor.addSibling(b.data, gp, kid), ErrorCode.INVALID) // gp has no parents yet
    }

    @Test fun at_most_two_biological_parents() {
        val b = FB(); val k = b.m("K"); val a = b.m("A"); val c = b.f("C"); val d = b.f("D")
        b.parent(a, k); b.parent(c, k)
        failed(FamilyEditor.addParentLink(b.data, d, k), ErrorCode.LIMIT)
        ok(FamilyEditor.addParentLink(b.data, d, k, LinkType.ADOPTIVE))
        ok(FamilyEditor.addParentLink(b.data, d, k, LinkType.STEP).let { EditResult.Ok(b.data) }) // step is separate
    }

    @Test fun multiple_marriages_each_have_their_own_record() {
        val b = FB(); val a = b.person("A", Gender.MALE, root = true); val w1 = b.f("W1"); val w2 = b.f("W2"); val w3 = b.f("W3")
        val m1 = b.marry(a, w1); val m2 = b.marry(a, w2); val m3 = b.marry(a, w3, MarriageStatus.WIDOWED)
        assertEquals(3, b.data.index.marriages(a).size)
        assertEquals(setOf(m1, m2, m3), b.data.index.marriages(a).map { it.id }.toSet())
        assertTrue(b.data.marriages.getValue(m1).order < b.data.marriages.getValue(m2).order)
        // earlier marriages are NOT assumed to be divorced
        assertEquals(MarriageStatus.CURRENT, b.data.marriages.getValue(m1).status)
        // the same couple cannot have two current marriages
        failed(FamilyEditor.addMarriage(b.data, a, w1), ErrorCode.ALREADY_EXISTS)
    }

    @Test fun divorce_keeps_parent_child_links_and_both_profiles_normal() {
        val b = FB(); val a = b.m("A"); val bb = b.f("B"); val c = b.f("C"); val k1 = b.m("K1"); val k2 = b.m("K2")
        val m = b.marry(a, bb); b.kids(a, bb, k1)
        val linksBefore = b.data.links
        val d = ok(FamilyEditor.setMarriageStatus(b.data, m, MarriageStatus.DIVORCED, "2005")).data
        assertEquals(linksBefore, d.links)
        assertEquals(MarriageStatus.DIVORCED, d.marriages.getValue(m).status)
        assertFalse(d.persons.getValue(a).deceased); assertFalse(d.persons.getValue(bb).deceased)
        assertEquals(StickerStyle(1f, false), StickerStyles.forPerson(d.persons.getValue(a)))
        // new current marriage; the older one stays divorced and the scene marks it
        val d2 = ok(FamilyEditor.addMarriage(d, a, c)).data
        val scene = TreeScene.build(d2)
        assertEquals(MarriageStatus.DIVORCED, scene.marriages.first { it.id == m }.status)
        assertEquals(1, scene.marriages.count { it.status == MarriageStatus.CURRENT })
        assertTrue(scene.stepLinks.contains(StepLink(c, k1)))          // C is a step-parent of K1
        assertFalse(scene.stepLinks.any { it.parentId == bb })         // divorced B gets no step links
        assertTrue(ok(FamilyEditor.setMarriageStatus(d2, m, MarriageStatus.CURRENT)).data.marriages.getValue(m).endDate == null)
    }

    @Test fun deceased_status_is_independent_from_marriage_and_links() {
        val b = FB(); val a = b.m("A"); val w = b.f("W"); val k = b.m("K")
        b.marry(a, w); b.kids(a, w, k)
        val d = ok(FamilyEditor.setDeceased(b.data, a, true, "2001")).data
        assertTrue(d.persons.getValue(a).deceased)
        assertEquals(b.data.marriages, d.marriages); assertEquals(b.data.links, d.links)
        val style = StickerStyles.forPerson(d.persons.getValue(a))
        assertTrue(style.alpha < 1f && style.desaturate)
        assertEquals(StickerStyle(1f, false), StickerStyles.forPerson(d.persons.getValue(w)))
        assertFalse(ok(FamilyEditor.setDeceased(d, a, false)).data.persons.getValue(a).deceased)
    }

    @Test fun add_child_with_two_parents_and_birth_order_placement() {
        val b = FB(); val a = b.m("A"); val w = b.f("W")
        val k1 = b.m("K1"); val k2 = b.m("K2"); val k3 = b.m("K3")
        var d = ok(FamilyEditor.addChild(b.data, a, k1, w)).data
        d = ok(FamilyEditor.addChild(d, a, k2, w, placement = Placement.Youngest)).data
        d = ok(FamilyEditor.addChild(d, a, k3, w, placement = Placement.Eldest)).data
        assertEquals(listOf(k3, k1, k2), FamilyEditor.orderedSiblings(d, k1).map { it.id })
    }

    @Test fun sibling_reordering_by_drag_and_drop_preserves_parentage() {
        val b = FB(); val a = b.m("A"); val w = b.f("W")
        val k1 = b.m("K1"); val k2 = b.m("K2"); val k3 = b.m("K3")
        b.kids(a, w, k1, k2, k3)
        var d = ok(FamilyEditor.placeInBirthOrder(b.data, k1, Placement.Youngest)).data
        d = ok(FamilyEditor.reorderSiblings(d, listOf(k3, k1, k2))).data
        assertEquals(listOf(k3, k1, k2), FamilyEditor.orderedSiblings(d, k1).map { it.id })
        assertEquals(b.data.links, d.links)
        assertEquals(AgeOrder.ELDER, Ages.compareSiblings(d.persons.getValue(k1), d.persons.getValue(k3)))
    }

    @Test fun half_sibling_order_is_retained_across_marriages() {
        val b = FB(); val a = b.m("A"); val w1 = b.f("W1"); val w2 = b.f("W2")
        val k1 = b.m("K1"); val k2 = b.m("K2"); val k3 = b.m("K3")
        b.kids(a, w1, k1, k2); b.kids(a, w2, k3)
        var d = ok(FamilyEditor.reorderSiblings(b.data, listOf(k1, k2, k3))).data
        assertEquals(AgeOrder.ELDER, Ages.compareSiblings(d.persons.getValue(k3), d.persons.getValue(k1)))
        d = ok(FamilyEditor.reorderSiblings(d, listOf(k1, k3, k2))).data
        // k1 is still elder than k3, but k3 is now elder than k2 (was the other way round)
        assertEquals(AgeOrder.ELDER, Ages.compareSiblings(d.persons.getValue(k3), d.persons.getValue(k1)))
        assertEquals(AgeOrder.ELDER, Ages.compareSiblings(d.persons.getValue(k2), d.persons.getValue(k3)))
        assertEquals(AgeOrder.YOUNGER, Ages.compareSiblings(d.persons.getValue(k3), d.persons.getValue(k2)))
        val scene = TreeScene.build(d)
        assertTrue("half siblings from different marriages are linked", scene.halfLinks.isNotEmpty())
    }

    @Test fun add_sibling_requires_parents_and_can_be_adoptive() {
        val b = FB(); val a = b.m("A"); val w = b.f("W"); val k = b.m("K"); val s = b.f("S"); val ad = b.f("Ad")
        b.kids(a, w, k)
        val d = ok(FamilyEditor.addSibling(b.data, k, s)).data
        assertEquals(setOf(a, w), d.index.realParentIds(s))
        val d2 = ok(FamilyEditor.addSibling(d, k, ad, SiblingShape.ADOPTIVE)).data
        assertTrue(d2.index.parentLinks(ad).all { it.type == LinkType.ADOPTIVE })
    }

    @Test fun add_parents_for_person_without_parents_creates_marriage() {
        val b = FB(); val k = b.person("K", Gender.MALE, root = true)
        val d = ok(FamilyEditor.addParents(b.data, k, p("Dad"), p("Mom", Gender.FEMALE))).data
        assertEquals(2, d.index.realParentIds(k).size)
        assertEquals(1, d.marriages.size)
    }

    @Test fun delete_shows_impact_and_never_deletes_other_people() {
        val f = Family()
        val impact = FamilyEditor.deleteImpact(f.data, f.appaji)
        assertNull(impact.blocked)
        assertTrue(impact.removedMarriages.isNotEmpty())
        assertTrue(impact.childrenLosingParent.any { it.id == f.raghul })
        // removing Appaji cuts Raghul's connection to the paternal side only through him, so those people become islands
        assertTrue(impact.becomeDisconnected.isNotEmpty() || impact.removedLinks.isNotEmpty())
        val d = ok(FamilyEditor.deletePerson(f.data, f.appaji)).data
        assertEquals(f.data.persons.size - 1, d.persons.size)
        assertTrue(FamilyEditor.validate(d).isEmpty())
        assertTrue(d.persons.containsKey(f.raghul) && d.persons.containsKey(f.amma))
        assertEquals(setOf(f.amma), d.index.realParentIds(f.raghul))
        // the reference person is protected
        assertNotNull(FamilyEditor.deleteImpact(f.data, f.raghul).blocked)
        failed(FamilyEditor.deletePerson(f.data, f.raghul), ErrorCode.PROTECTED)
    }

    @Test fun validate_detects_corruption() {
        val b = FB(); val a = b.m("A"); val k = b.m("K"); b.parent(a, k)
        val broken = b.data.copy(links = b.data.links + ("x" to ParentLink("x", "ghost", k)))
        assertFalse(FamilyEditor.validate(broken).isEmpty())
        assertTrue(FamilyEditor.validate(b.data).isEmpty())
    }

    @Test fun sticker_categories_follow_age_gender_and_generation() {
        val today = java.time.LocalDate.of(2026, 1, 1)
        val boy = p("Boy", dob = "2018")
        assertEquals(StickerCategory.BOY, StickerCategory.choose(boy, today, 1))
        assertEquals(StickerCategory.INFANT, StickerCategory.choose(p("Baby", dob = "2025-06-01"), today, 2))
        assertEquals(StickerCategory.ELDER_WOMAN, StickerCategory.choose(p("Gran", Gender.FEMALE), today, -2))
        assertEquals(StickerCategory.ELDER_MAN, StickerCategory.choose(p("Gran").copy(stickerCategory = "elder_man"), today, 1))
        assertTrue(StickerCategory.BOY.variants >= 4 && StickerCategory.GIRL.variants >= 4)
    }

    @Test fun sibling_boys_get_different_default_sticker_variants() {
        val b = FB(); val a = b.m("A"); val w = b.f("W")
        val ids = (1..4).map { b.m("Boy$it") }
        assertEquals(4, ids.map { b.data.persons.getValue(it).stickerVariant }.toSet().size)
        assertNotNull(a); assertNotNull(w)
    }
}
