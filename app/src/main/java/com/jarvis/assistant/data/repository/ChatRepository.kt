package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.backup.BackupTriggers
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    /** Re-reads persisted history, e.g. after a backup restore. */
    @Synchronized
    fun reloadFromPreferences() {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        BackupTriggers.request("chat-turn")
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
        BackupTriggers.request("chat-clear")
    }

    /**
     * Adds a user message immediately without inventing a placeholder assistant reply.
     * Used by the typed-chat path so the reply can be attached to the same turn later
     * via [completeLastTurn] instead of creating a duplicate "(Audio query)" turn.
     */
    @Synchronized
    fun beginTurn(userText: String): Boolean {
        val cleanUser = userText.trim()
        if (cleanUser.isEmpty()) return false

        val currentList = _turns.value.toMutableList()
        val last = currentList.lastOrNull()
        if (last != null && last.userTranscript == cleanUser && last.jarvisResponse.isBlank()) {
            // The same pending typed message is already on screen.
            return false
        }

        currentList.add(ChatTurn(userTranscript = cleanUser, jarvisResponse = ""))
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        BackupTriggers.request("chat-turn")
        return true
    }

    /**
     * Attaches an assistant reply to the most recent unanswered user turn.
     * Returns false when there is no matching pending turn, so callers can fall
     * back to [addTurn] without ever losing the reply.
     */
    @Synchronized
    fun completeLastTurn(userText: String, jarvisText: String): Boolean {
        val cleanReply = jarvisText.trim()
        if (cleanReply.isEmpty()) return false

        val cleanUser = userText.trim()
        val currentList = _turns.value.toMutableList()
        val index = currentList.indexOfLast { turn ->
            turn.jarvisResponse.isBlank() && (cleanUser.isEmpty() || turn.userTranscript == cleanUser)
        }
        if (index < 0) return false

        currentList[index] = currentList[index].copy(jarvisResponse = cleanReply)
        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        BackupTriggers.request("chat-turn")
        return true
    }

    /**
     * Completes the newest unanswered turn with reply text regardless of whose
     * transcript it holds. This covers server-side input transcription quirks.
     */
    @Synchronized
    fun completeLatestPendingTurn(jarvisText: String): Boolean {
        val cleanReply = jarvisText.trim()
        if (cleanReply.isEmpty()) return false

        val currentList = _turns.value.toMutableList()
        val index = currentList.indexOfLast { it.jarvisResponse.isBlank() }
        if (index < 0) return false

        currentList[index] = currentList[index].copy(jarvisResponse = cleanReply)
        _turns.value = currentList
        preferences.saveChatHistory(currentList)
        BackupTriggers.request("chat-turn")
        return true
    }
}
