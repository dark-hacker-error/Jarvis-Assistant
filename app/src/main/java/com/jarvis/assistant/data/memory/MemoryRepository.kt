package com.jarvis.assistant.data.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = ""
    ): Long {
        val calculatedKey = key.ifBlank {
            rawText.take(30).trim()
        }
        val calculatedValue = value.ifBlank { rawText }

        val entity = MemoryEntity(
            category = category,
            key = calculatedKey,
            value = calculatedValue,
            rawText = rawText
        )
        return memoryDao.insertMemory(entity)
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteMemoriesByKeyword(keyword: String): Int {
        return memoryDao.deleteMemoriesByKeyword(keyword)
    }

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
    }
}
