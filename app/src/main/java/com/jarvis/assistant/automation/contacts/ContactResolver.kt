package com.jarvis.assistant.automation.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.min

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"

        private val NICKNAME_MAP = mapOf(
            "mom" to listOf("mom", "mother", "mummy", "maa", "ammi"),
            "dad" to listOf("dad", "father", "papa", "daddy", "abbu"),
            "bro" to listOf("brother", "bro", "bhai"),
            "sis" to listOf("sister", "sis", "didi"),
            "wife" to listOf("wife", "biwi", "darling"),
            "husband" to listOf("husband", "hubby")
        )
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun resolveContact(query: String): ContactResolutionResult {
        if (!hasPermission()) {
            Log.w(TAG, "Cannot resolve contacts: READ_CONTACTS permission not granted")
            return ContactResolutionResult.PermissionDenied
        }

        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank()) {
            return ContactResolutionResult.NoMatch
        }

        val allContacts = loadAllContacts()
        if (allContacts.isEmpty()) {
            return ContactResolutionResult.NoMatch
        }

        // 1. Exact Name Matches (case-insensitive)
        val exactMatches = allContacts.filter { it.name.lowercase(Locale.ROOT) == cleanQuery }
        if (exactMatches.size == 1) {
            return ContactResolutionResult.SingleMatch(exactMatches.first())
        } else if (exactMatches.size > 1) {
            return ContactResolutionResult.MultipleMatches(exactMatches)
        }

        // 2. Nickname resolution
        val nicknameAliases = NICKNAME_MAP[cleanQuery]
        if (nicknameAliases != null) {
            val nicknameMatches = allContacts.filter { contact ->
                val lowerName = contact.name.lowercase(Locale.ROOT)
                nicknameAliases.any { alias -> lowerName.contains(alias) }
            }
            if (nicknameMatches.size == 1) {
                return ContactResolutionResult.SingleMatch(nicknameMatches.first())
            } else if (nicknameMatches.size > 1) {
                return ContactResolutionResult.MultipleMatches(nicknameMatches)
            }
        }

        // 3. Prefix or Word Boundary matches (e.g. "Rahul" matching "Rahul Sharma")
        val wordMatches = allContacts.filter { contact ->
            val lowerName = contact.name.lowercase(Locale.ROOT)
            val tokens = lowerName.split(" ", ".", "_", "-")
            tokens.any { token -> token == cleanQuery || token.startsWith(cleanQuery) }
        }
        if (wordMatches.size == 1) {
            return ContactResolutionResult.SingleMatch(wordMatches.first())
        } else if (wordMatches.size > 1) {
            return ContactResolutionResult.MultipleMatches(wordMatches)
        }

        // 4. Fuzzy Levenshtein matching
        val scored = allContacts.map { contact ->
            val score = calculateSimilarity(cleanQuery, contact.name.lowercase(Locale.ROOT))
            Pair(contact, score)
        }.filter { it.second >= 0.70 }
            .sortedByDescending { it.second }

        return when {
            scored.isEmpty() -> ContactResolutionResult.NoMatch
            scored.size == 1 -> ContactResolutionResult.SingleMatch(scored.first().first)
            scored.first().second - (scored.getOrNull(1)?.second ?: 0f) > 0.15f -> {
                // Clear top winner
                ContactResolutionResult.SingleMatch(scored.first().first)
            }
            else -> {
                ContactResolutionResult.MultipleMatches(scored.take(3).map { it.first })
            }
        }
    }

    private fun loadAllContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val keyIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)

                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    val lookupKey = if (keyIdx >= 0) it.getString(keyIdx) ?: "" else ""

                    val digitsOnly = number.replace(Regex("[^0-9]"), "")
                    val cleanNum = when {
                        digitsOnly.length == 10 -> "91$digitsOnly"
                        digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "91${digitsOnly.substring(1)}"
                        else -> digitsOnly
                    }
                    if (name.isNotBlank() && cleanNum.isNotBlank() && !seenNumbers.contains(cleanNum)) {
                        seenNumbers.add(cleanNum)
                        contacts.add(ContactInfo(id = id, name = name, phoneNumber = cleanNum, lookupKey = lookupKey))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}", e)
        }

        return contacts
    }

    private fun calculateSimilarity(s1: String, s2: String): Float {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0f

        val dist = levenshteinDistance(longer, shorter)
        return (longer.length - dist).toFloat() / longer.length.toFloat()
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1) { 0 }

        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }
}
