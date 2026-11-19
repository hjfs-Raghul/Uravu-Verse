package com.infinitefamily.data

import androidx.room.withTransaction
import com.infinitefamily.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Single source of truth. The whole family graph is kept in memory as an immutable [FamilyData]
 * (people are tiny records; photos stay on disk) and every change is
 *   1. validated by the pure [FamilyEditor],
 *   2. written to Room in ONE transaction (only the rows that differ),
 *   3. published to the UI.
 * That gives atomic relationship changes and a cheap undo (previous snapshots).
 */
class FamilyRepository(private val db: AppDatabase, val photos: PhotoStore) {
    private val dao = db.dao()
    private val mutex = Mutex()
    private val _data = MutableStateFlow(FamilyData())
    private val _overrides = MutableStateFlow<List<TermOverride>>(emptyList())
    private val _settings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _loaded = MutableStateFlow(false)
    private val undoStack = ArrayDeque<FamilyData>()

    val data: StateFlow<FamilyData> = _data.asStateFlow()
    val overrides: StateFlow<List<TermOverride>> = _overrides.asStateFlow()
    val settings: StateFlow<Map<String, String>> = _settings.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    val canUndo get() = undoStack.isNotEmpty()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (d, o, s) = readAll(db)
            _overrides.value = o; _settings.value = s
            if (d.persons.isEmpty()) {
                // First launch: only the reference person. No sample data unless the user asks.
                val raghul = Person(FamilyEditor.newId(), "Raghul", Gender.MALE, createdAt = System.currentTimeMillis())
                val created = (FamilyEditor.addPerson(FamilyData(), raghul, makeRoot = true) as EditResult.Ok).data
                persist(FamilyData(), created)
                _data.value = created
            } else _data.value = d
            photos.cleanupOrphans(_data.value.persons.values.mapNotNull { it.photoFile }.toSet())
            _loaded.value = true
        }
    }

    /** Applies [block] atomically. On success the change is stored and an undo snapshot is kept. */
    suspend fun mutate(block: (FamilyData) -> EditResult): EditResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = _data.value
            val r = block(before)
            if (r is EditResult.Ok && r.data !== before) {
                persist(before, r.data)
                undoStack.addLast(before); if (undoStack.size > 30) undoStack.removeFirst()
                _data.value = r.data
            }
            r
        }
    }

    suspend fun undo(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prev = undoStack.removeLastOrNull() ?: return@withLock false
            persist(_data.value, prev)
            _data.value = prev
            true
        }
    }

    private suspend fun persist(old: FamilyData, new: FamilyData) {
        db.withTransaction {
            val delLinks = (old.links.keys - new.links.keys).toList()
            val delMarriages = (old.marriages.keys - new.marriages.keys).toList()
            val delPersons = (old.persons.keys - new.persons.keys).toList()
            delLinks.chunked(400).forEach { dao.deleteLinks(it) }
            delMarriages.chunked(400).forEach { dao.deleteMarriages(it) }
            delPersons.chunked(400).forEach { dao.deletePersons(it) }
            val persons = new.persons.values.filter { old.persons[it.id] != it }
            if (persons.isNotEmpty()) dao.upsertPersons(persons.map { it.toEntity() })
            val marriages = new.marriages.values.filter { old.marriages[it.id] != it }
            if (marriages.isNotEmpty()) dao.upsertMarriages(marriages.map { it.toEntity() })
            val links = new.links.values.filter { old.links[it.id] != it }
            if (links.isNotEmpty()) dao.upsertLinks(links.map { it.toEntity() })
            if (old.rootId != new.rootId && new.rootId != null) dao.upsertSetting(SettingEntity(SettingKeys.ROOT, new.rootId))
        }
    }

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.upsertSetting(SettingEntity(key, value)); _settings.value = _settings.value + (key to value)
    }

    suspend fun setOverride(o: TermOverride) = withContext(Dispatchers.IO) {
        if (o.text.isBlank()) { dao.deleteOverride(o.code, o.language.name); _overrides.value = _overrides.value.filterNot { it.code == o.code && it.language == o.language } }
        else { dao.upsertOverrides(listOf(o.toEntity())); _overrides.value = _overrides.value.filterNot { it.code == o.code && it.language == o.language } + o }
    }

    // ---------------------------------------------------------------- backup

    suspend fun exportBackup(out: OutputStream, appVersion: String) = withContext(Dispatchers.IO) {
        val snap = BackupSnapshot(_data.value, _overrides.value, _settings.value)
        BackupCodec.write(snap, { photos.read(it) }, out, System.currentTimeMillis(), appVersion)
    }

    /** Parses and validates only. Nothing is changed until [restore] is called. */
    suspend fun readBackup(input: InputStream): ParsedBackup = withContext(Dispatchers.IO) { BackupCodec.read(input) }

    suspend fun restore(parsed: ParsedBackup) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snap = parsed.snapshot
            db.withTransaction {
                dao.clearPersons(); dao.clearOverrides(); dao.clearSettings()
                dao.upsertPersons(snap.data.persons.values.map { it.toEntity() })
                dao.upsertMarriages(snap.data.marriages.values.map { it.toEntity() })
                dao.upsertLinks(snap.data.links.values.map { it.toEntity() })
                dao.upsertOverrides(snap.overrides.map { it.toEntity() })
                (snap.settings + (SettingKeys.ROOT to snap.data.rootId!!)).forEach { (k, v) -> dao.upsertSetting(SettingEntity(k, v)) }
            }
            photos.replaceAll(parsed.photos)
            undoStack.clear()
            _data.value = snap.data
            _overrides.value = snap.overrides
            _settings.value = snap.settings + (SettingKeys.ROOT to snap.data.rootId!!)
        }
    }

    companion object {
        /** Reads the whole database (also used by the background worker). */
        suspend fun readAll(db: AppDatabase): Triple<FamilyData, List<TermOverride>, Map<String, String>> {
            val dao = db.dao()
            val persons = dao.persons().map { it.toDomain() }.associateBy { it.id }
            val links = dao.links().map { it.toDomain() }.associateBy { it.id }
            val marriages = dao.marriages().map { it.toDomain() }.associateBy { it.id }
            val settings = dao.settings().associate { it.key to it.value }
            val rootId = settings[SettingKeys.ROOT]?.takeIf { it in persons } ?: persons.values.minByOrNull { it.createdAt }?.id
            return Triple(FamilyData(persons, links, marriages, rootId), dao.overrides().map { it.toDomain() }, settings)
        }
    }
}
