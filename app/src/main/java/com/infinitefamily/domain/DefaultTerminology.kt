package com.infinitefamily.domain

/**
 * Default terminology, keyed by kinship code. These are COMMON terms, not universal ones: Tamil and
 * Malayalam kinship vocabulary differs by community, region and family. Every entry can be corrected
 * from Settings > Terminology (stored as [TermOverride]s). Missing combinations are deliberately left
 * empty instead of being invented; the UI then shows "not configured".
 *
 * Code notation (read left to right, "<last> is <first>'s ..."):
 *   F father, M mother, S son, D daughter, B brother, Z sister, H husband, W wife.
 *   e / y before a sibling letter = elder / younger than the previous person (FeB = father's elder brother).
 *   s / a / h / x before a letter = step / adoptive / half / ex.   "~e" "~y" suffix = target elder/younger than you (cousins).
 */
object DefaultTerminology {
    private val map = LinkedHashMap<String, TermEntry>()
    val entries: Map<String, TermEntry> get() = map

    private fun t(text: String, roman: String, note: String? = null) = Term(text, roman, note)
    private fun e(code: String, rel: String, addr: String, ta: List<Term> = emptyList(), ml: List<Term> = emptyList(), note: String? = null) {
        map[code] = TermEntry(code, rel, addr, ta, ml, note)
    }

    private const val GP_NOTE = "Some families use different words for the paternal and maternal side."
    private const val CROSS_NOTE = "Where cross-cousin marriage is customary, families often use special terms (e.g. Machan / Maithuni). Configure for your family."
    private const val DIRECT_NOTE = "Direct address between spouses is usually by name."

