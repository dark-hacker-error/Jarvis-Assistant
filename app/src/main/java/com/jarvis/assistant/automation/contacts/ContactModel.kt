package com.jarvis.assistant.automation.contacts

data class ContactInfo(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val lookupKey: String = ""
)

sealed class ContactResolutionResult {
    data class SingleMatch(val contact: ContactInfo) : ContactResolutionResult()
    data class MultipleMatches(val candidates: List<ContactInfo>) : ContactResolutionResult()
    object NoMatch : ContactResolutionResult()
    object PermissionDenied : ContactResolutionResult()
}
