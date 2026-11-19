package com.infinitefamily.domain

import org.junit.Assert.fail

/** Small builder on top of [FamilyEditor] so tests exercise the real validation rules. */
class FB {
    var data = FamilyData()
    private var clock = 1L

    private fun apply(r: EditResult): String? = when (r) {
        is EditResult.Ok -> { data = r.data; r.createdId }
        is EditResult.Fail -> { fail("Unexpected failure: ${r.message}"); null }
    }

    fun person(name: String, g: Gender, dob: String? = null, rel: RelativeAge = RelativeAge.UNKNOWN, root: Boolean = false): String {
        val p = Person(FamilyEditor.newId(), name, g, dob = dob, relativeAge = rel, createdAt = clock++)
        return apply(FamilyEditor.addPerson(data, p, force = true, makeRoot = root))!!
    }
    fun m(name: String, dob: String? = null) = person(name, Gender.MALE, dob)
    fun f(name: String, dob: String? = null) = person(name, Gender.FEMALE, dob)
    fun parent(parent: String, child: String, type: LinkType = LinkType.BIOLOGICAL) { apply(FamilyEditor.addParentLink(data, parent, child, type)) }
    fun marry(a: String, b: String, status: MarriageStatus = MarriageStatus.CURRENT): String = apply(FamilyEditor.addMarriage(data, a, b, status))!!
    fun kids(p1: String, p2: String?, vararg kids: String) { kids.forEach { parent(p1, it); if (p2 != null) parent(p2, it) } }
    fun name(id: String) = data.persons.getValue(id).name
}

/** A realistic Tamil/Malayalam style family around Raghul. */
class Family {
    val b = FB()
    val raghul = b.person("Raghul", Gender.MALE, "1995-05-10", root = true)
    val appaji = b.m("Appaji", "1965-03-01")
    val amma = b.f("Amma", "1968-07-01")
    // paternal side
    val pgf = b.m("PaternalGrandfather", "1935")
    val pgm = b.f("PaternalGrandmother", "1938")
    val periyappa = b.m("Periyappa", "1960-01-01")     // father's elder brother
    val periyamma = b.f("Periyamma", "1962")
    val chithappa = b.m("Chithappa", "1970-01-01")     // father's younger brother
    val athai = b.f("Athai", "1963")                   // father's sister
    val athaiHusband = b.m("AthaiHusband", "1960")
    val anna = b.m("CousinAnna", "1990-02-02")         // father's elder brother's son, older than Raghul
    val crossCousin = b.m("CrossCousin", "1992")       // father's sister's son
    // maternal side
    val mgf = b.m("MaternalGrandfather", "1938")
    val mgm = b.f("MaternalGrandmother", "1940")
    val mama = b.m("Mama", "1965-01-01")               // mother's brother
    val mami = b.f("Mami", "1967")
    val chithi = b.f("Chithi", "1972")                 // mother's younger sister
    // own family
    val priya = b.f("Priya", "1996")
    val kid = b.m("Kid", "2022")

    init {
        b.kids(pgf, pgm, appaji, periyappa, chithappa, athai)
        b.marry(pgf, pgm)
        b.marry(appaji, amma); b.parent(appaji, raghul); b.parent(amma, raghul)
        b.marry(periyappa, periyamma); b.kids(periyappa, periyamma, anna)
        b.marry(athai, athaiHusband); b.kids(athai, athaiHusband, crossCousin)
        b.kids(mgf, mgm, amma, mama, chithi)
        b.marry(mgf, mgm); b.marry(mama, mami)
        b.marry(raghul, priya); b.kids(raghul, priya, kid)
    }
    val data get() = b.data
    val engine get() = RelationshipEngine(data)
    fun code(from: String, to: String) = engine.relation(from, to).primary!!.readings.map { it.code }
}
