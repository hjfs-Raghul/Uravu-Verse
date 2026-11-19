package com.infinitefamily.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LayoutBackupTest {
    // ------------------------------------------------------------------ layout
    @Test fun layout_is_generated_from_relationships_without_overlap() {
        val f = Family()
        val layout = TreeLayout.compute(f.data)
        assertEquals(f.data.persons.keys, layout.positions.keys)
        assertEquals(0f, layout.positions.getValue(f.raghul).x, 0.01f)
        assertEquals(0f, layout.positions.getValue(f.raghul).y, 0.01f)
        // parents are above children; spouses share a row
        assertTrue(layout.positions.getValue(f.appaji).y < layout.positions.getValue(f.raghul).y)
        assertTrue(layout.positions.getValue(f.raghul).y < layout.positions.getValue(f.kid).y)
        assertEquals(layout.positions.getValue(f.raghul).y, layout.positions.getValue(f.priya).y, 0.01f)
        // no two stickers in one row overlap
        layout.positions.values.groupBy { it.y }.forEach { (_, row) ->
            val xs = row.map { it.x }.sorted()
            for (i in 1 until xs.size) assertTrue("overlap in row: ${xs[i] - xs[i - 1]}", xs[i] - xs[i - 1] >= TreeLayout.NODE_W - 0.5f)
        }
    }

    @Test fun layout_places_spouses_of_multiple_marriages_adjacent_and_keeps_birth_order() {
        val b = FB(); val a = b.person("A", Gender.MALE, root = true); val w1 = b.f("W1"); val w2 = b.f("W2")
        val m1 = b.marry(a, w1, MarriageStatus.DIVORCED); b.marry(a, w2)
        val k1 = b.m("K1"); val k2 = b.m("K2"); val k3 = b.m("K3")
        b.kids(a, w1, k1, k2); b.kids(a, w2, k3)
        val d = (FamilyEditor.reorderSiblings(b.data, listOf(k1, k2, k3)) as EditResult.Ok).data
        val pos = TreeLayout.compute(d).positions
        // divorced spouse on the left, current on the right
        assertTrue(pos.getValue(w1).x < pos.getValue(a).x && pos.getValue(a).x < pos.getValue(w2).x)
        assertTrue(pos.getValue(k1).x < pos.getValue(k2).x && pos.getValue(k2).x < pos.getValue(k3).x)
        assertNotNull(m1)
    }

    @Test fun disconnected_people_are_still_laid_out_without_overlap() {
        val b = FB(); val a = b.person("A", Gender.MALE, root = true); val x = b.f("X"); val y = b.m("Y")
        b.parent(y, a); // X is an island
        val pos = TreeLayout.compute(b.data).positions
        assertEquals(3, pos.size)
        assertTrue(kotlin.math.abs(pos.getValue(x).x - pos.getValue(a).x) >= TreeLayout.NODE_W)
    }

    @Test fun layout_handles_large_trees_quickly() {
        val b = FB(); var gen = listOf(b.person("R", Gender.MALE, root = true))
        repeat(8) { depth ->
            val next = ArrayList<String>()
            for (p in gen) { val s = b.f("S$depth"); b.marry(p, s); repeat(2) { next += b.m("C$depth").also { c -> b.parent(p, c); b.parent(s, c) } } }
            gen = next
        }
        val t = System.nanoTime()
        val layout = TreeLayout.compute(b.data)
        assertTrue(layout.positions.size > 500)
        assertTrue("took ${(System.nanoTime() - t) / 1_000_000}ms", (System.nanoTime() - t) / 1_000_000 < 5000)
    }

    // ------------------------------------------------------------------ ages
    @Test fun age_from_dob_and_relative_age_fallback() {
        val today = LocalDate.of(2026, 6, 15)
        val p = Person("1", "A", Gender.MALE, dob = "1990-06-16")
        assertEquals(35, Ages.ageYears(p, today))
        assertEquals(36, Ages.ageYears(p.copy(dob = "1990-06-15"), today))
        assertEquals(36, Ages.ageYears(p.copy(dob = "1990"), today))
        assertEquals("Died aged 40", Ages.ageText(p.copy(dob = "1950", deceased = true, deathDate = "1990-12-01"), today))
        val older = Person("2", "B", Gender.MALE, relativeAge = RelativeAge.OLDER)
        val younger = Person("3", "C", Gender.MALE, relativeAge = RelativeAge.YOUNGER)
        assertEquals(AgeOrder.ELDER, Ages.compare(younger, older))
        assertEquals(AgeOrder.UNKNOWN, Ages.compare(Person("4", "D", Gender.MALE), older))
        assertEquals(AgeOrder.UNKNOWN, Ages.compare(Person("5", "E", Gender.MALE, dob = "1990"), Person("6", "F", Gender.MALE, dob = "1990")))
    }

    // ------------------------------------------------------------------ backup
    private fun roundTrip(f: Family, overrides: List<TermOverride> = emptyList(), photos: Map<String, ByteArray> = emptyMap()): ParsedBackup {
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupSnapshot(f.data, overrides, mapOf("notif" to "1")), { photos[it] }, out, 123L, "1.0.0")
        return BackupCodec.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun backup_restores_the_complete_tree() {
        val f = Family()
        val withPhoto = f.data.persons.getValue(f.raghul).copy(photoFile = "r.jpg", stickerVariant = 7, stickerCategory = "young_man", birthOrder = 2.5, relativeAge = RelativeAge.SAME)
        f.b.data = (FamilyEditor.updatePerson(f.data, withPhoto) as EditResult.Ok).data
        val m = f.data.index.marriages(f.priya).first()
        f.b.data = (FamilyEditor.setMarriageStatus(f.data, m.id, MarriageStatus.WIDOWED, "2030") as EditResult.Ok).data
        val parsed = roundTrip(f, listOf(TermOverride("FeB", Language.TA, "பெரிய அப்பா")), mapOf("r.jpg" to byteArrayOf(1, 2, 3)))
        assertEquals(f.data.persons, parsed.snapshot.data.persons)
        assertEquals(f.data.links, parsed.snapshot.data.links)
        assertEquals(f.data.marriages, parsed.snapshot.data.marriages)
        assertEquals(f.raghul, parsed.snapshot.data.rootId)
        assertEquals(listOf(TermOverride("FeB", Language.TA, "பெரிய அப்பா")), parsed.snapshot.overrides)
        assertEquals("1", parsed.snapshot.settings["notif"])
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.photos["r.jpg"])
        assertEquals(BackupCodec.CURRENT_VERSION, parsed.backupVersion)
        // the restored tree gives identical relationships
        assertEquals(f.engine.relation(f.raghul, f.periyappa).primary!!.summary,
            RelationshipEngine(parsed.snapshot.data).relation(f.raghul, f.periyappa).primary!!.summary)
    }

    private fun zip(manifest: String?, data: String?, photos: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            manifest?.let { z.putNextEntry(ZipEntry("manifest.json")); z.write(it.toByteArray()); z.closeEntry() }
            data?.let { z.putNextEntry(ZipEntry("data.json")); z.write(it.toByteArray()); z.closeEntry() }
            photos.forEach { (n, b) -> z.putNextEntry(ZipEntry("photos/$n")); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }
    private fun expectFailure(bytes: ByteArray, contains: String) {
        try { BackupCodec.read(ByteArrayInputStream(bytes)); fail("expected failure") }
        catch (e: BackupException) { assertTrue(e.message, e.message!!.contains(contains, ignoreCase = true)) }
    }
    private val goodManifest = """{"format":"${BackupCodec.FORMAT}","backupVersion":1,"createdAt":1}"""

    @Test fun corrupted_or_foreign_backups_are_rejected_gracefully() {
        expectFailure("not a zip".toByteArray(), "not a valid backup")
        expectFailure(zip(null, "{}"), "manifest")
        expectFailure(zip(goodManifest, null), "incomplete")
        expectFailure(zip("{broken", "{}"), "corrupted")
        expectFailure(zip("""{"format":"other","backupVersion":1}""", "{}"), "not a Family Tree backup")
        expectFailure(zip(goodManifest, "{broken"), "corrupted")
    }

    @Test fun newer_backup_versions_are_reported_as_incompatible() {
        expectFailure(zip("""{"format":"${BackupCodec.FORMAT}","backupVersion":99}""", "{}"), "newer version")
        expectFailure(zip("""{"format":"${BackupCodec.FORMAT}","backupVersion":0}""", "{}"), "Invalid backup version")
    }

    @Test fun inconsistent_data_is_rejected_before_import() {
        val bad = """{"persons":[{"id":"a","name":"A","gender":"MALE"}],
            "parentLinks":[{"id":"l","parentId":"ghost","childId":"a","type":"BIOLOGICAL"}],"marriages":[],"settings":{"rootId":"a"}}"""
        expectFailure(zip(goodManifest, bad), "inconsistent")
        val noRoot = """{"persons":[{"id":"a","name":"A","gender":"MALE"}],"parentLinks":[],"marriages":[],"settings":{}}"""
        expectFailure(zip(goodManifest, noRoot), "reference person")
        val badEnum = """{"persons":[{"id":"a","name":"A","gender":"ROBOT"}],"parentLinks":[],"marriages":[],"settings":{"rootId":"a"}}"""
        expectFailure(zip(goodManifest, badEnum), "gender")
        val cyc = """{"persons":[{"id":"a","name":"A","gender":"MALE"},{"id":"b","name":"B","gender":"MALE"}],
            "parentLinks":[{"id":"1","parentId":"a","childId":"b","type":"BIOLOGICAL"},{"id":"2","parentId":"b","childId":"a","type":"BIOLOGICAL"}],"marriages":[],"settings":{"rootId":"a"}}"""
        expectFailure(zip(goodManifest, cyc), "inconsistent")
    }

    @Test fun missing_and_unsafe_photos_are_dropped_with_warnings() {
        val data = """{"persons":[{"id":"a","name":"A","gender":"MALE","photoFile":"gone.jpg"}],"parentLinks":[],"marriages":[],"settings":{"rootId":"a"}}"""
        val parsed = BackupCodec.read(ByteArrayInputStream(zip(goodManifest, data, mapOf("../evil.jpg" to byteArrayOf(1)))))
        assertNull(parsed.snapshot.data.persons.getValue("a").photoFile)
        assertTrue(parsed.warnings.size >= 2)
        assertTrue(parsed.photos.isEmpty())
    }
}
