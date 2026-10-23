package com.jarvis.assistant.data.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memories WHERE rawText LIKE '%' || :keyword || '%' OR `key` LIKE '%' || :keyword || '%' OR `value` LIKE '%' || :keyword || '%'")
    suspend fun deleteMemoriesByKeyword(keyword: String): Int

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}
