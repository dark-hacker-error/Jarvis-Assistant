package com.jarvis.assistant.automation.agent

import android.graphics.Rect
import java.util.UUID

enum class TaskStepStatus { PENDING, READY, RUNNING, WAITING_FOR_UI, VERIFYING, COMPLETED, RECOVERING, BLOCKED, FAILED, CANCELLED }

data class TaskPlanStep(
    val stepId: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val status: TaskStepStatus = TaskStepStatus.PENDING,
    val dependencies: List<String> = emptyList(),
    val expectedResult: String = "",
    val actualResult: String? = null,
    val retryCount: Int = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val failureReason: String? = null
)

/** Immutable value rendered by both the floating overlay and the in-app fallback. */
data class AgentTaskSnapshot(
    val taskId: String,
    val originalGoal: String,
    val normalizedGoal: String,
    val currentPlan: String,
    val currentStepId: String?,
    val steps: List<TaskPlanStep>,
    val completedSteps: List<String>,
    val pendingSteps: List<String>,
    val processedItems: Set<String>,
    val currentObservation: String?,
    val lastAction: String?,
    val lastActionResult: String?,
    val currentStatus: String,
    val progressPercentage: Int,
    val retryCount: Int,
    val recoveryCount: Int,
    val lastProgressTimestamp: Long,
    val cancellationRequested: Boolean,
    val finalVerificationResult: String?,
    val failureReason: String?
)

/**
 * Core models for the JARVIS True Agentic Task Engine.
 * Enforces structured planning, execution, verification, and state tracking.
 */

data class TaskGoal(
    val rawGoal: String,
    val isBulkAction: Boolean = false,
    val bulkKeywords: List<String> = emptyList(),
    val maxSteps: Int = 30,
    val requiresVerification: Boolean = true
) {
    companion object {
        private val BULK_INDICATORS = listOf(
            "all", "every", "each", "everyone", "sab", "sabhi", "saare", "saari", "sare", "sari",
            "until none remain", "all liked", "all posts", "all reels", "unlike all",
            "liked reels", "liked posts", "my liked reels", "my liked posts", "meri liked reels", "meri liked posts"
        )

        fun fromUserUtterance(utterance: String): TaskGoal {
            val lower = utterance.lowercase().trim()
            val matchedKeywords = BULK_INDICATORS.filter { keyword ->
                if (keyword.any { it.isWhitespace() }) {
                    lower.contains(keyword)
                } else {
                    Regex("(?<![\\p{L}\\p{N}])${Regex.escape(keyword)}(?![\\p{L}\\p{N}])")
                        .containsMatchIn(lower)
                }
            }
            val isBulk = matchedKeywords.isNotEmpty()
            return TaskGoal(
                rawGoal = utterance,
                isBulkAction = isBulk,
                bulkKeywords = matchedKeywords,
                maxSteps = if (isBulk) 40 else 25
            )
        }
    }
}

enum class AgentTaskStatus {
    IDLE,
    PLANNING,
    OBSERVING,
    EXECUTING,
    VERIFYING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ScreenSignature(
    val packageName: String,
    val className: String,
    val elementCount: Int,
    val contentHash: Long,
    val primaryTexts: List<String> = emptyList()
) {
    fun isEquivalentTo(other: ScreenSignature?): Boolean {
        if (other == null) return false
        if (packageName != other.packageName) return false
        // If contentHash matches or primary texts are identical, it's the same screen
        if (contentHash == other.contentHash) return true
        if (elementCount == other.elementCount && primaryTexts == other.primaryTexts) return true
        return false
    }
}

data class VerificationResult(
    val isVerified: Boolean,
    val reason: String,
    val stateChanged: Boolean = false,
    val actualValue: String? = null,
    val isRetryable: Boolean = false
)

data class ActionExecutionResult(
    val success: Boolean,
    val verified: Boolean,
    val message: String,
    val preSignature: ScreenSignature? = null,
    val postSignature: ScreenSignature? = null,
    val isEndConditionReached: Boolean = false
)

class TaskContext(
    val goal: TaskGoal
) {
    var status: AgentTaskStatus = AgentTaskStatus.IDLE
    var currentStep: Int = 0
    var lastScreenSignature: ScreenSignature? = null
    var consecutiveNoProgressCount: Int = 0
    val processedItemSignatures: MutableSet<String> = mutableSetOf()
    var isCompleted: Boolean = false
    var terminationReason: String? = null

    fun markItemProcessed(itemKey: String) {
        processedItemSignatures.add(itemKey)
    }

    fun isItemProcessed(itemKey: String): Boolean {
        return processedItemSignatures.contains(itemKey)
    }

    fun recordProgress(newSignature: ScreenSignature?) {
        lastScreenSignature = newSignature
        consecutiveNoProgressCount = 0
    }

    fun recordNoProgress() {
        consecutiveNoProgressCount++
    }

    fun isStuck(): Boolean = consecutiveNoProgressCount >= 3
}