    init {
        // Parents and children
        e("F", "Father", "Dad / Father", listOf(t("அப்பா", "Appa")), listOf(t("അച്ഛൻ", "Achan"), t("അപ്പൻ", "Appan", "Used in some communities")))
        e("M", "Mother", "Mom / Mother", listOf(t("அம்மா", "Amma")), listOf(t("അമ്മ", "Amma")))
        e("S", "Son", "Son", listOf(t("மகன்", "Magan")), listOf(t("മകൻ", "Makan"), t("മോൻ", "Mon", "Affectionate")))
        e("D", "Daughter", "Daughter", listOf(t("மகள்", "Magal")), listOf(t("മകൾ", "Makal"), t("മോൾ", "Mol", "Affectionate")))
        // Siblings
        e("eB", "Elder brother", "Elder brother", listOf(t("அண்ணன்", "Annan", "Address: Anna")), listOf(t("ചേട്ടൻ", "Chettan"), t("ഏട്ടൻ", "Ettan")))
        e("yB", "Younger brother", "Younger brother", listOf(t("தம்பி", "Thambi")), listOf(t("അനിയൻ", "Aniyan"), t("അനുജൻ", "Anujan")))
        e("eZ", "Elder sister", "Elder sister", listOf(t("அக்கா", "Akka")), listOf(t("ചേച്ചി", "Chechi")))
        e("yZ", "Younger sister", "Younger sister", listOf(t("தங்கை", "Thangai")), listOf(t("അനിയത്തി", "Aniyathi"), t("അനുജത്തി", "Anujathi")))
        // Spouses
        e("H", "Husband", "Husband", listOf(t("கணவன்", "Kanavan")), listOf(t("ഭർത്താവ്", "Bharthavu")), DIRECT_NOTE)
        e("W", "Wife", "Wife", listOf(t("மனைவி", "Manaivi")), listOf(t("ഭാര്യ", "Bharya")), DIRECT_NOTE)
        e("xH", "Ex-husband", "Ex-husband", listOf(t("முன்னாள் கணவன்", "Munnaal kanavan")), listOf(t("മുൻ ഭർത്താവ്", "Mun bharthavu")))
        e("xW", "Ex-wife", "Ex-wife", listOf(t("முன்னாள் மனைவி", "Munnaal manaivi")), listOf(t("മുൻ ഭാര്യ", "Mun bharya")))
        // Grandparents / grandchildren
        e("FF", "Paternal grandfather", "Grandfather", listOf(t("தாத்தா", "Thatha")), listOf(t("അപ്പൂപ്പൻ", "Appooppan"), t("മുത്തച്ഛൻ", "Muthachan")), GP_NOTE)
        e("FM", "Paternal grandmother", "Grandmother", listOf(t("பாட்டி", "Paatti"), t("ஆச்சி", "Aachi")), listOf(t("അമ്മൂമ്മ", "Ammoomma"), t("മുത്തശ്ശി", "Muthashi")), GP_NOTE)
        e("MF", "Maternal grandfather", "Grandfather", listOf(t("தாத்தா", "Thatha")), listOf(t("മുത്തച്ഛൻ", "Muthachan"), t("അപ്പൂപ്പൻ", "Appooppan")), GP_NOTE)
        e("MM", "Maternal grandmother", "Grandmother", listOf(t("பாட்டி", "Paatti"), t("ஆச்சி", "Aachi")), listOf(t("മുത്തശ്ശി", "Muthashi"), t("അമ്മൂമ്മ", "Ammoomma")), GP_NOTE)
        for (c in listOf("SS", "DS")) e(c, "Grandson", "Grandson", listOf(t("பேரன்", "Peran")), listOf(t("ചെറുമകൻ", "Cherumakan"), t("കൊച്ചുമകൻ", "Kochumakan")))
        for (c in listOf("SD", "DD")) e(c, "Granddaughter", "Granddaughter", listOf(t("பேத்தி", "Peththi")), listOf(t("ചെറുമകൾ", "Cherumakal"), t("കൊച്ചുമകൾ", "Kochumakal")))
        // Parents' siblings
        e("FeB", "Father's elder brother", "Uncle", listOf(t("பெரியப்பா", "Periyappa")), listOf(t("വല്യച്ഛൻ", "Valyachan")))
        e("FyB", "Father's younger brother", "Uncle", listOf(t("சித்தப்பா", "Chithappa")), listOf(t("ചെറിയച്ഛൻ", "Cheriyachan"), t("ചിറ്റപ്പൻ", "Chittappan")))
        e("FeBW", "Father's elder brother's wife", "Aunt", listOf(t("பெரியம்மா", "Periyamma")), listOf(t("വല്യമ്മ", "Valyamma")))
        e("FyBW", "Father's younger brother's wife", "Aunt", listOf(t("சித்தி", "Chithi")), listOf(t("ചെറിയമ്മ", "Cheriyamma"), t("ചിറ്റ", "Chitta")))
        e("FZ", "Father's sister", "Aunt", listOf(t("அத்தை", "Athai")), listOf(t("അമ്മായി", "Ammayi"), t("അപ്പച്ചി", "Appachi", "Used in some communities")))
        e("FZH", "Father's sister's husband", "Uncle", listOf(t("மாமா", "Mama"), t("அத்திம்பேர்", "Athimber", "Brahmin usage")))
        e("MB", "Mother's brother", "Uncle", listOf(t("மாமா", "Mama")), listOf(t("അമ്മാവൻ", "Ammavan")))
        e("MBW", "Mother's brother's wife", "Aunt", listOf(t("மாமி", "Mami"), t("அத்தை", "Athai", "Where cross-cousin marriage is customary")), listOf(t("അമ്മായി", "Ammayi")))
        e("MeZ", "Mother's elder sister", "Aunt", listOf(t("பெரியம்மா", "Periyamma")), listOf(t("വല്യമ്മ", "Valyamma")))
        e("MyZ", "Mother's younger sister", "Aunt", listOf(t("சித்தி", "Chithi")), listOf(t("ചെറിയമ്മ", "Cheriyamma"), t("ചിറ്റ", "Chitta"), t("കൊച്ചമ്മ", "Kochamma")))
        e("MeZH", "Mother's elder sister's husband", "Uncle", listOf(t("பெரியப்பா", "Periyappa")), listOf(t("വല്യച്ഛൻ", "Valyachan")))
        e("MyZH", "Mother's younger sister's husband", "Uncle", listOf(t("சித்தப்பா", "Chithappa")), listOf(t("ചെറിയച്ഛൻ", "Cheriyachan")))
        // Cousins: parallel cousins are normally addressed like siblings
        val parallel = listOf("FBS" to "Father's brother's son", "MZS" to "Mother's sister's son")
        for ((c, d) in parallel) {
            e("$c~e", "$d (elder than you)", "Cousin brother", listOf(t("அண்ணன்", "Annan")), listOf(t("ചേട്ടൻ", "Chettan")), "Parallel cousins are addressed like siblings in most families.")
            e("$c~y", "$d (younger than you)", "Cousin brother", listOf(t("தம்பி", "Thambi")), listOf(t("അനിയൻ", "Aniyan")), "Parallel cousins are addressed like siblings in most families.")
        }
        for ((c, d) in listOf("FBD" to "Father's brother's daughter", "MZD" to "Mother's sister's daughter")) {
            e("$c~e", "$d (elder than you)", "Cousin sister", listOf(t("அக்கா", "Akka")), listOf(t("ചേച്ചി", "Chechi")), "Parallel cousins are addressed like siblings in most families.")
            e("$c~y", "$d (younger than you)", "Cousin sister", listOf(t("தங்கை", "Thangai")), listOf(t("അനിയത്തി", "Aniyathi")), "Parallel cousins are addressed like siblings in most families.")
        }
        e("FZS", "Father's sister's son", "Cousin", listOf(t("அத்தை மகன்", "Athai magan"), t("மச்சான்", "Machan", "Customary in some families")), listOf(t("അമ്മായിയുടെ മകൻ", "Ammayiyude makan")), CROSS_NOTE)
        e("FZD", "Father's sister's daughter", "Cousin", listOf(t("அத்தை மகள்", "Athai magal")), listOf(t("അമ്മായിയുടെ മകൾ", "Ammayiyude makal")), CROSS_NOTE)
        e("MBS", "Mother's brother's son", "Cousin", listOf(t("மாமா மகன்", "Mama magan"), t("மச்சான்", "Machan", "Customary in some families")), listOf(t("അമ്മാവന്റെ മകൻ", "Ammavante makan")), CROSS_NOTE)
        e("MBD", "Mother's brother's daughter", "Cousin", listOf(t("மாமா மகள்", "Mama magal")), listOf(t("അമ്മാവന്റെ മകൾ", "Ammavante makal")), CROSS_NOTE)
        // Nephews and nieces
        e("eBS", "Elder brother's son", "Nephew", listOf(t("அண்ணன் மகன்", "Annan magan")), listOf(t("അനന്തരവൻ", "Ananthiravan")))
        e("yBS", "Younger brother's son", "Nephew", listOf(t("தம்பி மகன்", "Thambi magan")), listOf(t("അനന്തരവൻ", "Ananthiravan")))
        e("eBD", "Elder brother's daughter", "Niece", listOf(t("அண்ணன் மகள்", "Annan magal")), listOf(t("അനന്തരവൾ", "Ananthiravaal")))
        e("yBD", "Younger brother's daughter", "Niece", listOf(t("தம்பி மகள்", "Thambi magal")), listOf(t("അനന്തരവൾ", "Ananthiravaal")))
        e("BS", "Brother's son", "Nephew", listOf(t("மகன்", "Magan", "Literally 'brother's son'; often addressed like a son")), listOf(t("അനന്തരവൻ", "Ananthiravan")))
        e("BD", "Brother's daughter", "Niece", listOf(t("மகள்", "Magal", "Often addressed like a daughter")), listOf(t("അനന്തരവൾ", "Ananthiravaal")))
        e("ZS", "Sister's son", "Nephew", listOf(t("மருமகன்", "Marumagan")), listOf(t("മരുമകൻ", "Marumakan")))
        e("ZD", "Sister's daughter", "Niece", listOf(t("மருமகள்", "Marumagal")), listOf(t("മരുമകൾ", "Marumakal")))
        // In-laws
        for (c in listOf("WF", "HF")) e(c, if (c == "WF") "Wife's father" else "Husband's father", "Father-in-law", listOf(t("மாமனார்", "Mamanar")), listOf(t("അമ്മായിയച്ഛൻ", "Ammayiyachan")))
        for (c in listOf("WM", "HM")) e(c, if (c == "WM") "Wife's mother" else "Husband's mother", "Mother-in-law", listOf(t("மாமியார்", "Mamiyar")), listOf(t("അമ്മായിയമ്മ", "Ammayiyamma")))
        e("SW", "Son's wife", "Daughter-in-law", listOf(t("மருமகள்", "Marumagal")), listOf(t("മരുമകൾ", "Marumakal")))
        e("DH", "Daughter's husband", "Son-in-law", listOf(t("மருமகன்", "Marumagan")), listOf(t("മരുമകൻ", "Marumakan")))
        e("WB", "Wife's brother", "Brother-in-law", listOf(t("மச்சான்", "Machan")), listOf(t("അളിയൻ", "Aliyan")))
        e("WZ", "Wife's sister", "Sister-in-law", listOf(t("மச்சினி", "Machini")))
        e("HeB", "Husband's elder brother", "Brother-in-law", listOf(t("மைத்துனர்", "Maithunar")), listOf(t("ചേട്ടൻ", "Chettan", "Addressed like an elder brother")))
        e("HyB", "Husband's younger brother", "Brother-in-law", listOf(t("கொழுந்தன்", "Kozhundhan")), listOf(t("അനിയൻ", "Aniyan", "Addressed like a younger brother")))
        e("HZ", "Husband's sister", "Sister-in-law", listOf(t("நாத்தனார்", "Naathanaar")), listOf(t("നാത്തൂൻ", "Naathoon")))
        e("eBW", "Elder brother's wife", "Sister-in-law", listOf(t("அண்ணி", "Anni")), listOf(t("ചേട്ടത്തി", "Chettathi"), t("ഏട്ടത്തി", "Ettathi")))
        e("yBW", "Younger brother's wife", "Sister-in-law", listOf(t("தம்பி மனைவி", "Thambi manaivi", "Descriptive")), listOf(t("അനിയന്റെ ഭാര്യ", "Aniyante bharya", "Descriptive")))
        e("eZH", "Elder sister's husband", "Brother-in-law", listOf(t("அத்தான்", "Athan"), t("அத்திம்பேர்", "Athimber", "Brahmin usage")), listOf(t("അളിയൻ", "Aliyan")))
        e("yZH", "Younger sister's husband", "Brother-in-law", listOf(t("மச்சான்", "Machan")), listOf(t("അളിയൻ", "Aliyan")))
        // Step relations (adoptive / half terms fall back to the plain relationship and are flagged approximate)
        e("sM", "Step-mother", "Step-mother", listOf(t("மாற்றாந்தாய்", "Maatrandhaai", "Formal")), listOf(t("രണ്ടാനമ്മ", "Randanamma")))
        e("sF", "Step-father", "Step-father", emptyList(), listOf(t("രണ്ടാനച്ഛൻ", "Randanachan")))
    }
}
