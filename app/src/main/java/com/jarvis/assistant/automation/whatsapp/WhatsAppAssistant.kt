package com.jarvis.assistant.automation.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.contacts.ContactInfo
import com.jarvis.assistant.automation.contacts.ContactResolutionResult
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.util.ActivityLauncherHelper
import java.net.URLEncoder

data class PendingWhatsAppDraft(
    val contactName: String,
    val phoneNumber: String?,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

class WhatsAppAssistant(
    private val context: Context,
    private val contactResolver: ContactResolver
) {
    companion object {
        private const val TAG = "WhatsAppAssistant"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }

    private enum class DisambiguationAction { MESSAGE, CALL, DELETE }

    private var pendingDraft: PendingWhatsAppDraft? = null
    private var disambiguationCandidates: List<ContactInfo>? = null
    private var disambiguationAction = DisambiguationAction.MESSAGE
    private var disambiguationMessage = ""
    private var disambiguationVideo = false
    private var disambiguationForEveryone = false

    fun hasPendingDraft(): Boolean = pendingDraft != null

    fun isAwaitingDisambiguation(): Boolean = !disambiguationCandidates.isNullOrEmpty()

    fun createDraft(targetContact: String, messageText: String): String {
        val cleanContact = targetContact.trim()
        val cleanMessage = messageText.trim()

        if (cleanContact.isBlank()) {
            return "Who would you like to message on WhatsApp, sir?"
        }
        if (cleanMessage.isBlank()) {
            return "What message would you like to send to $cleanContact?"
        }

        // A new message request supersedes any older unsent draft. Without this
        // reset, an ambiguous new contact could trigger confirmAndSend() and send
        // the previous draft to the previous person.
        pendingDraft = null
        disambiguationCandidates = null

        val resolution = contactResolver.resolveContact(cleanContact)
        when (resolution) {
            is ContactResolutionResult.SingleMatch -> {
                disambiguationCandidates = null
                val contact = resolution.contact
                pendingDraft = PendingWhatsAppDraft(
                    contactName = contact.name,
                    phoneNumber = contact.phoneNumber,
                    message = cleanMessage
                )
                return "Draft ready for ${contact.name}: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                disambiguationAction = DisambiguationAction.MESSAGE
                disambiguationMessage = cleanMessage
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            is ContactResolutionResult.NoMatch -> {
                // If contact was entered as a raw phone number or not found in contacts
                disambiguationCandidates = null
                val isPhoneNumber = cleanContact.replace(Regex("[^0-9+]"), "").length >= 7
                val phone = if (isPhoneNumber) cleanContact.replace(Regex("[^0-9+]"), "") else null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = phone,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.PermissionDenied -> {
                disambiguationCandidates = null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = null,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact. Note: Contacts permission is needed for automatic phone resolution. Should I send it?"
            }
        }
    }

    fun handleDisambiguationChoice(userChoice: String, pendingMessage: String = ""): String {
        val candidates = disambiguationCandidates ?: return "No contact disambiguation in progress."
        val normalized = userChoice.trim()
        val candidatesList = candidates

        val ordinal = when {
            normalized.equals("1", ignoreCase = true) || normalized.contains("first", ignoreCase = true) -> 0
            normalized.equals("2", ignoreCase = true) || normalized.contains("second", ignoreCase = true) -> 1
            normalized.equals("3", ignoreCase = true) || normalized.contains("third", ignoreCase = true) -> 2
            else -> -1
        }
        val byName = if (normalized.isBlank()) null else candidatesList.find {
            it.name.contains(normalized, ignoreCase = true) || normalized.contains(it.name, ignoreCase = true)
        }
        // Never guess the first contact: sending a private message to the wrong
        // person is worse than asking the user to repeat the choice.
        val chosen = byName ?: candidatesList.getOrNull(ordinal)

        if (chosen == null) {
            disambiguationCandidates = candidatesList
            val names = candidatesList.take(3).mapIndexed { index, contact -> "${index + 1}. ${contact.name}" }
                .joinToString(", ")
            return "I couldn't identify which contact you meant. Please say the name or number: $names."
        }

        val action = disambiguationAction
        val storedMessage = disambiguationMessage
        val video = disambiguationVideo
        val forEveryone = disambiguationForEveryone
        disambiguationCandidates = null
        disambiguationAction = DisambiguationAction.MESSAGE
        disambiguationMessage = ""
        disambiguationVideo = false
        disambiguationForEveryone = false

        // Resume the original operation. Previously every choice created a
        // message draft, so choosing a contact for a call or delete silently
        // changed the requested action.
        return when (action) {
            DisambiguationAction.CALL -> {
                makeCall(chosen.phoneNumber.ifBlank { chosen.name }, video)
            }
            DisambiguationAction.DELETE -> {
                deleteLastMessage(chosen.phoneNumber.ifBlank { chosen.name }, forEveryone)
            }
            DisambiguationAction.MESSAGE -> {
                val msg = storedMessage.ifBlank { pendingMessage }.ifBlank { "Hello" }
                pendingDraft = PendingWhatsAppDraft(
                    contactName = chosen.name,
                    phoneNumber = chosen.phoneNumber,
                    message = msg
                )
                "Draft ready for ${chosen.name}. Should I send it?"
            }
        }
    }

    fun sendMessageDirectly(targetContact: String, messageText: String): String {
        val cleanContact = targetContact.trim()
        val cleanMessage = messageText.trim()
        if (cleanContact.isBlank()) return "Who would you like to message on WhatsApp, sir?"
        if (cleanMessage.isBlank()) return "What message would you like to send to $cleanContact?"

        val draftResult = createDraft(cleanContact, cleanMessage)
        return if (hasPendingDraft()) {
            confirmAndSend()
        } else {
            draftResult
        }
    }

    fun confirmAndSend(): String {
        val draft = pendingDraft ?: return "There is no draft waiting to be sent."
        val message = draft.message
        val phone = draft.phoneNumber

        return try {
            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val rawDigits = phone?.replace(Regex("[^0-9]"), "") ?: ""
            val cleanPhone = when {
                rawDigits.length == 10 -> "91$rawDigits"
                rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
                else -> rawDigits
            }

            val targetPkg = whatsAppPackage()

            val intent = if (cleanPhone.isNotBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage")).apply {
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            // Arm accessibility service BEFORE launching WhatsApp so it's ready to click immediately
            JarvisAccessibilityService.triggerWhatsAppSend(message)

            ActivityLauncherHelper.startActivitySafely(context, intent)

            val recipient = draft.contactName
            pendingDraft = null
            "Sending WhatsApp message to $recipient."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message: ${e.message}", e)
            pendingDraft = null
            "Failed to open WhatsApp. Please ensure WhatsApp is installed."
        }
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkgName, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prefers regular WhatsApp, then WhatsApp Business. Every deep link /
     * launch intent must use the package that is actually installed.
     */
    private fun whatsAppPackage(): String = when {
        isPackageInstalled(WHATSAPP_PACKAGE) -> WHATSAPP_PACKAGE
        isPackageInstalled(WHATSAPP_BUSINESS_PACKAGE) -> WHATSAPP_BUSINESS_PACKAGE
        else -> WHATSAPP_PACKAGE
    }

    fun cancelDraft(): String {
        if (disambiguationCandidates != null) {
            disambiguationCandidates = null
            pendingDraft = null
            disambiguationAction = DisambiguationAction.MESSAGE
            disambiguationMessage = ""
            disambiguationVideo = false
            disambiguationForEveryone = false
            return "Contact selection cancelled. I did not send, call, or delete anything."
        }
        return if (pendingDraft != null) {
            val name = pendingDraft?.contactName
            pendingDraft = null
            "Draft cancelled for $name. I did not send it."
        } else {
            "No active draft to cancel."
        }
    }

    fun makeCall(targetContact: String, isVideo: Boolean): String {
        val cleanContact = targetContact.trim()
        if (cleanContact.isBlank()) {
            return "Who would you like to call on WhatsApp?"
        }

        // A call request supersedes an unsent message draft.
        pendingDraft = null
        disambiguationCandidates = null

        val resolution = contactResolver.resolveContact(cleanContact)
        val contact = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                disambiguationAction = DisambiguationAction.CALL
                disambiguationVideo = isVideo
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        disambiguationCandidates = null
        val callType = if (isVideo) "video call" else "voice call"

        if (contact == null || contact.phoneNumber.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to initiate a WhatsApp $callType."
        }

        val rawDigits = contact.phoneNumber.replace(Regex("[^0-9]"), "")
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            // Arm accessibility service to click top action bar call icon & confirm prompt
            JarvisAccessibilityService.triggerWhatsAppCall(isVideo)

            val mimeType = if (isVideo) {
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            } else {
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            }

            val callDataUri = getWhatsAppCallDataUri(contact.id, mimeType)
            val targetPkg = whatsAppPackage()
            val intent = if (callDataUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(callDataUri, mimeType)
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            ActivityLauncherHelper.startActivitySafely(context, intent)
            "Starting WhatsApp $callType to ${contact.name}."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WhatsApp call: ${e.message}", e)
            "Could not initiate WhatsApp call."
        }
    }

    /**
     * Deletes the most recent message in a WhatsApp chat.
     * - If [targetContact] is given, opens that contact's chat first (via the WhatsApp
     *   deep link, same as sending a message) then arms the accessibility service to
     *   long-press the last bubble and pick the delete option.
     * - If [targetContact] is blank/null, it operates on whichever WhatsApp chat is
     *   already open on screen (e.g. right after sending a message and saying
     *   "delete that message").
     */
    fun deleteLastMessage(targetContact: String?, forEveryone: Boolean): String {
        val cleanContact = targetContact?.trim().orEmpty()
        val callType = if (forEveryone) "for everyone" else "for me"

        if (cleanContact.isBlank()) {
            return try {
                // Arm accessibility BEFORE bringing WhatsApp to the foreground so it's
                // ready to act on whatever chat is currently visible.
                JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
                val targetPkg = whatsAppPackage()
                val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                if (launchIntent != null) {
                    ActivityLauncherHelper.startActivitySafely(context, launchIntent)
                } else {
                    return "WhatsApp is not installed, so I couldn't open the chat to delete the message."
                }
                "Deleting the last message $callType."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete WhatsApp message: ${e.message}", e)
                "Could not open WhatsApp to delete the message."
            }
        }

        // A targeted delete supersedes an unsent message draft.
        pendingDraft = null
        disambiguationCandidates = null

        val resolution = contactResolver.resolveContact(cleanContact)
        val phoneCandidate = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact.phoneNumber
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                disambiguationAction = DisambiguationAction.DELETE
                disambiguationForEveryone = forEveryone
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        disambiguationCandidates = null
        val rawDigits = (phoneCandidate ?: cleanContact).replace(Regex("[^0-9]"), "")
        if (rawDigits.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to open that chat."
        }
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                setPackage(whatsAppPackage())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            "Deleting the last message $callType in the chat with $cleanContact."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open chat to delete WhatsApp message: ${e.message}", e)
            "Could not open the chat with $cleanContact to delete the message."
        }
    }

    @Suppress("SuspiciousIndentation") // Lint false positive on the multiline query arguments below.
    private fun getWhatsAppCallDataUri(contactId: String, mimeType: String): Uri? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, mimeType),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(ContactsContract.Data._ID)
                    if (idIdx >= 0) {
                        val rowId = it.getLong(idIdx)
                        return Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, rowId.toString())
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
