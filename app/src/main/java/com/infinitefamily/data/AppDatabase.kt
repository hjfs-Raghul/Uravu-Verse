package com.infinitefamily.data

import android.content.Context
import androidx.room.*

@Dao
interface FamilyDao {
    @Query("SELECT * FROM persons") suspend fun persons(): List<PersonEntity>
    @Query("SELECT * FROM parent_links") suspend fun links(): List<ParentLinkEntity>
    @Query("SELECT * FROM marriages") suspend fun marriages(): List<MarriageEntity>
    @Query("SELECT * FROM term_overrides") suspend fun overrides(): List<TermOverrideEntity>
    @Query("SELECT * FROM app_settings") suspend fun settings(): List<SettingEntity>

    @Upsert suspend fun upsertPersons(items: List<PersonEntity>)
    @Upsert suspend fun upsertLinks(items: List<ParentLinkEntity>)
    @Upsert suspend fun upsertMarriages(items: List<MarriageEntity>)
    @Upsert suspend fun upsertOverrides(items: List<TermOverrideEntity>)
    @Upsert suspend fun upsertSetting(item: SettingEntity)

    @Query("DELETE FROM persons WHERE id IN (:ids)") suspend fun deletePersons(ids: List<String>)
    @Query("DELETE FROM parent_links WHERE id IN (:ids)") suspend fun deleteLinks(ids: List<String>)
    @Query("DELETE FROM marriages WHERE id IN (:ids)") suspend fun deleteMarriages(ids: List<String>)
    @Query("DELETE FROM term_overrides WHERE code = :code AND language = :language") suspend fun deleteOverride(code: String, language: String)

    // Used by restore. Deleting persons cascades to links and marriages.
    @Query("DELETE FROM persons") suspend fun clearPersons()
    @Query("DELETE FROM term_overrides") suspend fun clearOverrides()
    @Query("DELETE FROM app_settings") suspend fun clearSettings()
}

@Database(
    entities = [PersonEntity::class, ParentLinkEntity::class, MarriageEntity::class, TermOverrideEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FamilyDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "family_tree.db")
                .build().also { instance = it }
        }
    }
}
