package com.infinitefamily.domain

import org.junit.Assert.*
import org.junit.Test

class RelationshipEngineTest {
    private val fam = Family()

    @Test fun father_and_mother_terms_in_three_languages() {
        val r = fam.engine.relation(fam.raghul, fam.appaji).primary!!
        assertEquals(listOf("F"), r.readings.map { it.code })
        val t = r.readings[0].terms
        assertEquals("Father", t.relationEn)
        assertEquals("அப்பா", t.ta[0].text)
        assertEquals("അച്ഛൻ", t.ml[0].text)
        assertEquals("Amma", fam.engine.relation(fam.raghul, fam.amma).primary!!.readings[0].terms.ta[0].roman)
    }

    @Test fun paternal_uncles_depend_on_age_order_with_father() {
        val elder = fam.engine.relation(fam.raghul, fam.periyappa).primary!!
        assertEquals(listOf("FeB"), elder.readings.map { it.code })
        assertEquals("Periyappa", elder.readings[0].terms.ta[0].roman)
        val younger = fam.engine.relation(fam.raghul, fam.chithappa).primary!!
        assertEquals("Chithappa", younger.readings[0].terms.ta[0].roman)
        assertEquals("ചെറിയച്ഛൻ", younger.readings[0].terms.ml[0].text)
    }

    @Test fun paternal_versus_maternal_lineage() {
        assertEquals("Paternal grandfather", fam.engine.relation(fam.raghul, fam.pgf).primary!!.readings[0].terms.relationEn)
        assertEquals("Maternal grandmother", fam.engine.relation(fam.raghul, fam.mgm).primary!!.readings[0].terms.relationEn)
        val athai = fam.engine.relation(fam.raghul, fam.athai).primary!!.readings[0].terms
        assertEquals("Father's elder sister", athai.relationEn)
        assertEquals("Aunt", athai.addressEn)
        assertEquals("Athai", athai.ta[0].roman)
        val mama = fam.engine.relation(fam.raghul, fam.mama).primary!!.readings[0]
        assertEquals("Mother's elder brother", mama.terms.relationEn)
        assertEquals("Mama", mama.terms.ta[0].roman)
        assertEquals("Ammavan", mama.terms.ml[0].roman)
        assertEquals("Mother's younger sister", fam.engine.relation(fam.raghul, fam.chithi).primary!!.readings[0].terms.relationEn)
    }

    @Test fun reverse_direction_is_computed_independently() {
        val r = fam.engine.pair(fam.raghul, fam.periyappa)
        assertEquals("Father's elder brother", r.aToB.primary!!.summary)
        // Periyappa -> Raghul : younger brother's son
        assertEquals("Younger brother's son", r.bToA.primary!!.summary)
        assertEquals("Thambi magan", r.bToA.primary!!.readings[0].terms.ta[0].roman)
    }

    @Test fun marriage_relationships() {
        assertEquals(listOf("W"), fam.code(fam.raghul, fam.priya))
        assertEquals(listOf("H"), fam.code(fam.priya, fam.raghul))
        assertEquals("Wife", fam.engine.relation(fam.raghul, fam.priya).primary!!.readings[0].terms.relationEn)
    }

    @Test fun in_laws_through_marriage() {
        // Raghul's wife's father is not recorded; Appaji's wife is Raghul's mother (no detour through marriage)
        val r = fam.engine.relation(fam.raghul, fam.amma).primary!!
        assertEquals(1, r.steps.size)
        // Priya's relation to Appaji: husband's father
        assertEquals(listOf("HF"), fam.code(fam.priya, fam.appaji))
        assertEquals("Mamanar", fam.engine.relation(fam.priya, fam.appaji).primary!!.readings[0].terms.ta[0].roman)
    }

    @Test fun parallel_and_cross_cousins() {
        val parallel = fam.engine.relation(fam.raghul, fam.anna).primary!!
        assertEquals(listOf("FeBS~e"), parallel.readings.map { it.code })
        assertEquals("Annan", parallel.readings[0].terms.ta[0].roman)
        val cross = fam.engine.relation(fam.raghul, fam.crossCousin).primary!!
        assertTrue(cross.readings[0].terms.relationEn.contains("sister's son"))
        assertEquals("Athai magan", cross.readings[0].terms.ta[0].roman)
    }

    @Test fun unknown_age_order_shows_both_outcomes_instead_of_guessing() {
        val b = FB()
        val r = b.person("Raghul", Gender.MALE, root = true)
        val dad = b.m("Dad"); val uncle = b.m("Uncle"); val gp = b.f("Gran")
        b.kids(gp, null, dad, uncle); b.parent(dad, r)
        val res = RelationshipEngine(b.data).relation(r, uncle).primary!!
        assertTrue(res.ambiguous)
        assertEquals(setOf("Periyappa", "Chithappa"), res.readings.map { it.terms.ta[0].roman }.toSet())
        assertNotNull(res.readings.first().condition)
    }

