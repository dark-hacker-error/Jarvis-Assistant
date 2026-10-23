package com.jarvis.assistant.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val key: String,
    val value: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val CATEGORY_PERSONAL = "Personal"
        const val CATEGORY_PREFERENCES = "Preferences"
        const val CATEGORY_CONTACTS = "Contacts"
        const val CATEGORY_ROUTINES = "Routines"
        const val CATEGORY_NOTES = "Notes"

        val ALL_CATEGORIES = listOf(
            CATEGORY_PERSONAL,
            CATEGORY_PREFERENCES,
            CATEGORY_CONTACTS,
            CATEGORY_ROUTINES,
            CATEGORY_NOTES
        )
    }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
