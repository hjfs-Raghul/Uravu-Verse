package com.infinitefamily.domain

object SettingKeys {
    const val ROOT = "rootId"
    const val NOTIFY = "notify_enabled"
    const val HOME_CITY = "home_city"
    const val SAMPLE_LOADED = "sample_loaded"
}

/** Optional demonstration family. Every record is flagged `isSample` so it can be removed in one step. */
object SampleFamily {
    fun apply(data: FamilyData): EditResult {
        val root = data.root ?: return EditResult.Fail(ErrorCode.NOT_FOUND, "No reference person.")
        if (data.index.parentLinks(root.id).isNotEmpty())
            return EditResult.Fail(ErrorCode.INVALID, "Sample data can only be added while the reference person has no parents recorded.")
        var clock = System.currentTimeMillis()
        var d = data
        fun person(name: String, g: Gender, dob: String?, loc: String, deceased: Boolean = false, death: String? = null): String {
            val p = Person(FamilyEditor.newId(), name, g, dob = dob, location = loc, deceased = deceased, deathDate = death, isSample = true, createdAt = clock++)
            d = (FamilyEditor.addPerson(d, p, force = true) as EditResult.Ok).data
            return p.id
        }
        fun parent(p: String, c: String, t: LinkType = LinkType.BIOLOGICAL) { d = (FamilyEditor.addParentLink(d, p, c, t) as EditResult.Ok).data }
        fun marry(a: String, b: String, s: MarriageStatus = MarriageStatus.CURRENT) { d = (FamilyEditor.addMarriage(d, a, b, s) as EditResult.Ok).data }
        fun kids(a: String, b: String?, vararg k: String) = k.forEach { parent(a, it); if (b != null) parent(b, it) }

        val appaji = person("Appaji", Gender.MALE, "1962", "Chennai")
        val amma = person("Amma", Gender.FEMALE, "1966", "Chennai")
        val lakshmi = person("Lakshmi", Gender.FEMALE, "1964", "Mumbai")
        val suresh = person("Suresh", Gender.MALE, "1960", "Mumbai")
        val kumar = person("Kumar", Gender.MALE, "1987", "Mumbai")
        val divya = person("Divya", Gender.FEMALE, "2000", "Chennai")
        val meera = person("Meera", Gender.FEMALE, "2015", "Chennai")
        val thatha = person("Thatha", Gender.MALE, "1932", "Madurai", deceased = true, death = "2015")
        val paatti = person("Paatti", Gender.FEMALE, "1936", "Madurai")
        val periyappa = person("Periyappa", Gender.MALE, "1958", "Coimbatore")
        val periyamma = person("Periyamma", Gender.FEMALE, "1960", "Coimbatore")
        val anand = person("Anand", Gender.MALE, "1988", "Coimbatore")
        val athai = person("Athai", Gender.FEMALE, "1965", "Madurai")
        val athaiHusband = person("Athai's husband", Gender.MALE, "1962", "Madurai")
        val karthik = person("Karthik", Gender.MALE, "1992", "Madurai")
        val mgf = person("Maternal grandfather", Gender.MALE, "1938", "Kochi")
        val mgm = person("Maternal grandmother", Gender.FEMALE, "1942", "Kochi")
        val mama = person("Mama", Gender.MALE, "1968", "Bengaluru")
        val chithi = person("Chithi", Gender.FEMALE, "1972", "Kochi")
        val priya = person("Priya", Gender.FEMALE, "1996", "Kochi")
        val aarav = person("Aarav", Gender.MALE, "2023", "Chennai")

        marry(thatha, paatti, MarriageStatus.WIDOWED); kids(thatha, paatti, periyappa, appaji, athai)
        parent(appaji, divya); marry(periyappa, periyamma); kids(periyappa, periyamma, anand)
        marry(athai, athaiHusband); kids(athai, athaiHusband, karthik)
        marry(mgf, mgm); kids(mgf, mgm, amma, mama, chithi)
        marry(appaji, lakshmi, MarriageStatus.DIVORCED); parent(appaji, kumar); parent(lakshmi, kumar)
        marry(lakshmi, suresh)
        marry(appaji, amma); parent(amma, divya); parent(appaji, root.id); parent(amma, root.id)
        parent(appaji, meera, LinkType.ADOPTIVE); parent(amma, meera, LinkType.ADOPTIVE)
        marry(root.id, priya); kids(root.id, priya, aarav)
        // birth order among Appaji's children
        d = (FamilyEditor.reorderSiblings(d, listOf(kumar, root.id, divya, meera)) as EditResult.Ok).data
        d = (FamilyEditor.reorderSiblings(d, listOf(periyappa, appaji, athai)) as EditResult.Ok).data
        return EditResult.Ok(d)
    }

    fun sampleCount(data: FamilyData) = data.persons.values.count { it.isSample }

    fun remove(data: FamilyData): EditResult {
        var d = data
        for (p in data.persons.values.filter { it.isSample }) {
            d = (FamilyEditor.deletePerson(d, p.id) as? EditResult.Ok ?: continue).data
        }
        return EditResult.Ok(d)
    }
}
