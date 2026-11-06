package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarked_entries")
data class BookmarkedEntryEntity(
    @PrimaryKey val entryId: String,
    val topicTitle: String,
    val topicUrl: String,
    val contentHtml: String,
    val contentText: String,
    val author: String,
    val dateText: String,
    val favoriteCount: Int,
    val savedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface EksinDao {
    @Query("SELECT * FROM bookmarked_entries ORDER BY savedAtMs DESC")
    fun observeBookmarkedEntries(): Flow<List<BookmarkedEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmarkedEntry(entry: BookmarkedEntryEntity)

    @Query("DELETE FROM bookmarked_entries WHERE entryId = :entryId")
    suspend fun deleteBookmarkedEntry(entryId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarked_entries WHERE entryId = :entryId)")
    fun isEntryBookmarked(entryId: String): Flow<Boolean>
}

@Database(
    entities = [
        TopicReadStateEntity::class,
        BookmarkedEntryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readStateDao(): ReadStateDao
    abstract fun eksinDao(): EksinDao
}
