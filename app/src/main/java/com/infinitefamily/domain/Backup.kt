package com.infinitefamily.domain

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Everything needed to rebuild the family on another device (photos travel separately in the ZIP). */
data class BackupSnapshot(
    val data: FamilyData,
    val overrides: List<TermOverride> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

data class ParsedBackup(
    val snapshot: BackupSnapshot,
    val photos: Map<String, ByteArray>,
    val warnings: List<String>,
    val backupVersion: Int,
    val createdAt: Long,
    val appVersion: String,
)

class BackupException(message: String) : Exception(message)

/**
 * Backup ZIP layout:
 *   manifest.json   { format, backupVersion, createdAt, appVersion, counts }
 *   data.json       persons, parentLinks, marriages, termOverrides, settings
 *   photos/<file>   profile photos referenced by persons
 * [CURRENT_VERSION] is bumped when the layout changes; older versions can then be migrated in [read].
 */
object BackupCodec {
    const val FORMAT = "infinite-family-tree-backup"
    const val CURRENT_VERSION = 1
    private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    private val safeName = Regex("^[A-Za-z0-9._-]{1,120}$")

    fun write(
        snapshot: BackupSnapshot, photoReader: (String) -> ByteArray?, out: OutputStream,
        createdAt: Long = System.currentTimeMillis(), appVersion: String = "1.0.0",
    ) {
        val d = snapshot.data
        val manifest = buildJsonObject {
            put("format", FORMAT); put("backupVersion", CURRENT_VERSION)
            put("createdAt", createdAt); put("appVersion", appVersion)
            put("persons", d.persons.size); put("marriages", d.marriages.size); put("parentLinks", d.links.size)
        }
        val data = buildJsonObject {
            putJsonArray("persons") {
                d.persons.values.sortedBy { it.createdAt }.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("name", p.name); put("gender", p.gender.name)
                        put("dob", p.dob); put("deceased", p.deceased); put("deathDate", p.deathDate)
                        put("occupation", p.occupation); put("location", p.location); put("notes", p.notes)
                        put("photoFile", p.photoFile); put("relativeAge", p.relativeAge.name)
                        put("birthOrder", p.birthOrder); put("stickerVariant", p.stickerVariant)
                        put("stickerCategory", p.stickerCategory); put("isSample", p.isSample); put("createdAt", p.createdAt)
                    })
                }
            }
            putJsonArray("parentLinks") {
                d.links.values.forEach { add(buildJsonObject { put("id", it.id); put("parentId", it.parentId); put("childId", it.childId); put("type", it.type.name) }) }
            }
            putJsonArray("marriages") {
                d.marriages.values.sortedBy { it.order }.forEach {
                    add(buildJsonObject {
                        put("id", it.id); put("personAId", it.personAId); put("personBId", it.personBId)
                        put("status", it.status.name); put("startDate", it.startDate); put("endDate", it.endDate)
                        put("order", it.order); put("note", it.note)
                    })
                }
            }
            putJsonArray("termOverrides") {
                snapshot.overrides.forEach { add(buildJsonObject { put("code", it.code); put("language", it.language.name); put("text", it.text) }) }
            }
            putJsonObject("settings") {
                put("rootId", d.rootId)
                snapshot.settings.forEach { (k, v) -> if (k != "rootId") put(k, v) }
            }
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(data.toString().toByteArray()); zip.closeEntry()
            d.persons.values.mapNotNull { it.photoFile }.distinct().forEach { name ->
                val bytes = photoReader(name) ?: return@forEach
                zip.putNextEntry(ZipEntry("photos/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw BackupException("Missing or invalid field '$k'.")
    private fun JsonObject.optStr(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.int(k: String): Int = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: throw BackupException("Missing list '$k'.")
    private inline fun <reified E : Enum<E>> enumOf(name: String, what: String): E =
        enumValues<E>().firstOrNull { it.name == name } ?: throw BackupException("Unknown $what '$name'.")

    fun read(input: InputStream): ParsedBackup {
        var manifestText: String? = null
        var dataText: String? = null
        val photos = LinkedHashMap<String, ByteArray>()
        val warnings = ArrayList<String>()
        try {
            ZipInputStream(input).use { zip ->
                var total = 0L; var count = 0
                var any = false
                while (true) {
                    val e = zip.nextEntry ?: break
                    any = true
                    if (e.isDirectory) continue
                    if (++count > MAX_ENTRIES) throw BackupException("The backup contains too many files.")
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(chunk); if (n < 0) break
                        buf.write(chunk, 0, n); total += n
                        if (buf.size() > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw BackupException("The backup is too large or corrupted.")
                    }
                    when {
                        e.name == "manifest.json" -> manifestText = buf.toString(Charsets.UTF_8.name())
                        e.name == "data.json" -> dataText = buf.toString(Charsets.UTF_8.name())
                        e.name.startsWith("photos/") -> {
                            val n = e.name.removePrefix("photos/")
                            if (safeName.matches(n) && !n.contains("..")) photos[n] = buf.toByteArray()
                            else warnings += "Skipped a photo with an unsafe file name."
                        }
                    }
                }
                if (!any) throw BackupException("This file is not a valid backup (not a ZIP archive).")
            }
        } catch (e: BackupException) { throw e } catch (e: Exception) {
            throw BackupException("This file is not a valid backup (${e.message ?: "unreadable"}).")
        }
        val mText = manifestText ?: throw BackupException("This is not a Family Tree backup (manifest missing).")
        val dText = dataText ?: throw BackupException("The backup is incomplete (data missing).")

        val manifest = try { Json.parseToJsonElement(mText) as? JsonObject } catch (e: Exception) { null }
            ?: throw BackupException("The backup manifest is corrupted.")
        if (manifest.optStr("format") != FORMAT) throw BackupException("This file is not a Family Tree backup.")
        val version = manifest.int("backupVersion")
        if (version < 1) throw BackupException("Invalid backup version.")
        if (version > CURRENT_VERSION) throw BackupException("This backup was made by a newer version of the app (backup version $version). Update the app to restore it.")

        val root = try { Json.parseToJsonElement(dText) as? JsonObject } catch (e: Exception) { null }
            ?: throw BackupException("The backup data is corrupted.")

        val persons = LinkedHashMap<String, Person>()
        for (el in root.arr("persons")) {
            val o = el as? JsonObject ?: throw BackupException("Invalid person record.")
            val id = o.str("id")
            if (id in persons) throw BackupException("Duplicate person id in backup.")
            var photo = o.optStr("photoFile")
            if (photo != null && photo !in photos) { warnings += "Photo for ${o.optStr("name")} is missing from the backup."; photo = null }
            persons[id] = Person(
                id = id, name = o.str("name"), gender = enumOf<Gender>(o.str("gender"), "gender"),
                dob = o.optStr("dob"), deceased = o.bool("deceased"), deathDate = o.optStr("deathDate"),
                occupation = o.optStr("occupation") ?: "", location = o.optStr("location") ?: "", notes = o.optStr("notes") ?: "",
                photoFile = photo, relativeAge = o.optStr("relativeAge")?.let { enumOf<RelativeAge>(it, "relative age") } ?: RelativeAge.UNKNOWN,
                birthOrder = o.dbl("birthOrder"), stickerVariant = o.int("stickerVariant"),
                stickerCategory = o.optStr("stickerCategory"), isSample = o.bool("isSample"), createdAt = o.long("createdAt"),
            )
        }
        if (persons.isEmpty()) throw BackupException("The backup contains no people.")
        val links = LinkedHashMap<String, ParentLink>()
        for (el in root.arr("parentLinks")) {
            val o = el as? JsonObject ?: throw BackupException("Invalid relationship record.")
            val l = ParentLink(o.str("id"), o.str("parentId"), o.str("childId"), enumOf<LinkType>(o.str("type"), "link type"))
            if (l.id in links) throw BackupException("Duplicate relationship id in backup.")
            links[l.id] = l
        }
        val marriages = LinkedHashMap<String, Marriage>()
        for (el in root.arr("marriages")) {
            val o = el as? JsonObject ?: throw BackupException("Invalid marriage record.")
            val m = Marriage(o.str("id"), o.str("personAId"), o.str("personBId"), enumOf<MarriageStatus>(o.str("status"), "marriage status"),
                o.optStr("startDate"), o.optStr("endDate"), o.int("order"), o.optStr("note") ?: "")
            if (m.id in marriages) throw BackupException("Duplicate marriage id in backup.")
            marriages[m.id] = m
        }
        val overrides = (root["termOverrides"] as? JsonArray).orEmpty().map {
            val o = it as? JsonObject ?: throw BackupException("Invalid terminology record.")
            TermOverride(o.str("code"), enumOf<Language>(o.str("language"), "language"), o.str("text"))
        }
        val settingsObj = root["settings"] as? JsonObject ?: JsonObject(emptyMap())
        val settings = settingsObj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content } }.toMap()
        val rootId = settings["rootId"] ?: throw BackupException("The backup has no reference person.")
        val data = FamilyData(persons, links, marriages, rootId)
        val problems = FamilyEditor.validate(data)
        if (problems.isNotEmpty()) throw BackupException("The backup is inconsistent: ${problems.first()}")
        return ParsedBackup(BackupSnapshot(data, overrides, settings - "rootId"), photos, warnings, version,
            manifest.long("createdAt"), manifest.optStr("appVersion") ?: "")
    }
}