    @Test fun explicit_sibling_order_resolves_the_ambiguity() {
        val b = FB()
        val r = b.person("Raghul", Gender.MALE, root = true)
        val dad = b.m("Dad"); val uncle = b.m("Uncle"); val gp = b.f("Gran")
        b.kids(gp, null, dad, uncle); b.parent(dad, r)
        val order = FamilyEditor.reorderSiblings(b.data, listOf(uncle, dad)) as EditResult.Ok
        val res = RelationshipEngine(order.data).relation(r, uncle).primary!!
        assertFalse(res.ambiguous)
        assertEquals("FeB", res.readings[0].code)
    }

    @Test fun relative_age_hint_is_used_when_dob_is_missing() {
        val b = FB()
        val r = b.person("Raghul", Gender.MALE, root = true)
        val c1 = b.person("Cousin", Gender.MALE, rel = RelativeAge.OLDER)
        val dad = b.m("Dad"); val unc = b.m("Unc", "1960"); val gp = b.f("Gran")
        b.kids(gp, null, dad, unc); b.parent(dad, r); b.parent(unc, c1)
        // dad has no dob/order so the uncle's order is unknown, but cousin age vs Raghul is known
        val res = RelationshipEngine(b.data).relation(r, c1).primary!!
        assertTrue(res.readings.all { it.code.endsWith("~e") })
    }

    @Test fun half_siblings_after_divorce_and_remarriage() {
        val b = FB()
        val a = b.m("A", "1960"); val bb = b.f("B", "1962"); val c = b.f("C", "1970")
        val kid1 = b.m("Kid1", "1988"); val kid2 = b.f("Kid2", "1996")
        val m1 = b.marry(a, bb); b.kids(a, bb, kid1)
        // divorce, then A marries C and has Kid2
        b.data = (FamilyEditor.setMarriageStatus(b.data, m1, MarriageStatus.DIVORCED) as EditResult.Ok).data
        b.marry(a, c); b.kids(a, c, kid2)
        val e = RelationshipEngine(b.data)

        // children keep their original parents; divorce never rewrote links
        assertEquals(setOf(a, bb), b.data.index.realParentIds(kid1))
        assertEquals(setOf(a, c), b.data.index.realParentIds(kid2))
        // half siblings, elder / younger known from dob
        val k = e.relation(kid2, kid1).primary!!
        assertEquals(listOf("heB"), k.readings.map { it.code })
        assertTrue(k.readings[0].terms.relationEn.contains("half"))
        // C is a step-mother of Kid1, B is not related to Kid2 as a step-parent
        assertEquals(listOf("sM"), e.relation(kid1, c).primary!!.readings.map { it.code })
        assertEquals("Randanamma", e.relation(kid1, c).primary!!.readings[0].terms.ml[0].roman)
        assertNotEquals("sM", e.relation(kid2, bb).primary!!.readings[0].code)
        // the ex-wife is described as such
        assertEquals(listOf("xW"), e.relation(a, bb).primary!!.readings.map { it.code })
        // no path may travel through an ex-spouse: B's other relatives are not "A's relatives"
        val toB = e.relation(kid2, bb)
        assertTrue((listOf(toB.primary!!) + toB.alternatives).any { p -> p.steps.last().bond == Bond.EX && p.steps.count { it.kind == EdgeKind.SPOUSE } == 1 })
    }

    @Test fun full_siblings_are_not_half() {
        val e = fam.engine
        val b = FB()
        val p = b.m("P"); val q = b.f("Q"); val x = b.m("X", "1990"); val y = b.f("Y", "1992")
        b.kids(p, q, x, y)
        val r = RelationshipEngine(b.data).relation(y, x).primary!!
        assertEquals(listOf("eB"), r.readings.map { it.code })
        assertNotNull(e)
    }

    @Test fun adoptive_relationships_are_distinct_from_biological() {
        val b = FB()
        val dad = b.m("Dad"); val mom = b.f("Mom"); val adopted = b.m("Adopted", "2010"); val bio = b.f("Bio", "2008")
        b.kids(dad, mom, bio)
        b.parent(dad, adopted, LinkType.ADOPTIVE); b.parent(mom, adopted, LinkType.ADOPTIVE)
        val e = RelationshipEngine(b.data)
        val toDad = e.relation(adopted, dad).primary!!
        assertEquals(listOf("aF"), toDad.readings.map { it.code })
        assertEquals("Adoptive father", toDad.readings[0].terms.relationEn)
        assertTrue("falls back to Appa but marks it approximate", toDad.readings[0].terms.taApproximate)
        assertEquals("Appa", toDad.readings[0].terms.ta[0].roman)
        assertTrue(e.relation(bio, adopted).primary!!.readings[0].code.startsWith("a"))
        assertTrue(e.relation(dad, adopted).primary!!.readings[0].terms.relationEn.startsWith("Adoptive"))
    }

