package com.jarvis.assistant.data.memory

import android.util.Log
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MemoryManager(
    private val repository: MemoryRepository,
    private val preferences: AppPreferences,
    private val chatRepository: com.jarvis.assistant.data.repository.ChatRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_SHORT_TERM_TURNS = 30
    }

    // --- Short-Term Memory (Preloaded with persistent history) ---
    private val _shortTermHistory = MutableStateFlow<List<ChatTurn>>(emptyList())
    val shortTermHistory: StateFlow<List<ChatTurn>> = _shortTermHistory.asStateFlow()
    private val shortTermSummary = StringBuilder()

    init {
        // Preload recent persistent chat turns so memory is continuous across app restarts
        scope.launch(Dispatchers.IO) {
            val savedTurns = chatRepository.turns.value
            _shortTermHistory.value = savedTurns.takeLast(MAX_SHORT_TERM_TURNS)
            Log.i(TAG, "Preloaded ${_shortTermHistory.value.size} persistent turns into active memory.")
        }
    }

    fun addShortTermTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val current = _shortTermHistory.value.toMutableList()
        val turn = ChatTurn(
            userTranscript = cleanUser,
            jarvisResponse = cleanJarvis
        )
        current.add(turn)

        if (current.size > MAX_SHORT_TERM_TURNS) {
            val pruned = current.removeAt(0)
            if (shortTermSummary.length < 800) {
                shortTermSummary.append("${pruned.userTranscript} -> ${pruned.jarvisResponse}; ")
            }
        }
        _shortTermHistory.value = current

        // Trigger automatic semantic extraction on user input
        learnPreferencesFromConversation(cleanUser)
    }

    fun clearShortTermMemory() {
        _shortTermHistory.value = emptyList()
        shortTermSummary.clear()
    }

    fun getShortTermContextSummary(): String {
        return shortTermSummary.toString().trim()
    }

    // --- Long-Term Memory (Persistent in SQLite Room Database) ---

    suspend fun rememberFact(rawInput: String): String {
        val clean = rawInput
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(remember that|remember to|remember|yaad rakhna ki|yaad rakhna)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "I didn't catch what you'd like me to remember, Sir."
        }

        learnPreferencesFromConversation(clean)

        val lower = clean.lowercase(Locale.ROOT)
        val category = when {
            lower.contains("favorite") || lower.contains("prefer") || lower.contains("like") || lower.contains("song") || lower.contains("music") || lower.contains("app") || lower.contains("pasand") ->
                MemoryEntity.CATEGORY_PREFERENCES
            lower.contains("routine") || lower.contains("gym") || lower.contains("work") || lower.contains("wake up") || lower.contains("sleep") || lower.contains("at ") || lower.contains("pm") || lower.contains("am") ->
                MemoryEntity.CATEGORY_ROUTINES
            lower.contains("name") || lower.contains("birthday") || lower.contains("live in") || lower.contains("age") || lower.contains("i am") || lower.contains("my ") || lower.contains("mera") || lower.contains("meri") ->
                MemoryEntity.CATEGORY_PERSONAL
            lower.contains("call") || lower.contains("contact") || lower.contains("phone") || lower.contains("number") || lower.contains("friend") || lower.contains("brother") || lower.contains("sister") || lower.contains("mom") || lower.contains("dad") || lower.contains("bhai") || lower.contains("dost") ->
                MemoryEntity.CATEGORY_CONTACTS
            else ->
                MemoryEntity.CATEGORY_NOTES
        }

        val key = clean.take(40).trim()
        repository.saveMemory(
            rawText = clean,
            category = category,
            key = key,
            value = clean
        )
        Log.i(TAG, "Saved long-term memory in category '$category': $clean")
        val response = "Sir, I have permanently committed that to memory: $clean."
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun forgetFact(query: String): String {
        val clean = query
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(forget that|forget my|forget about|forget|bhool jao)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "Please tell me what you would like me to forget, Sir."
        }

        val deletedCount = repository.deleteMemoriesByKeyword(clean)
        val response = if (deletedCount > 0) {
            "Sir, I have erased $clean from my persistent memory."
        } else {
            "I couldn't find any memory matching '$clean'."
        }
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun getWhatDoYouRemember(): String {
        val all = repository.getAllMemories()
        if (all.isEmpty()) {
            return "My memory core is currently clear, Sir. Tell me anything about your life, habits, or preferences and I will never forget it."
        }

        val count = all.size
        val topFacts = all.take(6).joinToString(". ") { it.rawText }
        val response = "Sir, I remember $count key facts about you in my Memory Core, including: $topFacts."
        chatRepository.addTurn("What do you remember about me?", response)
        return response
    }

    // --- Direct Recall Responses ---

    suspend fun getBirthdayResponse(): String {
        val bday = preferences.userBirthday
        if (bday.isNotBlank()) {
            val reply = "Sir, your birthday is $bday."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val memories = repository.searchMemories("birthday")
        val mem = memories.firstOrNull()
        if (mem != null) {
            preferences.userBirthday = mem.value
            val reply = "Sir, your birthday is ${mem.value}."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val notSet = "Sir, you haven't told me your birthday yet. You can tell me 'My birthday is 15th August' and I will save it."
        chatRepository.addTurn("When is my birthday?", notSet)
        return notSet
    }

    fun getFavoriteSongResponse(): String {
        val song = resolveSongQuery("my favorite song")
        val reply = "Sir, your favorite song is $song."
        chatRepository.addTurn("What is my favorite song?", reply)
        return reply
    }

    suspend fun getRoutineResponse(): String {
        val allMemories = repository.getAllMemories()
        val routines = allMemories.filter { it.category == MemoryEntity.CATEGORY_ROUTINES }
        if (routines.isNotEmpty()) {
            val summary = routines.joinToString("; ") { it.rawText }
            val reply = "Sir, here is your recorded daily routine: $summary."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            val reply = "Sir, your daily routine is: ${preferences.dailyRoutine}."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        val notSet = "Sir, you haven't recorded your routine yet. Tell me 'I wake up at 7 am' or 'I go to the gym at 6 pm' and I will save it."
        chatRepository.addTurn("What is my routine?", notSet)
        return notSet
    }

    // --- Automatic Habit & Preference Learning ---

    fun trackSongPlay(rawQuery: String) {
        val clean = rawQuery
            .replace(Regex("(?i)\\s+(song|video|gana|track|music|audio)$"), "")
            .replace(Regex("(?i)^(play|chalao|bajao)\\s+"), "")
            .trim()

        if (clean.isBlank() || clean.equals("my favorite song", ignoreCase = true) || clean.equals("favorite song", ignoreCase = true)) return

        val currentFav = preferences.recordSongPlay(clean)
        Log.i(TAG, "Tracked song play: '$clean'. Current top favorite: '$currentFav'")

        scope.launch(Dispatchers.IO) {
            repository.saveMemory(
                rawText = "User played and likes the song $clean",
                category = MemoryEntity.CATEGORY_PREFERENCES,
                key = "song_$clean",
                value = clean
            )
            if (currentFav.equals(clean, ignoreCase = true)) {
                repository.deleteMemoriesByKeyword("favorite_song")
                repository.saveMemory(
                    rawText = "User's favorite song is $clean",
                    category = MemoryEntity.CATEGORY_PREFERENCES,
                    key = "favorite_song",
                    value = clean
                )
            }
        }
    }

    fun resolveSongQuery(rawQuery: String): String {
        val clean = rawQuery.lowercase(Locale.ROOT).trim()
        val isFavoriteQuery = clean == "my favorite song" || clean == "my favourite song" ||
                clean == "favorite song" || clean == "favourite song" ||
                clean == "my song" || clean == "my favorite" || clean == "my favourite" ||
                clean == "something i like" || clean == "a song i like" ||
                clean == "mera favorite gana" || clean == "mera favourite gana" ||
                clean == "mera favorite song" || clean == "mera favourite song" ||
                clean == "mera pasandida gana" || clean == "pasandida gana"

        if (!isFavoriteQuery) {
            return rawQuery
        }

        val prefFav = preferences.favoriteSong
        if (prefFav.isNotBlank()) {
            return prefFav
        }

        val mostPlayed = preferences.getSongPlayCounts().maxByOrNull { it.value }?.key
        if (!mostPlayed.isNullOrBlank()) {
            return mostPlayed
        }

        return "Tadipaar"
    }

    /**
     * Expanded Semantic Learning Engine: Extracts personal facts, preferences, routines,
     * relationships, and knowledge from spoken user conversation in English and Hinglish.
     * Everything learned is immediately committed to Room Database (MemoryRepository).
     */
    fun learnPreferencesFromConversation(userSpeech: String) {
        val clean = userSpeech.trim()
        if (clean.isBlank() || clean.length < 3) return

        scope.launch(Dispatchers.IO) {
            val lower = clean.lowercase(Locale.ROOT)

            // 1. EXPLICIT "REMEMBER THAT" / "YAAD RAKHNA" STATEMENTS
            val rememberMatch = Regex("(?i).*(?:remember\\s+that|remember\\s+to|remember|yaad\\s+rakhna\\s+ki|yaad\\s+rakhna)\\s+(.+)$").find(clean)
            if (rememberMatch != null) {
                val fact = rememberMatch.groupValues[1].trim()
                if (fact.length > 3) {
                    repository.saveMemory(
                        rawText = fact,
                        category = MemoryEntity.CATEGORY_NOTES,
                        key = fact.take(30),
                        value = fact
                    )
                    Log.i(TAG, "Learned explicit fact: $fact")
                }
            }

            // 2. USER NAME & IDENTITY
            val nameMatch = Regex("(?i).*\\b(?:my\\s+name\\s+is|call\\s+me|i\\s+am\\s+called|mera\\s+naam\\s+hai|mera\\s+naam)\\s+([a-zA-Z]+)(?:[.,!]|\\s+\$|\\s+hai)?").find(clean)
            if (nameMatch != null) {
                val name = nameMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (name.length > 1 && !listOf("jarvis", "sir", "boss", "you", "who", "not").contains(name.lowercase(Locale.ROOT))) {
                    preferences.userName = name
                    repository.saveMemory(
                        rawText = "User's preferred name is $name",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_name",
                        value = name
                    )
                    Log.i(TAG, "Learned user name: $name")
                }
            }

            // 3. BIRTHDAY EXTRACTION (English & Hinglish)
            val bdayMatch = Regex("(?i).*\\b(?:my\\s+birthday\\s+is|born\\s+on|my\\s+bday\\s+is)\\s+(?:on\\s+)?([a-zA-Z0-9\\s,]+?)(?:[.,!]|\$|\\s+sir|\\s+jarvis)").find(clean)
                ?: Regex("(?i).*\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+(?:hai\\s+)?([a-zA-Z0-9\\s]+?)(?:\\s+ko\\s+hai|\\s+ko|\\s+hai)?\$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9\\s]+?)\\s+(?:ko\\s+mera\\s+birthday\\s+hai|mera\\s+birthday\\s+hai).*").find(clean)

            if (bdayMatch != null) {
                var bday = bdayMatch.groupValues[1].trim()
                bday = bday.replace(Regex("(?i)^(on|ko|hai)\\s+"), "")
                    .replace(Regex("(?i)\\s+(ko|hai|sir|jarvis|please)$"), "")
                    .trim()
                if (bday.isNotBlank() && bday.length >= 3 && !listOf("sir", "jarvis", "today", "tomorrow", "when").contains(bday.lowercase(Locale.ROOT))) {
                    preferences.userBirthday = bday
                    repository.deleteMemoriesByKeyword("birthday")
                    repository.saveMemory(
                        rawText = "User's birthday is $bday",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_birthday",
                        value = bday
                    )
                    Log.i(TAG, "Learned user's birthday: $bday")
                }
            }

            // 4. USER LOCATION / RESIDENCE
            val locMatch = Regex("(?i).*\\b(?:i\\s+live\\s+in|i\\s+stay\\s+in|my\\s+hometown\\s+is|my\\s+city\\s+is|main\\s+rehta\\s+hoon|mera\\s+ghar\\s+hai)\\s+([a-zA-Z\\s]+?)(?:[.,!]|\$|\\s+me|\\s+mein)").find(clean)
            if (locMatch != null) {
                val loc = locMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (loc.isNotBlank() && loc.length > 2 && !listOf("here", "there", "home", "house").contains(loc.lowercase(Locale.ROOT))) {
                    preferences.userLocation = loc
                    repository.saveMemory(
                        rawText = "User lives in $loc",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_location",
                        value = loc
                    )
                    Log.i(TAG, "Learned user location: $loc")
                }
            }

            // 5. USER JOB / PROFESSION
            val jobMatch = Regex("(?i).*\\b(?:i\\s+work\\s+as\\s+(?:a\\s+)?|i\\s+am\\s+a\\s+|my\\s+job\\s+is\\s+(?:a\\s+)?|mera\\s+kaam\\s+hai\\s+)([a-zA-Z\\s]+?)(?:[.,!]|\$|\\s+engineer|\\s+developer|\\s+doctor)").find(clean)
            if (jobMatch != null) {
                val job = jobMatch.groupValues[1].trim()
                if (job.isNotBlank() && job.length > 2 && !listOf("human", "person", "user", "good", "bad").contains(job.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "User's profession/job is $job",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_profession",
                        value = job
                    )
                    Log.i(TAG, "Learned user profession: $job")
                }
            }

            // 6. FAVORITE SONG LEARNING
            val favSongMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+song\\s+is|i\\s+love\\s+the\\s+song|mera\\s+favou?rite\\s+gana\\s+hai|mera\\s+pasandida\\s+gana)\\s+([a-zA-Z0-9\\s]+?)(?:[.,!]|\$|\\s+by|\\s+hai)").find(clean)
            if (favSongMatch != null) {
                val song = favSongMatch.groupValues[1].trim()
                if (song.isNotBlank() && song.length > 2) {
                    preferences.favoriteSong = song
                    repository.deleteMemoriesByKeyword("favorite_song")
                    repository.saveMemory(
                        rawText = "User's favorite song is $song",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_song",
                        value = song
                    )
                    Log.i(TAG, "Learned user's favorite song: $song")
                }
            }

            // 7. FAVORITE APP LEARNING
            val appMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+app\\s+is|i\\s+use\\s+)([a-zA-Z0-9\\s]+?)(?:\\s+the\\s+most|\\s+app)?(?:[.,!]|\$)?$").find(clean)
            if (appMatch != null) {
                val app = appMatch.groupValues[1].trim()
                if (app.isNotBlank() && app.length > 2 && !listOf("this", "phone", "internet", "device").contains(app.lowercase(Locale.ROOT))) {
                    preferences.favoriteApp = app
                    repository.saveMemory(
                        rawText = "User's favorite app is $app",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_app",
                        value = app
                    )
                    Log.i(TAG, "Learned user's favorite app: $app")
                }
            }

            // 8. PERSONAL RELATIONSHIPS / CONTACTS
            val contactMatch = Regex("(?i).*\\b([a-zA-Z0-9]+)\\s+is\\s+my\\s+(brother|sister|friend|best\\s+friend|mom|dad|father|mother|cousin|boss|wife|husband)[.,!]?$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9]+)\\s+mera\\s+(bhai|dost|yaar|papa|mummy)[.,!]?$").find(clean)
            if (contactMatch != null) {
                val name = contactMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                val relation = contactMatch.groupValues[2].trim()
                if (name.isNotBlank() && name.length > 1 && !listOf("who", "he", "she", "this", "that", "it").contains(name.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "$name is user's $relation",
                        category = MemoryEntity.CATEGORY_CONTACTS,
                        key = "relation_$name",
                        value = relation
                    )
                    Log.i(TAG, "Learned personal relationship: $name is $relation")
                }
            }

            // 9. GENERAL PREFERENCES (e.g. food, vehicles, colors, hobbies)
            val prefMatch = Regex("(?i).*\\b(?:i\\s+love\\s+|i\\s+like\\s+|my\\s+favou?rite\\s+)([a-zA-Z0-9\\s]+?)\\s+(?:is|are)\\s+([a-zA-Z0-9\\s]+?)[.,!]?$").find(clean)
            if (prefMatch != null) {
                val topic = prefMatch.groupValues[1].trim()
                val prefValue = prefMatch.groupValues[2].trim()
                if (topic.isNotBlank() && prefValue.isNotBlank() && topic.length < 25 && prefValue.length < 40) {
                    repository.saveMemory(
                        rawText = "User's favorite $topic is $prefValue",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "pref_${topic.take(20)}",
                        value = prefValue
                    )
                    Log.i(TAG, "Learned general preference: $topic -> $prefValue")
                }
            }
        }
    }

    /**
     * Builds the complete prompt context for Gemini Live.
     * Contains:
     * 1. Core Profile
     * 2. All categorized Room database memories (persistent across restarts)
     * 3. Recent 15 conversation turns across sessions (persistent dialogue memory)
     */
    suspend fun getSystemPromptContext(): String {
        val all = repository.getAllMemories()
        val favSong = resolveSongQuery("my favorite song")
        val sb = StringBuilder("\n[USER PERSONAL PROFILE & MEMORY CORE - PERMANENT STORAGE]:\n")
        sb.append("- User's Preferred Name: ${preferences.userName.ifBlank { "Sir" }}\n")
        sb.append("- User's Birthday: ${preferences.userBirthday.ifBlank { "Not specified yet" }}\n")
        sb.append("- User's Known Favorite Song: $favSong\n")
        sb.append("- User's Favorite App: ${preferences.favoriteApp.ifBlank { "YouTube" }}\n")
        if (preferences.userLocation.isNotBlank()) {
            sb.append("- User's Location: ${preferences.userLocation}\n")
        }
        if (preferences.userAge.isNotBlank()) {
            sb.append("- User's Age: ${preferences.userAge} years old\n")
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            sb.append("- User's Daily Routine: ${preferences.dailyRoutine}\n")
        }

        if (all.isNotEmpty()) {
            sb.append("\n[ALL PERMANENT MEMORIES FROM DATABASE]:\n")
            all.take(40).forEach { mem ->
                sb.append("- [${mem.category}] ${mem.rawText}\n")
            }
        }

        // Persistent Dialogue History across App Restarts
        val recentTurns = chatRepository.turns.value.takeLast(15)
        if (recentTurns.isNotEmpty()) {
            sb.append("\n[PERSISTENT DIALOGUE HISTORY ACROSS APP RESTARTS]:\n")
            sb.append("You remember what was discussed in previous conversations with the user:\n")
            recentTurns.forEach { turn ->
                val u = turn.userTranscript.trim()
                val j = turn.jarvisResponse.trim()
                if (u.isNotBlank() && j.isNotBlank() && u != "(Audio query)" && j != "(Audio response)") {
                    sb.append("User: \"$u\"\n")
                    sb.append("JARVIS: \"$j\"\n")
                }
            }
        }

        return sb.toString()
    }
}
