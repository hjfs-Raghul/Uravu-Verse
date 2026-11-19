package com.infinitefamily.data

import androidx.room.*
import com.infinitefamily.domain.*

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val gender: String,
    val dob: String?,
    val deceased: Boolean,
    val deathDate: String?,
    val occupation: String,
    val location: String,
    val notes: String,
    val photoFile: String?,
    val relativeAge: String,
    val birthOrder: Double?,
    val stickerVariant: Int,
    val stickerCategory: String?,
    val isSample: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "parent_links",
    foreignKeys = [
        ForeignKey(PersonEntity::class, ["id"], ["parentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, ["id"], ["childId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("parentId"), Index("childId"), Index(value = ["parentId", "childId"], unique = true)],
)
data class ParentLinkEntity(@PrimaryKey val id: String, val parentId: String, val childId: String, val type: String)

@Entity(
    tableName = "marriages",
    foreignKeys = [
        ForeignKey(PersonEntity::class, ["id"], ["personAId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, ["id"], ["personBId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("personAId"), Index("personBId")],
)
data class MarriageEntity(
    @PrimaryKey val id: String,
    val personAId: String,
    val personBId: String,
    val status: String,
    val startDate: String?,
    val endDate: String?,
    val sortOrder: Int,
    val note: String,
)

@Entity(tableName = "term_overrides", primaryKeys = ["code", "language"])
data class TermOverrideEntity(val code: String, val language: String, val text: String)

@Entity(tableName = "app_settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

fun Person.toEntity() = PersonEntity(id, name, gender.name, dob, deceased, deathDate, occupation, location, notes,
    photoFile, relativeAge.name, birthOrder, stickerVariant, stickerCategory, isSample, createdAt)

fun PersonEntity.toDomain() = Person(id, name, runCatching { Gender.valueOf(gender) }.getOrDefault(Gender.OTHER), dob, deceased, deathDate,
    occupation, location, notes, photoFile, runCatching { RelativeAge.valueOf(relativeAge) }.getOrDefault(RelativeAge.UNKNOWN),
    birthOrder, stickerVariant, stickerCategory, isSample, createdAt)

fun ParentLink.toEntity() = ParentLinkEntity(id, parentId, childId, type.name)
fun ParentLinkEntity.toDomain() = ParentLink(id, parentId, childId, runCatching { LinkType.valueOf(type) }.getOrDefault(LinkType.BIOLOGICAL))
fun Marriage.toEntity() = MarriageEntity(id, personAId, personBId, status.name, startDate, endDate, order, note)
fun MarriageEntity.toDomain() = Marriage(id, personAId, personBId, runCatching { MarriageStatus.valueOf(status) }.getOrDefault(MarriageStatus.PREVIOUS), startDate, endDate, sortOrder, note)
fun TermOverride.toEntity() = TermOverrideEntity(code, language.name, text)
fun TermOverrideEntity.toDomain() = TermOverride(code, Language.valueOf(language), text)