    @Test fun step_parent_is_never_silently_biological() {
        val b = FB()
        val dad = b.m("Dad"); val mom = b.f("Mom"); val kid = b.m("Kid", "2000"); val stepDad = b.m("StepDad")
        b.kids(dad, mom, kid)
        // before the marriage there is no relation between Kid and StepDad
        assertFalse(RelationshipEngine(b.data).relation(kid, stepDad).connected)
        b.marry(mom, stepDad)
        val e = RelationshipEngine(b.data)
        val r = e.relation(kid, stepDad).primary!!
        assertEquals(listOf("sF"), r.readings.map { it.code })
        assertEquals("Step-father", r.readings[0].terms.relationEn)
        assertEquals(setOf(dad, mom), b.data.index.realParentIds(kid))
        assertEquals(listOf("sS"), e.relation(stepDad, kid).primary!!.readings.map { it.code })
    }

    @Test fun explicit_step_link_without_marriage() {
        val b = FB()
        val kid = b.m("Kid"); val s = b.f("StepMom")
        b.parent(s, kid, LinkType.STEP)
        assertEquals(listOf("sM"), RelationshipEngine(b.data).relation(kid, s).primary!!.readings.map { it.code })
    }

    @Test fun step_siblings() {
        val b = FB()
        val dad = b.m("Dad"); val mom = b.f("Mom"); val kid = b.m("Kid", "2000")
        val stepDad = b.m("StepDad"); val stepSis = b.f("StepSis", "2002")
        b.kids(dad, mom, kid); b.kids(stepDad, null, stepSis); b.marry(mom, stepDad)
        val r = RelationshipEngine(b.data).relation(kid, stepSis).primary!!
        assertEquals(listOf("sFD"), r.readings.map { it.code })
        assertTrue(r.readings[0].terms.relationEn.contains("step-father", ignoreCase = true))
    }

    @Test fun multiple_relationship_paths_are_reported() {
        val f = Family()
        // Raghul marries his father's elder brother's daughter
        val cousinBride = f.b.f("CousinBride", "1997")
        f.b.parent(f.periyappa, cousinBride); f.b.parent(f.periyamma, cousinBride)
        f.b.marry(f.raghul, cousinBride)
        val res = RelationshipEngine(f.b.data).relation(f.raghul, cousinBride)
        assertEquals(listOf("W"), res.primary!!.readings.map { it.code })
        assertTrue("cousin path must be listed as an alternative", res.alternatives.any { alt -> alt.readings.any { it.code.startsWith("FeBD") } })
    }

    @Test fun deceased_and_marriage_status_do_not_change_relationships() {
        val f = Family()
        val d = (FamilyEditor.setDeceased(f.data, f.appaji, true, "2020") as EditResult.Ok).data
        assertEquals(listOf("F"), RelationshipEngine(d).relation(f.raghul, f.appaji).primary!!.readings.map { it.code })
        assertEquals(f.data.links.size, d.links.size)
        assertEquals(f.data.marriages, d.marriages)
    }

    @Test fun terminology_overrides_replace_defaults_without_engine_changes() {
        val f = Family()
        val term = Terminology(overrides = listOf(TermOverride("FeB", Language.TA, "பெரிய அப்பா")))
        val t = RelationshipEngine(f.data, term).relation(f.raghul, f.periyappa).primary!!.readings[0].terms
        assertEquals("பெரிய அப்பா", t.ta[0].text)
        assertEquals("Periyappa", Terminology().resolve("FeB").ta[0].roman)
    }

    @Test fun unmapped_relations_are_not_invented() {
        val f = Family()
        val t = Terminology().resolve("FFFF")
        assertTrue(t.ta.isEmpty() && t.ml.isEmpty())
        assertEquals("Paternal great-great-grandfather", t.relationEn)
        assertTrue(fam.engine.relation(f.raghul, f.raghul).isSelf)
    }

    @Test fun unconnected_people_have_no_relation() {
        val b = FB()
        val a = b.person("A", Gender.MALE, root = true); val x = b.person("X", Gender.FEMALE)
        val r = RelationshipEngine(b.data).relation(a, x)
        assertFalse(r.connected); assertNull(r.primary)
    }

    @Test fun step_by_step_path_text() {
        val r = fam.engine.relation(fam.raghul, fam.periyappa).primary!!
        assertEquals(listOf("Raghul's father is Appaji", "Appaji's elder brother is Periyappa"), r.stepTexts)
    }
}
