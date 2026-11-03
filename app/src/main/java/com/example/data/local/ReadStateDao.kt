package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Başlık okuma durumlarını ([TopicReadStateEntity]) yöneten Room DAO arayüzü.
 */
@Dao
interface ReadStateDao {

    /**
     * Bir başlığın son okunan `entryId`, `pageNumber`, `scrollIndex` ve `scrollOffset`
     * bilgilerini kaydeder veya günceller.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadState(state: TopicReadStateEntity)

    /**
     * Kanonik `topicKey` (temiz URL) üzerinden kaydedilmiş son okuma durumunu tek seferlik getirir.
     * Başlığa tekrar girildiğinde doğrudan kalınan sayfadan (`pageNumber`) ve `entryId`/`scrollOffset`
     * konumundan devam etmek için kullanılır.
     */
    @Query("SELECT * FROM topic_read_states WHERE topic_key = :topicKey LIMIT 1")
    suspend fun getReadStateByKey(topicKey: String): TopicReadStateEntity?

    /**
     * Eğer URL slug'ı değişmiş ancak `topicId` aynı kalmışsa `topicId` üzerinden yedek sorgulama yapar.
     */
    @Query("SELECT * FROM topic_read_states WHERE topic_id = :topicId AND topic_id != '' ORDER BY updated_at_ms DESC LIMIT 1")
    suspend fun getReadStateByTopicId(topicId: String): TopicReadStateEntity?

    /**
     * Belirli bir başlığın okuma durumunu reaktif [Flow] olarak gözlemler.
     */
    @Query("SELECT * FROM topic_read_states WHERE topic_key = :topicKey LIMIT 1")
    fun observeReadStateByKey(topicKey: String): Flow<TopicReadStateEntity?>

    /**
     * En son okunan tüm başlıkları güncellenme tarihine göre sıralı olarak döndürür.
     */
    @Query("SELECT * FROM topic_read_states ORDER BY updated_at_ms DESC LIMIT 100")
    fun observeAllReadStates(): Flow<List<TopicReadStateEntity>>

    /**
     * Belirli bir başlığın okuma kaydını siler.
     */
    @Query("DELETE FROM topic_read_states WHERE topic_key = :topicKey")
    suspend fun deleteReadState(topicKey: String)

    /**
     * Tüm okuma geçmişini temizler.
     */
    @Query("DELETE FROM topic_read_states")
    suspend fun clearAllReadStates()
}
