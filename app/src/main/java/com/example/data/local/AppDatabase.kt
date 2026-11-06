package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    companion object {
        /**
         * Uygulama güncellendiğinde kullanıcının yer imleri (`bookmarked_entries`) ve
         * okuma geçmişi (`topic_read_states`) tablolarını silmeden koruyan v1 -> v2 geçişi.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `topic_read_states` (
                        `topic_key` TEXT NOT NULL,
                        `topic_id` TEXT NOT NULL,
                        `topic_title` TEXT NOT NULL,
                        `topic_slug` TEXT NOT NULL,
                        `topic_url` TEXT NOT NULL,
                        `entry_id` TEXT NOT NULL,
                        `page_number` INTEGER NOT NULL,
                        `scroll_index` INTEGER NOT NULL,
                        `scroll_offset` INTEGER NOT NULL,
                        `total_known_pages` INTEGER NOT NULL,
                        `updated_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`topic_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_topic_read_states_topic_id` ON `topic_read_states` (`topic_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_topic_read_states_updated_at_ms` ON `topic_read_states` (`updated_at_ms`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarked_entries` (
                        `entryId` TEXT NOT NULL,
                        `topicTitle` TEXT NOT NULL,
                        `topicUrl` TEXT NOT NULL,
                        `contentHtml` TEXT NOT NULL,
                        `contentText` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `dateText` TEXT NOT NULL,
                        `favoriteCount` INTEGER NOT NULL,
                        `savedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`entryId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
