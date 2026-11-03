package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bir başlığa girildiğinde en son okunan [entryId], [pageNumber], [scrollIndex] ve [scrollOffset]
 * değerlerini Room veritabanında saklayan Entity.
 *
 * [topicKey] olarak sorgu parametrelerinden arındırılmış kanonik başlık URL'si
 * (örn: `https://eksisozluk.com/eksisozluk--31499`) kullanılır; böylece başlığa
 * Gündem, Bugün, Olaylar (`?a=tracked`) veya Arama sekmelerinin hangisinden girilirse girilsin
 * aynı okuma durumu yakalanır.
 */
@Entity(
    tableName = "topic_read_states",
    indices = [
        Index(value = ["topic_id"]),
        Index(value = ["updated_at_ms"])
    ]
)
data class TopicReadStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "topic_key")
    val topicKey: String,

    @ColumnInfo(name = "topic_id")
    val topicId: String = "",

    @ColumnInfo(name = "topic_title")
    val topicTitle: String,

    @ColumnInfo(name = "topic_slug")
    val topicSlug: String = "",

    @ColumnInfo(name = "topic_url")
    val topicUrl: String,

    @ColumnInfo(name = "entry_id")
    val entryId: String,

    @ColumnInfo(name = "page_number")
    val pageNumber: Int,

    @ColumnInfo(name = "scroll_index")
    val scrollIndex: Int = 0,

    @ColumnInfo(name = "scroll_offset")
    val scrollOffset: Int = 0,

    @ColumnInfo(name = "total_known_pages")
    val totalKnownPages: Int = 1,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)
