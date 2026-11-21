package com.infinitefamily.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinitefamily.data.AppDatabase
import com.infinitefamily.data.FamilyRepository
import com.infinitefamily.data.PhotoStore
import com.infinitefamily.domain.*
import com.infinitefamily.work.FamilyLocationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiMessage(val text: String, val undoable: Boolean = false)

sealed class RestoreState {
    object Idle : RestoreState()
    data class Ready(val parsed: ParsedBackup) : RestoreState()
    data class Failed(val reason: String) : RestoreState()
}

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    val repo = FamilyRepository(AppDatabase.get(app), PhotoStore(app))

    val data: StateFlow<FamilyData> = repo.data
    val overrides: StateFlow<List<TermOverride>> = repo.overrides
    val settings: StateFlow<Map<String, String>> = repo.settings
    val ready: StateFlow<Boolean> = repo.loaded

    /** Heavy derived values are computed off the main thread whenever the data changes. */
    val engine: StateFlow<RelationshipEngine> =
        combine(repo.data, repo.overrides) { d, o -> RelationshipEngine(d, Terminology(overrides = o)) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, RelationshipEngine(FamilyData()))

    val layout: StateFlow<TreeLayoutResult> =
        repo.data.map { TreeLayout.compute(it) }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TreeLayoutResult(emptyMap()))

    val scene: StateFlow<TreeScene> =
        repo.data.map { TreeScene.build(it) }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, TreeScene(emptyList(), emptyList(), emptyList(), emptyList()))

    val terminology: StateFlow<Terminology> =
        repo.overrides.map { Terminology(overrides = it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Terminology())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages
    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore

    init { viewModelScope.launch { repo.load() } }

    fun say(text: String, undoable: Boolean = false) { _messages.tryEmit(UiMessage(text, undoable)) }

    /**
     * Runs a change through the pure editor and stores it in one transaction.
     * Failures (cycles, duplicates, ...) are explained to the user and nothing is changed.
     */
    fun edit(success: String? = null, undoable: Boolean = false, onDone: ((EditResult) -> Unit)? = null, block: (FamilyData) -> EditResult) {
        viewModelScope.launch {
            val r = repo.mutate(block)
            when (r) {
                is EditResult.Ok -> if (success != null) say(success, undoable)
                is EditResult.Fail -> say(r.message)
            }
            onDone?.invoke(r)
        }
    }

    fun undo() { viewModelScope.launch { if (repo.undo()) say("Undone") } }

    suspend fun importPhoto(uri: Uri): String? = withContext(Dispatchers.IO) { repo.photos.importFromUri(uri) }

    // ------------------------------------------------------------- settings

    fun setNotify(enabled: Boolean) {
        viewModelScope.launch {
            repo.setSetting(SettingKeys.NOTIFY, if (enabled) "1" else "0")
            val ctx = getApplication<Application>()
            if (enabled) FamilyLocationWorker.schedule(ctx) else FamilyLocationWorker.cancel(ctx)
        }
    }

    /** Remembers where a person was dragged to on the sheet. */
    fun moveNode(id: String, x: Float, y: Float) {
        viewModelScope.launch {
            val m = LinkedHashMap(ManualLayout.decode(repo.settings.value[ManualLayout.KEY]))
            m[id] = x to y
            repo.setSetting(ManualLayout.KEY, ManualLayout.encode(m))
        }
    }

    /** Forgets all dragged positions and goes back to the automatic arrangement. */
    fun resetLayout() { viewModelScope.launch { repo.setSetting(ManualLayout.KEY, "") } }

    fun setHomeCity(city: String) { viewModelScope.launch { repo.setSetting(SettingKeys.HOME_CITY, city.trim()) } }

    fun setOverride(code: String, lang: Language, text: String) { viewModelScope.launch { repo.setOverride(TermOverride(code, lang, text.trim())) } }

    fun loadSample() = edit("Sample family added. You can remove it from Settings.", undoable = true) { SampleFamily.apply(it) }
    fun removeSample() = edit("Sample family removed.", undoable = true) { SampleFamily.remove(it) }

    // --------------------------------------------------------------- backup

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "w")!!.use { repo.exportBackup(it, "1.0.0") }
                }
            }.onSuccess { say("Backup saved.") }.onFailure { say("Backup failed: ${it.message}") }
        }
    }

    fun prepareRestore(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _restore.value = try {
                val parsed = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { repo.readBackup(it) } }
                RestoreState.Ready(parsed)
            } catch (e: BackupException) { RestoreState.Failed(e.message ?: "Invalid backup.") }
            catch (e: Exception) { RestoreState.Failed("Could not read the file: ${e.message}") }
        }
    }

    fun confirmRestore() {
        val s = _restore.value as? RestoreState.Ready ?: return
        viewModelScope.launch {
            runCatching { repo.restore(s.parsed) }
                .onSuccess { say("Restored ${s.parsed.snapshot.data.persons.size} people.") }
                .onFailure { say("Restore failed. Your existing data was not changed: ${it.message}") }
            _restore.value = RestoreState.Idle
        }
    }

    fun dismissRestore() { _restore.value = RestoreState.Idle }
}
