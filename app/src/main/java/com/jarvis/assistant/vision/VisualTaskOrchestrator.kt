package com.jarvis.assistant.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.agent.SerializedActionExecutor
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class TaskActionResult(
    val success: Boolean,
    val message: String,
    val currentStateSummary: String = "",
    val requiresUserConfirmation: Boolean = false,
    val verified: Boolean? = null
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            if (currentStateSummary.isNotBlank()) {
                put("current_state", currentStateSummary)
            }
            if (requiresUserConfirmation) {
                put("requires_confirmation", true)
            }
            verified?.let { put("verified", it) }
        }
    }
}

data class UiWaitResult(
    val ready: Boolean,
    val root: AccessibilityNodeInfo?,
    val elements: List<UiElementInfo>,
    val signature: com.jarvis.assistant.automation.agent.ScreenSignature?,
    val changedFromPrevious: Boolean,
    val reason: String
)

/**
 * Visual Task Orchestrator.
 * Connects Gemini Live's visual tool calls to the Dual-Anchor Grounding Engine
 * and executes precise, closed-loop verified actions on the Android OS.
 */
class VisualTaskOrchestrator(
    private val groundingEngine: UiGroundingEngine = UiGroundingEngine()
) {
    companion object {
        private const val TAG = "VisualTaskOrchestrator"

        @Volatile
        private var instance: VisualTaskOrchestrator? = null

        fun getInstance(): VisualTaskOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: VisualTaskOrchestrator().also { instance = it }
            }
        }
    }

    private val screenCaptureManager = ScreenCaptureManager.getInstance()
    private var consecutiveScrollCount = 0
    private var lastScreenSignature = ""
    private var duplicateSignatureCount = 0
    private var lastClickTimestamp = 0L
    private var lastClickPhysicalX = -1f
    private var lastClickPhysicalY = -1f
    private var lastUnverifiedClickTarget = ""
    private var lastUnverifiedClickScreen: com.jarvis.assistant.automation.agent.ScreenSignature? = null

    private suspend fun mutateUi(name: String, operation: () -> Boolean): Boolean =
        AgentTaskEngine.executeUiMutation(name) { operation() }

    private fun recordObservation(service: JarvisAccessibilityService, summary: String? = null) {
        val root = service.getActiveRootNode()
        AgentTaskEngine.observe(
            com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(root),
            root?.packageName?.toString(), summary
        )
    }

    /** Runs Spotify's search-and-play path locally so the model need not request each step. */
    suspend fun runSpotifySmartSearch(goal: String): TaskActionResult = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not running.", verified = false)
        val root = service.getActiveRootNode()
        if (root?.packageName?.toString()?.contains("spotify", true) != true) {
            return@withContext TaskActionResult(false, "Spotify is not the foreground app.", verified = false)
        }
        val query = goal
            .replace(Regex("(?i)\\b(jarvis|spotify|please|find|search|look for|play|song|track|music|playlist|bajao|chalao|karo|kar do|do|dhundo|dhoondho|dhoondh|talaash|gaana|gana|meri|mera|par|mein|ko|the|a|an|on|in|my)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '.', '!', '?', '"', '\'')
        if (query.length < 2) {
            return@withContext TaskActionResult(false, "Could not identify a song title in the request.", verified = false)
        }
        val task = AgentTaskEngine.begin(goal, "spotify-smart-search")
        AgentTaskEngine.replacePlan(listOf("Understand goal", "Open Spotify", "Search for the requested track", "Select one unique match", "Verify playback"))
        AgentTaskEngine.completeStepByTitle("Open Spotify", "Spotify is confirmed in the foreground", verified = true)
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)

        var elements = groundingEngine.scanScreen(root)
        if (elements.none { it.isEditable }) {
            val search = elements.filter { it.isClickable && !it.isEditable &&
                it.primaryLabel.contains("search", ignoreCase = true) }
            if (search.size != 1) {
                AgentTaskEngine.recordAction("resolve Spotify Search", "Search control is ambiguous or unavailable", false)
                return@withContext TaskActionResult(false, "Spotify Search is not uniquely visible; stopped without guessing.", groundingEngine.formatElementsSummary(elements, root), verified = false)
            }
            val opened = handleClick(search.single().primaryLabel, taskGoal = goal)
            if (!opened.success) return@withContext opened
            delay(500)
            elements = groundingEngine.scanScreen(service.getActiveRootNode())
        }
        val editable = elements.filter { it.isEditable || it.className.contains("EditText", true) }
        if (editable.size != 1) {
            AgentTaskEngine.recordAction("resolve Spotify search field", "Expected one editable search field; found ${editable.size}", false)
            return@withContext TaskActionResult(false, "Spotify search field is ambiguous; no text was entered.", groundingEngine.formatElementsSummary(elements, service.getActiveRootNode()), verified = false)
        }

        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val typed = handleType(query, editable.single().primaryLabel.takeIf { it.isNotBlank() }, submit = true)
        if (!typed.success) return@withContext typed
        val currentRoot = service.getActiveRootNode()
        recordObservation(service, groundingEngine.formatElementsSummary(groundingEngine.scanScreen(currentRoot), currentRoot))
        AgentTaskEngine.recordAction("search Spotify: $query", "Search text entered and read back", typed.verified == true)
        val afterSearchWait = waitForUiState(
            com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(currentRoot),
            expectedPackage = "spotify",
            timeoutMs = 8_000L
        )
        if (!afterSearchWait.ready) {
            return@withContext TaskActionResult(false, "Spotify search results did not become stable before the wait limit.", groundingEngine.formatElementsSummary(afterSearchWait.elements, afterSearchWait.root), verified = false)
        }

        var selected: UiElementInfo? = null
        var searchFailure: TaskActionResult? = null
        var previous: com.jarvis.assistant.automation.agent.ScreenSignature? = null
        var noProgress = 0
        val searchLoop = AgentTaskEngine.runAutonomousLoop(7) { attempt, _ ->
            if (AgentTaskEngine.isCancelled()) {
                searchFailure = TaskActionResult(false, "Spotify search was cancelled.", verified = false)
                return@runAutonomousLoop false
            }
            val freshRoot = service.getActiveRootNode()
            if (freshRoot?.packageName?.toString()?.contains("spotify", true) != true) {
                searchFailure = TaskActionResult(false, "Spotify left the foreground during search; stopped without selecting a result.", verified = false)
                AgentTaskEngine.failCurrentStep("Spotify is no longer the foreground app.", blocked = true)
                return@runAutonomousLoop false
            }
            val fresh = groundingEngine.scanScreen(freshRoot)
            val tokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 }.toSet()
            val candidates = fresh.filter { it.hasTextOrDesc && !it.isEditable && it.isClickable }
                .filter { candidate ->
                    val label = candidate.primaryLabel.lowercase(Locale.ROOT)
                    tokens.isNotEmpty() && tokens.all(label::contains) &&
                        !label.contains("search results") && !label.contains("see all")
                }
            val ranked = candidates.map { candidate ->
                val label = candidate.primaryLabel.trim().lowercase(Locale.ROOT)
                candidate to when {
                    label == query.lowercase(Locale.ROOT) -> 3
                    label.startsWith(query.lowercase(Locale.ROOT)) -> 2
                    else -> 1
                }
            }
            val bestScore = ranked.maxOfOrNull { it.second } ?: 0
            val best = ranked.filter { it.second == bestScore }.distinctBy { it.first.primaryLabel.lowercase(Locale.ROOT) }
            if (bestScore >= 2 && best.size == 1) {
                selected = best.single().first
                return@runAutonomousLoop false
            }
            if (best.size > 1 && bestScore >= 2) {
                AgentTaskEngine.setState(AgentTaskEngine.State.RECOVERING)
                searchFailure = TaskActionResult(false, "Spotify shows multiple similarly named matches for '$query'; stopped without selecting one.", groundingEngine.formatElementsSummary(fresh, freshRoot), verified = false)
                return@runAutonomousLoop false
            }
            if (attempt == 6) return@runAutonomousLoop false
            val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(freshRoot)
            if (signature.isEquivalentTo(previous)) noProgress++ else noProgress = 0
            if (noProgress >= 2) return@runAutonomousLoop false
            previous = signature
            if (!handleScroll("down", "medium").success) {
                searchFailure = TaskActionResult(false, "Spotify results could not be scrolled safely.", groundingEngine.formatElementsSummary(fresh, freshRoot), verified = false)
                return@runAutonomousLoop false
            }
            val scrollWait = waitForUiState(signature, expectedPackage = "spotify", timeoutMs = 6_000L)
            if (!scrollWait.ready) {
                searchFailure = TaskActionResult(false, "Spotify results did not settle after scrolling.", groundingEngine.formatElementsSummary(scrollWait.elements, scrollWait.root), verified = false)
                return@runAutonomousLoop false
            }
            true
        }
        if (searchFailure != null) return@withContext searchFailure!!
        val match = selected ?: run {
            AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.TARGET_NOT_FOUND, "No unique Spotify title match was found after bounded scrolling")
            AgentTaskEngine.failCurrentStep(
                if (searchLoop.reachedLimit) "Spotify search reached its bounded scroll limit without a unique match."
                else "No unique Spotify match was found in the observed search results.",
                blocked = true
            )
            return@withContext TaskActionResult(false, "Could not find a unique matching Spotify result after searching and scrolling.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
        }
        AgentTaskEngine.completeStepByTitle("Search for the requested track", "Searched '$query' and found one unique title match", verified = true)
        AgentTaskEngine.setState(AgentTaskEngine.State.RESOLVING_TARGET)
        val clicked = handleClick(match.primaryLabel, taskGoal = goal)
        if (!clicked.success) return@withContext clicked
        val playbackWait = waitForUiState(
            com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(service.getActiveRootNode()),
            expectedPackage = "spotify",
            timeoutMs = 8_000L
        )
        val playbackRoot = playbackWait.root ?: service.getActiveRootNode()
        val playbackElements = playbackWait.elements.ifEmpty { groundingEngine.scanScreen(playbackRoot) }
        val requestedTokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val trackVisible = playbackElements.any { item ->
            !item.isEditable && item.hasTextOrDesc && requestedTokens.all { item.primaryLabel.contains(it, true) }
        }
        val isPlaying = playbackElements.any { item ->
            val label = item.primaryLabel.lowercase(Locale.ROOT)
            label == "pause" || label.contains("pause playback") || label.contains("now playing") || label.contains("currently playing")
        }
        val verified = trackVisible && isPlaying
        AgentTaskEngine.recordCompletionEvidence(
            if (verified) AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL else AgentTaskEngine.CompletionEvidence.AMBIGUOUS,
            if (verified) "Requested song and active playback controls are visible" else "Playback state or requested song title is not visible after selection"
        )
        AgentTaskEngine.recordAction("play ${match.primaryLabel}", "trackVisible=$trackVisible, activePlayback=$isPlaying", verified)
        AgentTaskEngine.completeStepByTitle("Select one unique match", "Selected '${match.primaryLabel}'", clicked.verified == true)
        AgentTaskEngine.completeStepByTitle("Verify playback", "trackVisible=$trackVisible; activePlayback=$isPlaying", verified)
        AgentTaskEngine.setState(if (verified) AgentTaskEngine.State.COMPLETED else AgentTaskEngine.State.RECOVERING)
        TaskActionResult(verified, if (verified) "Verified Spotify is playing '${match.primaryLabel}'." else "Selected '${match.primaryLabel}', but playback could not be verified.", groundingEngine.formatElementsSummary(playbackElements, playbackRoot), verified = verified)
    }

    /** Processes an Instagram liked-items list only when each visible Unlike control can be verified. */
    suspend fun runInstagramBulkUnlike(goal: String): TaskActionResult = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
            ?: return@withContext TaskActionResult(false, "Accessibility Service is not running.", verified = false)
        val task = AgentTaskEngine.begin(goal, "instagram-bulk-unlike")
        if (task.taskScope != AgentTaskEngine.TaskScope.ALL_MATCHING ||
            !goal.contains("instagram", true) || !goal.contains("unlike", true)) {
            return@withContext TaskActionResult(false, "This is not an Instagram bulk unlike task.", verified = false)
        }
        val requestedType = when {
            goal.contains("reel", true) -> "reel"
            goal.contains("post", true) -> "post"
            else -> null
        }
        val initialRoot = service.getActiveRootNode()
        if (initialRoot?.packageName?.toString()?.contains("instagram", true) != true) {
            return@withContext TaskActionResult(false, "Instagram is not the foreground app.", verified = false)
        }
        val initialElements = groundingEngine.scanScreen(initialRoot)
        val onLikedItemsList = initialElements.any { element ->
            val label = element.primaryLabel.lowercase(Locale.ROOT)
            label.contains("posts you've liked") || label.contains("posts you liked") ||
                label.contains("liked posts") || label.contains("liked reels") || label == "likes"
        }
        if (!onLikedItemsList) {
            return@withContext TaskActionResult(false, "Stopped safely: the current Instagram screen is not identifiable as the liked-items list.", groundingEngine.formatElementsSummary(initialElements, initialRoot), verified = false)
        }
        AgentTaskEngine.completeStepByTitle("Open Instagram", "Instagram is confirmed in the foreground", verified = true)
        AgentTaskEngine.completeStepByTitle("Navigate to liked content", "An identifiable liked-items list is visible", verified = true)
        if (initialElements.none { it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true) }) {
            return@withContext TaskActionResult(false, "No explicit accessible Unlike controls are visible yet; continue to the Instagram liked-items list and inspect again.", groundingEngine.formatElementsSummary(initialElements, initialRoot), verified = false)
        }
        var unchangedScrolls = 0
        var processedThisRun = 0
        var terminalResult: TaskActionResult? = null
        AgentTaskEngine.setState(AgentTaskEngine.State.PLANNING)
        AgentTaskEngine.replacePlan(listOf("Understand goal", "Open Instagram", "Navigate to liked content", "Observe and process matching items", "Scroll and wait for more items", "Verify list end and results"))
        val loopOutcome = AgentTaskEngine.runAutonomousLoop(400) { _, _ ->
            if (AgentTaskEngine.isCancelled()) {
                terminalResult = TaskActionResult(false, "Instagram bulk unlike was cancelled after $processedThisRun verified item(s).", verified = false)
                return@runAutonomousLoop false
            }
            val root = service.getActiveRootNode()
            if (root?.packageName?.toString()?.contains("instagram", true) != true) {
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.AMBIGUOUS, "Foreground app changed during bulk processing")
                AgentTaskEngine.failCurrentStep("Instagram is no longer the foreground app.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: Instagram is no longer the foreground app.", verified = false)
                return@runAutonomousLoop false
            }
            var elements = groundingEngine.scanScreen(root)
            if (elements.none { element ->
                    val label = element.primaryLabel.lowercase(Locale.ROOT)
                    label.contains("posts you've liked") || label.contains("posts you liked") ||
                        label.contains("liked posts") || label.contains("liked reels") || label == "likes"
                }) {
                AgentTaskEngine.failCurrentStep("Liked-items list is no longer identifiable on a fresh observation.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped safely: the liked-items list is no longer identifiable on the fresh screen.", groundingEngine.formatElementsSummary(elements, root), verified = false)
                return@runAutonomousLoop false
            }
            val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(root)
            val summary = groundingEngine.formatElementsSummary(elements, root)
            AgentTaskEngine.observe(signature, root.packageName?.toString(), summary)
            AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
            val controls = elements.filter { element ->
                element.isClickable && Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(element.primaryLabel.trim())
            }.sortedBy { it.bounds.top }

            val target = controls.firstOrNull()
            if (target != null) {
                val nearby = elements.filter { element ->
                    !element.isEditable && element.bounds != target.bounds &&
                        abs(element.bounds.centerY() - target.bounds.centerY()) <= 110 &&
                        element.hasTextOrDesc &&
                        !element.primaryLabel.contains("unlike", true) &&
                        !element.primaryLabel.equals("like", true) &&
                        !element.primaryLabel.contains("comment", true)
                }.map { it.primaryLabel.trim() }.filter { it.length >= 3 }.distinct()
                if (nearby.isEmpty()) {
                    AgentTaskEngine.recordAction("resolve Instagram item", "Unlike control has no accessible item identity", false)
                    AgentTaskEngine.failCurrentStep("Unlike control has no unique accessible item identity.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: Instagram exposes an Unlike button but not enough nearby item text to identify which item it belongs to.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                val identity = nearby.joinToString("|").lowercase(Locale.ROOT)
                if (requestedType != null && nearby.none { it.contains(requestedType, true) ||
                        (requestedType == "reel" && it.contains("video", true)) ||
                        (requestedType == "post" && it.contains("photo", true)) }) {
                    AgentTaskEngine.failCurrentStep("Visible item type cannot be verified as $requestedType.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: the visible liked item cannot be verified as a requested $requestedType.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                if (identity in task.processedItemSignatures) {
                    AgentTaskEngine.recordAction("resolve Instagram item", "Refusing to toggle previously processed item '$identity'", false)
                    AgentTaskEngine.failCurrentStep("A previously processed item identity is still exposed as Unlike.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely: the same item identity is still exposed as Unlike after an earlier verified action.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                AgentTaskEngine.setState(AgentTaskEngine.State.RESOLVING_TARGET)
                val preCount = controls.size
                AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
                val dispatched = mutateUi("instagram-unlike:${identity.take(48)}") {
                    service.performClickAtRect(target.bounds)
                }
                if (!dispatched) {
                    AgentTaskEngine.recordAction("unlike:$identity", "UI action was not dispatched", false)
                    AgentTaskEngine.failCurrentStep("Could not dispatch the Unlike action.")
                    terminalResult = TaskActionResult(false, "Could not safely dispatch the Unlike action; stopped.", summary, verified = false)
                    return@runAutonomousLoop false
                }
                val itemWait = waitForUiState(signature, expectedPackage = "instagram", timeoutMs = 6_000L)
                if (!itemWait.ready) {
                    AgentTaskEngine.failCurrentStep("UI did not settle after Unlike action; item result is unverified.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped: the Instagram list did not settle after the item action; its result is unverified.", groundingEngine.formatElementsSummary(itemWait.elements, itemWait.root), verified = false)
                    return@runAutonomousLoop false
                }
                val afterRoot = itemWait.root ?: service.getActiveRootNode()
                val afterElements = itemWait.elements
                val afterCount = afterElements.count { it.isClickable &&
                    Regex("^unlike(?:\\b|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(it.primaryLabel.trim()) }
                val sameControlChangedToLike = afterElements.any { element ->
                    element.bounds.centerX().let { abs(it - target.bounds.centerX()) <= 80 } &&
                        abs(element.bounds.centerY() - target.bounds.centerY()) <= 100 &&
                        element.primaryLabel.trim().startsWith("like", true) &&
                        !element.primaryLabel.trim().startsWith("unlike", true)
                }
                val verified = afterCount < preCount || sameControlChangedToLike
                AgentTaskEngine.recordAction("unlike:$identity", "Before=Unlike; afterCount=$afterCount; toggledToLike=$sameControlChangedToLike", verified)
                if (!verified || !AgentTaskEngine.markItemProcessed(identity, true)) {
                    AgentTaskEngine.failCurrentStep("Post-action state could not be verified.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped: the item's post-action state could not be verified, so it was not marked processed.", groundingEngine.formatElementsSummary(afterElements, afterRoot), verified = false)
                    return@runAutonomousLoop false
                }
                processedThisRun++
                task.completedSteps.add("Unlike $identity")
                if (task.pendingSteps.isNotEmpty()) task.pendingSteps.removeAt(0)
                AgentTaskEngine.updateActivity("Verified unlike for item $processedThisRun: ${identity.take(90)}", AgentTaskEngine.State.CONTINUING)
                return@runAutonomousLoop true
            }

            // No accessible Unlike action remains on this viewport. Scroll and require
            // fresh, usable observations; repeated unchanged signatures are the only end evidence.
            AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
            val scrolled = mutateUi("instagram-bulk-scroll") { service.performSmartScroll("down", "large") }
            if (!scrolled) {
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.NO_PROGRESS, "Instagram did not accept a scroll gesture")
                AgentTaskEngine.failCurrentStep("Instagram did not accept a scroll gesture.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: Instagram could not scroll the liked-items list; completion is unverified.", summary, verified = false)
                return@runAutonomousLoop false
            }
            val scrollWait = waitForUiState(signature, expectedPackage = "instagram", timeoutMs = 8_000L)
            val afterScrollRoot = scrollWait.root
            elements = scrollWait.elements
            val afterScrollSignature = scrollWait.signature
            if (!scrollWait.ready || afterScrollRoot == null || afterScrollSignature == null || afterScrollSignature.elementCount == 0) {
                AgentTaskEngine.failCurrentStep("Fresh Instagram content did not become ready after scrolling.", blocked = true)
                terminalResult = TaskActionResult(false, "Stopped: the fresh Instagram screen did not become stable after scrolling.", verified = false)
                return@runAutonomousLoop false
            }
            if (signature.isEquivalentTo(afterScrollSignature)) unchangedScrolls++ else unchangedScrolls = 0
            AgentTaskEngine.observe(afterScrollSignature, afterScrollRoot.packageName?.toString(), groundingEngine.formatElementsSummary(elements, afterScrollRoot))
            AgentTaskEngine.current()?.noProgressCount = unchangedScrolls
            if (unchangedScrolls >= 2) {
                if (processedThisRun == 0 && task.processedItemSignatures.isEmpty()) {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.AMBIGUOUS, "Reached a stable viewport without verifying any item actions")
                    AgentTaskEngine.failCurrentStep("No item was verifiably unliked before the list stopped progressing.", blocked = true)
                    terminalResult = TaskActionResult(false, "Reached an unchanged viewport, but no item was verifiably unliked; completion is not claimed.", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = false)
                    return@runAutonomousLoop false
                }
                val endMarkers = listOf("end of list", "no more posts", "no more reels", "you've seen all", "you have seen all", "you're all caught up", "you are all caught up", "nothing to show")
                val actualEndMarker = elements.any { element -> endMarkers.any(element.primaryLabel.lowercase(Locale.ROOT)::contains) }
                if (!actualEndMarker) {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.NO_PROGRESS, "Repeated scroll produced no movement, but the screen exposes no explicit end-of-content marker")
                    AgentTaskEngine.failCurrentStep("Instagram stopped scrolling, but the UI did not prove the end of the liked-items list.", blocked = true)
                    terminalResult = TaskActionResult(false, "Stopped safely after repeated no-progress observations. Instagram did not expose evidence that this is the end of the full liked-items list, so I cannot claim all items were processed.", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = false)
                    return@runAutonomousLoop false
                }
                AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.END_REACHED, "Explicit Instagram end marker visible after two stable post-scroll observations; ${task.processedItemSignatures.size} unique item(s) were individually verified")
                AgentTaskEngine.completeStepByTitle("Observe and process matching items", "${task.processedItemSignatures.size} unique item(s) were verified individually", verified = true)
                AgentTaskEngine.completeStepByTitle("Scroll and wait for more items", "Explicit end marker visible after fresh post-scroll observations", verified = true)
                AgentTaskEngine.completeStepByTitle("Verify list end and results", "End marker and ${task.processedItemSignatures.size} verified item(s)", verified = true)
                AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                terminalResult = TaskActionResult(true, "Verified Instagram's end-of-list marker after processing ${task.processedItemSignatures.size} item(s).", groundingEngine.formatElementsSummary(elements, afterScrollRoot), verified = true)
                return@runAutonomousLoop false
            }
            true
        }
        terminalResult ?: when {
            loopOutcome.cancelled -> TaskActionResult(false, "Instagram bulk unlike was cancelled after verifying $processedThisRun item(s).", verified = false)
            loopOutcome.reachedLimit -> TaskActionResult(false, "Stopped at the Instagram processing safety limit after verifying $processedThisRun item(s); the list may contain more.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
            else -> TaskActionResult(false, loopOutcome.error ?: "Instagram processing stopped without verified completion.", groundingEngine.formatElementsSummary(groundingEngine.scanScreen(service.getActiveRootNode()), service.getActiveRootNode()), verified = false)
        }
    }

    private fun resetScrollCount() {
        consecutiveScrollCount = 0
        lastScreenSignature = ""
        duplicateSignatureCount = 0
    }

    private suspend fun dispatchImmediateScreenFrame() {
        try {
            val snapshot = JarvisAccessibilityService.captureScreenJpeg(75)
                ?: screenCaptureManager.captureSnapshotJpeg(70)
            if (snapshot != null) {
                JarvisConversationService.sendImmediateVideoFrame(snapshot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching immediate frame: ${e.message}")
        }
    }

    /** Waits for accessibility changes and a stable, non-loading tree; polling is bounded fallback only. */
    suspend fun waitForUiState(
        previousSignature: com.jarvis.assistant.automation.agent.ScreenSignature? = null,
        expectedPackage: String? = null,
        timeoutMs: Long = 10_000L
    ): UiWaitResult = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
            ?: return@withContext UiWaitResult(false, null, emptyList(), null, false, "Accessibility Service is unavailable")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 20_000L)
        var lastSignature: com.jarvis.assistant.automation.agent.ScreenSignature? = null
        var stableSamples = 0
        var latestRoot: AccessibilityNodeInfo? = null
        var latestElements: List<UiElementInfo> = emptyList()
        var latestSignature: com.jarvis.assistant.automation.agent.ScreenSignature? = null
        AgentTaskEngine.updateActivity("Waiting for the app UI to settle", AgentTaskEngine.State.WAITING_FOR_UI)
        while (System.currentTimeMillis() < deadline) {
            if (AgentTaskEngine.isCancelled()) {
                return@withContext UiWaitResult(false, latestRoot, latestElements, latestSignature, false, "Task was cancelled while waiting for UI")
            }
            latestRoot = service.getActiveRootNode()
            latestElements = groundingEngine.scanScreen(latestRoot)
            latestSignature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(latestRoot)
            val pkg = latestRoot?.packageName?.toString().orEmpty()
            val packageReady = expectedPackage.isNullOrBlank() || pkg.contains(expectedPackage, ignoreCase = true)
            val loading = latestElements.any { element ->
                val label = element.primaryLabel.lowercase(Locale.ROOT)
                listOf("loading", "buffering", "please wait", "working…", "working...").any(label::contains)
            }
            AgentTaskEngine.observe(latestSignature, pkg, groundingEngine.formatElementsSummary(latestElements, latestRoot))
            if (packageReady && !loading && latestRoot != null) {
                if (latestSignature != null && latestSignature.isEquivalentTo(lastSignature)) stableSamples++ else stableSamples = 0
                if (stableSamples >= 1) {
                    AgentTaskEngine.setState(AgentTaskEngine.State.VERIFYING)
                    return@withContext UiWaitResult(
                        true, latestRoot, latestElements, latestSignature,
                        previousSignature != null && !latestSignature.isEquivalentTo(previousSignature),
                        "UI tree stable across fresh accessibility observations"
                    )
                }
            } else {
                stableSamples = 0
            }
            lastSignature = latestSignature
            val revision = JarvisAccessibilityService.uiChanges.value.revision
            val waitedForEvent = withTimeoutOrNull(220L) {
                JarvisAccessibilityService.uiChanges.first { it.revision > revision }
                true
            } ?: false
            if (!waitedForEvent) delay(100L)
        }
        AgentTaskEngine.updateActivity("UI did not become stable before the wait deadline", AgentTaskEngine.State.RECOVERING)
        UiWaitResult(false, latestRoot, latestElements, latestSignature,
                previousSignature != null && latestSignature != null && !latestSignature.isEquivalentTo(previousSignature),
            "Timed out waiting for a stable non-loading accessibility tree")
    }

    /**
     * Executes a precise visual click on the requested UI element.
     * Snaps to the exact hardware bounding box Rect from the accessibility tree.
     */
    suspend fun handleClick(
        targetName: String,
        elementId: Int? = null,
        description: String? = null,
        isToggle: Boolean = false,
        x: Int? = null,
        y: Int? = null,
        allowChromeMenuForSettings: Boolean = false,
        forbidAddingInstagramLike: Boolean = false,
        taskGoal: String = ""
    ): TaskActionResult = withContext(Dispatchers.IO) {
        if (taskGoal.isNotBlank()) AgentTaskEngine.begin(taskGoal)
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(
                success = false,
                message = "Accessibility Service is not enabled. Please enable it in Settings."
            )
        }

        // Apply the same sensitive-action gate to visual taps as named/accessibility taps.
        if (isSensitiveTarget(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Sensitive action detected ('$targetName'). Please ask the user for explicit confirmation before proceeding.",
                requiresUserConfirmation = true
            )
        }

        // A stale or confused model must not send an email just because it found a
        // button labelled Send. Require the active user task to explicitly request it.
        val initialRoot = service.getActiveRootNode()
        val initialPackage = initialRoot?.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        val normalizedTarget = targetName.lowercase(Locale.ROOT).trim()
        val isGmailSend = initialPackage.contains("com.google.android.gm") &&
            (normalizedTarget == "send" || normalizedTarget == "send email" || normalizedTarget == "send message")
        if (isGmailSend) {
            val goal = taskGoal.lowercase(Locale.ROOT)
            val explicitEmailRequest = (goal.contains("email") || goal.contains("gmail")) &&
                (goal.contains("send") || goal.contains("email this") || goal.contains("mail this"))
            if (!explicitEmailRequest) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Blocked Gmail Send: the active user request did not explicitly ask to send an email. No message was sent.",
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }
            val gmailElements = groundingEngine.scanScreen(initialRoot)
            if (gmailElements.any { it.primaryLabel.contains("add at least one recipient", ignoreCase = true) }) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Blocked Gmail Send because Gmail says no recipient is present. Add and verify the intended recipient first.",
                    currentStateSummary = groundingEngine.formatElementsSummary(gmailElements, initialRoot),
                    verified = false
                )
            }
        }

        // CLICK DEBOUNCE / RATE LIMITER: Prevent rapid machine-gun tapping
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastClickTimestamp
        if (timeSinceLast < 280L) {
            delay(280L - timeSinceLast)
        }

        // DIRECT VISUAL COORDINATE TAP (100% Precision Vision Mode):
        // When Screen Share is active, Gemini can pass normalized coordinates (0-1000)
        // to directly tap any visual button, icon, or element with physical precision!
        if (x != null && y != null && x in 0..1000 && y in 0..1000 && screenCaptureManager.isSharing.value) {
            val dm = DisplayMetrics()
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                dm.setTo(service.resources.displayMetrics)
            }
            var physicalX = (x / 1000f) * dm.widthPixels
            var physicalY = (y / 1000f) * dm.heightPixels

            val rootBeforeVisualTap = service.getActiveRootNode()
            val elementsBeforeVisualTap = groundingEngine.scanScreen(rootBeforeVisualTap)
            val hitElement = elementsBeforeVisualTap
                .filter { it.isClickable && it.bounds.contains(physicalX.toInt(), physicalY.toInt()) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
            if (hitElement != null && hitElement.hasTextOrDesc) {
                val targetWords = targetName.lowercase(Locale.ROOT)
                    .replace("\\b(icon|button|item|the|on|screen)\\b".toRegex(), " ")
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.length >= 3 }
                val hitLabel = hitElement.primaryLabel.lowercase(Locale.ROOT)
                val labelAgrees = targetWords.isEmpty() || targetWords.any { hitLabel.contains(it) } ||
                    targetName.lowercase(Locale.ROOT).contains(hitLabel)
                if (!labelAgrees) {
                    return@withContext TaskActionResult(
                        success = false,
                        message = "The screen point lands on '${hitElement.primaryLabel}', which does not match '$targetName'. No tap was made.",
                        currentStateSummary = captureCurrentStateSummary(service)
                    )
                }
                // Snap to the current clickable target's accessibility bounds rather than the model's estimated point.
                physicalX = hitElement.bounds.exactCenterX()
                physicalY = hitElement.bounds.exactCenterY()
            }

            val rootPkg = service.getActiveRootNode()?.packageName?.toString() ?: ""
            val isBrowser = rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
            val targetLower = targetName.lowercase(Locale.ROOT)

            if (isBrowser) {
                // Block accidental tap on Chrome 3-dots menu button (top right: x >= 850 && y <= 150)
                if (x >= 850 && y <= 150) {
                    Log.w(TAG, "Blocked accidental tap on Chrome 3-dots menu button at ($x, $y)")
                    return@withContext TaskActionResult(
                        success = false,
                        message = "Refusing to click Chrome 3-dots browser menu. Stay on the web creation page."
                    )
                }
                // Block accidental clicks on settings tags in Google Flow (e.g. Video • 720p • 8s • x1)
                val isSubmit = targetLower.contains("generate") || targetLower.contains("submit") ||
                        targetLower.contains("arrow") || targetLower.contains("create") || targetLower.contains("enter") || targetLower.contains("send")
                if (!isSubmit && (targetLower.contains("video") || targetLower.contains("720p") || targetLower.contains("8s") || targetLower.contains("x1") || targetLower.contains("frames") || targetLower.contains("omni") || targetLower.contains("ingredients"))) {
                    Log.w(TAG, "Blocked accidental tap on Google Flow settings chip: '$targetName' at ($x, $y)")
                    return@withContext TaskActionResult(
                        success = false,
                        message = "Refusing to click video settings chip. The prompt is ready; click the Generate arrow [→] on the right to start."
                    )
                }
            }

            // Suppress rapid duplicate clicks on identical coordinates within 1.8s
            if (Math.abs(physicalX - lastClickPhysicalX) < 30f && Math.abs(physicalY - lastClickPhysicalY) < 30f && (now - lastClickTimestamp) < 1800L) {
                Log.w(TAG, "Suppressed rapid duplicate click at ($physicalX, $physicalY)")
                return@withContext TaskActionResult(true, "Already tapped at this location. Waiting for UI update.")
            }

            val isSubmitIntent = targetLower.contains("generate") || targetLower.contains("submit") ||
                    targetLower.contains("arrow") || targetLower.contains("create") || targetLower.contains("enter") || targetLower.contains("send")

            // Snap to exact Google Flow arrow button if target is submit/generate or coordinates are in the bottom-right action bar
            // Only in a browser, and NEVER while the keyboard is open (the fixed spot is then the keyboard's Enter key).
            if ((isSubmitIntent || (x >= 800 && y >= 880)) && !service.isKeyboardVisible()) {
                if (isBrowser) {
                    val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val realDm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.getRealMetrics(realDm) ?: run {
                        realDm.widthPixels = dm.widthPixels
                        realDm.heightPixels = 2408
                    }
                    val exactArrowX = realDm.widthPixels * 0.9333f // Exactly 1008 on 1080p
                    val exactArrowY = realDm.heightPixels * 0.9440f // Exactly 2273 on 2408p
                    Log.i(TAG, "Snapping Generate/Submit arrow click from ($physicalX, $physicalY) -> exact button center ($exactArrowX, $exactArrowY)")
                    physicalX = exactArrowX
                    physicalY = exactArrowY
                }
            }

            lastClickTimestamp = System.currentTimeMillis()
            lastClickPhysicalX = physicalX
            lastClickPhysicalY = physicalY

            Log.i(TAG, "Direct visual tap at normalized ($x, $y) -> physical ($physicalX, $physicalY)")
            val preTapSignature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootBeforeVisualTap)
            AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
            val clicked = mutateUi("visual-click:$targetName") { service.clickAt(physicalX, physicalY) }
            val tapDelay = if (isBrowser) 800L else 400L
            delay(tapDelay)
            dispatchImmediateScreenFrame()
            val rootAfterVisualTap = service.getActiveRootNode()
            val elementsAfterVisualTap = groundingEngine.scanScreen(rootAfterVisualTap)
            val postTapSignature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootAfterVisualTap)
            val verified = !postTapSignature.isEquivalentTo(preTapSignature)
            return@withContext TaskActionResult(
                success = clicked,
                message = if (!clicked) {
                    "Tap could not be dispatched for '$targetName'. No action was completed."
                } else if (verified) {
                    "Tapped '$targetName' and observed a screen change, but the requested result is not semantically verified. Inspect the fresh screen before continuing."
                } else {
                    "Tap was dispatched for '$targetName', but a screen change was not detected. Do not claim it succeeded; inspect the screen before deciding what to do next."
                },
                currentStateSummary = groundingEngine.formatElementsSummary(elementsAfterVisualTap, rootAfterVisualTap),
                verified = false
            )
        }
        // Anti-Clear-Search Guard: prevent AI from reflexively clicking "Clear search" in the middle of a search task
        if (isClearSearchButton(targetName, description)) {
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to click '$targetName'. Do not clear or erase the search input! Instead, click a matching result/folder from the list below, or submit.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        // GOOGLE FLOW / VIDEO SETTINGS PANEL GUARD:
        // Block clicks on video quality, duration, multiplier, and settings-panel elements.
        // These are NEVER valid targets during content creation — the user wants to type a prompt!
        val settingsBlocklistExact = setOf(
            "16:9", "9:16", "360p", "480p", "720p", "1080p",
            "4s", "6s", "8s", "10s", "12s", "15s",
            "x1", "x2", "x3", "x4", "x5",
            "frames", "ingredients", "omni 1.1 flash", "omni", "veo 2"
        )
        val rawTargetLower = targetName.trim().lowercase()
        if (settingsBlocklistExact.contains(rawTargetLower) ||
            // Also block if it's ONLY a number+unit pattern like "8s" "720p" "x2"
            rawTargetLower.matches(Regex("\\d+[sp]|x\\d+|\\d+[sS]ec?"))) {
            return@withContext TaskActionResult(
                success = false,
                message = "BLOCKED: '$targetName' is a Google Flow video settings control (quality/duration/multiplier). Do NOT click these. The ONLY thing you should do is: call type_text(text='<your prompt>', target_input_name='What do you want to create') to type the video description into the prompt box. THEN call inspect_screen() to find the arrow/generate button ID and click it.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }


        // Anti-Search-Loop on Web Search Pages: prevent model from re-tapping search bar when results are visible
        if (isBrowserSearchScreen(service) && (targetName.equals("search", ignoreCase = true) || targetName.contains("search bar", ignoreCase = true) || targetName.contains("url_bar", ignoreCase = true))) {
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val resultLinks = elements.filter { it.isClickable && !it.isEditable && it.bounds.top > 250 && it.text.isNotBlank() }
            if (resultLinks.isNotEmpty()) {
                val bestLink = resultLinks.first()
                Log.i(TAG, "Re-routing search bar click on search results page to top result link: '${bestLink.primaryLabel}'")
                val clicked = mutateUi("click-search-result") { service.performClickAtRect(bestLink.bounds) }
                delay(700)
                dispatchImmediateScreenFrame()
                val rootAfter = service.getActiveRootNode()
                val elementsAfter = groundingEngine.scanScreen(rootAfter)
                return@withContext TaskActionResult(
                    success = clicked,
                    message = "Navigating to website: automatically clicked search result link '${bestLink.primaryLabel}'. Wait for page to load and locate generation prompt input.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        }

        val rootBefore = service.getActiveRootNode()
        val elementsBefore = groundingEngine.scanScreen(rootBefore)
        val preTapSignature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootBefore)
        val repeatedTarget = targetName.lowercase(Locale.ROOT).trim()
        if (repeatedTarget.isNotBlank() && repeatedTarget == lastUnverifiedClickTarget &&
            lastUnverifiedClickScreen?.isEquivalentTo(preTapSignature) == true) {
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to repeat '$targetName': the previous tap did not change this screen. No second tap was made; inspect the current screen or choose a different target.",
                currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                verified = false
            )
        }
        val onInstagram = rootBefore?.packageName?.toString()?.contains("instagram", ignoreCase = true) == true
        val asksToLike = Regex("\\blike\\b", RegexOption.IGNORE_CASE).containsMatchIn("$targetName ${description.orEmpty()}") &&
            !"$targetName ${description.orEmpty()}".contains("unlike", ignoreCase = true)
        if (forbidAddingInstagramLike && onInstagram && asksToLike) {
            return@withContext TaskActionResult(
                success = false,
                message = "Blocked a Like action during an Instagram bulk unlike task. Use the Likes screen's Select mode and its Unlike action; do not open a Reel or add likes.",
                currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                verified = false
            )
        }
        val isFlowScreen = groundingEngine.isGoogleFlowSurface(elementsBefore)

        val initialTarget = groundingEngine.findTargetElement(
            elements = elementsBefore,
            targetName = targetName,
            isToggleIntent = isToggle || isTogglePhrase(targetName) || isTogglePhrase(description ?: ""),
            elementId = elementId
        )

        // Search-Result Loop Guard:
        // If on a browser search page and the target resolved was an editable search bar,
        // NEVER re-tap the search bar when search result links are visible below!
        val target = if (isBrowserSearchScreen(service) && (initialTarget?.isEditable == true || initialTarget?.viewId?.contains("search", ignoreCase = true) == true || initialTarget?.viewId?.contains("url_bar", ignoreCase = true) == true) && elementId == null) {
            val resultLinks = elementsBefore.filter { !it.isEditable && it.bounds.top > 250 && (it.isClickable || it.bounds.top > 280) && (it.text.isNotBlank() || it.contentDescription.isNotBlank()) }
            if (resultLinks.isNotEmpty()) {
                val qTokens = targetName.lowercase(Locale.ROOT).split("\\s+".toRegex()).filter { it.length > 2 }
                val bestResult = resultLinks.filter { el ->
                    qTokens.any { t -> el.primaryLabel.lowercase(Locale.ROOT).contains(t) }
                }.firstOrNull() ?: resultLinks.first()
                Log.i(TAG, "Prevented search bar re-tap: re-routing target from search bar to search result '${bestResult.primaryLabel}' (id=${bestResult.id})")
                bestResult
            } else {
                initialTarget
            }
        } else {
            initialTarget
        }

        // Chrome's native Settings page is reached through its overflow menu. Treat
        // that menu tap as a navigation step for an explicit Settings request, then
        // reacquire the tree and resolve Settings afresh before tapping it.
        val isChrome = (rootBefore?.packageName?.toString() ?: "").contains("chrome", ignoreCase = true)
        if ((target == null || isBrowserOverflowMenu(target)) && isChrome && targetName.contains("settings", ignoreCase = true)) {
            val menuTarget = groundingEngine.findTargetElement(elementsBefore, "More options")
                ?: groundingEngine.findTargetElement(elementsBefore, "Customize and control Chrome")
            if (menuTarget == null || !mutateUi("open-chrome-menu") { service.performClickAtRect(menuTarget.bounds) }) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Chrome's Settings menu button was not found; no tap was made.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsBefore, rootBefore),
                    verified = false
                )
            }
            delay(350)
            val menuRoot = service.getActiveRootNode()
            val menuElements = groundingEngine.scanScreen(menuRoot)
            val settingsTarget = groundingEngine.findTargetElement(menuElements, "Settings")
            if (settingsTarget == null) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Opened Chrome's menu, but its Settings item is not visible. Inspect the fresh screen before continuing.",
                    currentStateSummary = groundingEngine.formatElementsSummary(menuElements, menuRoot),
                    verified = false
                )
            }
            val beforeSettings = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(menuRoot)
            if (!mutateUi("open-chrome-settings") { service.performClickAtRect(settingsTarget.bounds) }) {
                return@withContext TaskActionResult(false, "Could not tap Chrome Settings.", groundingEngine.formatElementsSummary(menuElements, menuRoot), verified = false)
            }
            delay(700)
            val settingsRoot = service.getActiveRootNode()
            val settingsElements = groundingEngine.scanScreen(settingsRoot)
            val afterSettings = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(settingsRoot)
            val verified = settingsRoot != null && settingsRoot.packageName?.toString()?.contains("chrome", ignoreCase = true) == true &&
                !afterSettings.isEquivalentTo(beforeSettings) &&
                settingsElements.any { it.primaryLabel.contains("settings", ignoreCase = true) || it.viewId.contains("settings", ignoreCase = true) }
            return@withContext TaskActionResult(
                success = verified,
                message = if (verified) "Chrome Settings opened and screen change verified." else "Tapped Chrome Settings, but could not verify the Settings screen. Inspect the current screen.",
                currentStateSummary = groundingEngine.formatElementsSummary(settingsElements, settingsRoot),
                verified = verified
            )
        }

        if (target == null) {
            if (isFlowScreen) {
                return@withContext TaskActionResult(
                    success = false,
                    message = "Could not find '$targetName' as an actionable control on Google Flow. No tap or scroll was made; inspect the current screen and choose a visible Flow control.",
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }
            // Continue searching across screens until the target appears, the screen
            // stops changing repeatedly, or the bounded safety ceiling is reached.
            Log.d(TAG, "Target '$targetName' not visible on current screen. Running auto-scroll search...")
            var foundTarget: UiElementInfo? = null
            var prevSig: com.jarvis.assistant.automation.agent.ScreenSignature? = null
            var noProgressCount = 0

            for (scrollStep in 1..20) {
                AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
                val scrolled = mutateUi("auto-scroll-search:$targetName") { service.performSmartScroll("down", "medium") }
                AgentTaskEngine.current()?.scrollCount = (AgentTaskEngine.current()?.scrollCount ?: 0) + 1
                if (AgentTaskEngine.isCancelled()) break
                if (!scrolled) break
                delay(600)
                val rootAfterScroll = service.getActiveRootNode()
                val currentSig = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootAfterScroll)
                if (prevSig != null && currentSig.isEquivalentTo(prevSig)) {
                    noProgressCount++
                    if (noProgressCount >= 3) {
                        Log.i(TAG, "Stopping target search after $noProgressCount consecutive unchanged screen signatures.")
                        break
                    }
                } else {
                    noProgressCount = 0
                }
                prevSig = currentSig

                val elementsAfterScroll = groundingEngine.scanScreen(rootAfterScroll)
                val targetAfterScroll = groundingEngine.findTargetElement(
                    elements = elementsAfterScroll,
                    targetName = targetName,
                    isToggleIntent = isToggle,
                    elementId = elementId
                )
                if (targetAfterScroll != null) {
                    foundTarget = targetAfterScroll
                    Log.i(TAG, "Found target '$targetName' after $scrollStep scroll(s)!")
                    break
                }
            }

            if (foundTarget != null) {
                AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
                val clicked = mutateUi("click-after-scroll:$targetName") { service.performClickAtRect(foundTarget.bounds) }
                delay(300)
                dispatchImmediateScreenFrame()
                return@withContext TaskActionResult(
                    success = clicked,
                    message = if (clicked) "Tap was dispatched for '$targetName' after scrolling. Inspect the updated screen before claiming success or taking the next action."
                        else "Could not dispatch a tap for '$targetName' after scrolling.",
                    currentStateSummary = captureCurrentStateSummary(service),
                    verified = false
                )
            }

            val (pkg, title) = groundingEngine.extractScreenContext(rootBefore, elementsBefore)
            return@withContext TaskActionResult(
                success = false,
                message = "Could not find element '$targetName' on current screen ('$title' in $pkg). Check screen title or navigate to the correct section.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        val rootPkg = rootBefore?.packageName?.toString() ?: ""
        val isBrowserApp = rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
        val isExplicitMenuRequest = targetName.lowercase(Locale.ROOT).contains("menu") ||
            targetName.lowercase(Locale.ROOT).contains("more options") ||
            (description ?: "").lowercase(Locale.ROOT).contains("menu") ||
            (allowChromeMenuForSettings && isBrowserApp)

        if (isBrowserApp && !isExplicitMenuRequest && isBrowserOverflowMenu(target)) {
            Log.w(TAG, "Blocked clicking browser overflow menu ('More options' / 3 dots). Target: ${target.primaryLabel}")
            return@withContext TaskActionResult(
                success = false,
                message = "Refusing to click Chrome's browser menu ('More options' / three dots). Please interact directly with the website content, search results, or prompt input instead of the browser menu.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        val initialCheckedState = target.isChecked
        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val clickOk = mutateUi("click:${target.primaryLabel}") { service.performClickAtRect(target.bounds) }

        if (!clickOk) {
            return@withContext TaskActionResult(
                success = false,
                message = "Failed to tap coordinates for '${target.primaryLabel}'.",
                currentStateSummary = captureCurrentStateSummary(service)
            )
        }

        // Closed-loop verification: wait for UI transition (adaptive delay for browser/network links)
        val isWebOrLink = target.isClickable && (target.primaryLabel.contains("http", ignoreCase = true) ||
                target.primaryLabel.contains(".com", ignoreCase = true) ||
                target.primaryLabel.contains("www.", ignoreCase = true) ||
                target.primaryLabel.contains("search", ignoreCase = true) ||
                target.primaryLabel.contains("flow", ignoreCase = true))
        // Watch the screen patiently like a human (animations and page loads take time) and NEVER re-tap
        // automatically: menus, drawers, dropdowns and play/pause all TOGGLE, so a second tap undoes the first.
        val maxWatchMs = if (isWebOrLink) 12_000L else 8_000L
        dispatchImmediateScreenFrame()
        val preSig = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootBefore)
        val waitStartedAt = System.currentTimeMillis()
        val uiWait = waitForUiState(preSig, expectedPackage = rootPkg, timeoutMs = maxWatchMs)
        var rootAfter = uiWait.root ?: service.getActiveRootNode()
        var elementsAfter = uiWait.elements.ifEmpty { groundingEngine.scanScreen(rootAfter) }
        val postSig = uiWait.signature ?: com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootAfter)
        val stateChanged = !postSig.isEquivalentTo(preSig)
        val watchedMs = System.currentTimeMillis() - waitStartedAt

        // Verify if toggle state changed
        var toggleVerified = false
        if (target.isSwitchOrToggle()) {
            val updatedTarget = elementsAfter.firstOrNull { it.bounds == target.bounds || it.id == target.id }
            val nowChecked = updatedTarget?.isChecked
            toggleVerified = (nowChecked != initialCheckedState)
            Log.i(TAG, "Toggle verified: was=$initialCheckedState, now=$nowChecked, changed=$toggleVerified")
        }

        // (Removed the automatic second tap: it undid toggles such as menus/drawers and reported "verified".)
        if (!stateChanged && !target.isSwitchOrToggle()) {
            Log.w(TAG, "No UI state transition after ${watchedMs}ms for '${target.primaryLabel}'. NOT re-tapping (a second tap could undo a toggle).")
            lastUnverifiedClickTarget = repeatedTarget
            lastUnverifiedClickScreen = postSig
        } else {
            lastUnverifiedClickTarget = ""
            lastUnverifiedClickScreen = null
        }

        val recentOtp = JarvisAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        val clickedLabelLower = target.primaryLabel.lowercase(Locale.ROOT)
        val isInstallOrDownload = clickedLabelLower == "install" || clickedLabelLower.contains("install") || clickedLabelLower == "download" || clickedLabelLower.contains("download")
        val isPlayStore = rootPkg.contains("vending", ignoreCase = true)

        val normalizedGoal = taskGoal.lowercase(Locale.ROOT)
        val targetLabel = target.primaryLabel.lowercase(Locale.ROOT)
        val visibleTargetLabels = elementsAfter.filterNot { it.isEditable }
            .map { it.primaryLabel.lowercase(Locale.ROOT) }
        val playbackExpected = listOf("play", "play it", "play karo", "bajao").any(normalizedGoal::contains)
        val requestedTrackTokens = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in setOf("please", "spotify", "youtube", "playlist", "meri", "mera", "par", "mein", "song", "track", "find", "search", "play", "karo", "kar", "do", "dhundo", "dhoondh", "aur", "the", "for", "and", "with") }
        val playbackVerified = playbackExpected && requestedTrackTokens.isNotEmpty() &&
            requestedTrackTokens.all(targetLabel::contains) &&
            visibleTargetLabels.any { it.contains(targetLabel) } &&
            visibleTargetLabels.any { it.contains("pause") || it.contains("now playing") || it.contains("currently playing") }
        val intendedResultVerified = toggleVerified || playbackVerified
        val returnMessage = if (isPlayStore && isInstallOrDownload) {
            "Tapped Install on Google Play Store. The app is downloading and installing. NEXT ACTION: Wait for installation to complete and inspect screen for the 'Open' button, then tap 'Open' to launch and set up the app. DO NOT stop the task!"
        } else if (toggleVerified) {
            "Clicked '${target.primaryLabel}' and verified its checked state changed."
        } else if (playbackVerified) {
            "Clicked '${target.primaryLabel}'; the requested track is visible and the player exposes an active playback state."
        } else if (stateChanged) {
            "Clicked '${target.primaryLabel}' and observed a UI change, but the requested result is unverified. Inspect the fresh screen before claiming success or taking a dependent action."
        } else {
            "Tapped '${target.primaryLabel}' once, but no screen change appeared after ${watchedMs / 1000.0}s. Do NOT tap it again blindly (it may be a toggle that already worked): call inspect_screen and look at the screen first."
        }

        val summary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter) + otpNotice
        recordObservation(service, summary)
        AgentTaskEngine.recordAction("click:${target.primaryLabel}", returnMessage, intendedResultVerified)
        AgentTaskEngine.setState(if (intendedResultVerified) AgentTaskEngine.State.CONTINUING else AgentTaskEngine.State.RECOVERING)
        return@withContext TaskActionResult(
            success = stateChanged || toggleVerified,
            message = returnMessage,
            currentStateSummary = summary,
            verified = intendedResultVerified
        )
    }

    /**
     * Types text into a search bar or input field with zero typos.
     */
    suspend fun handleType(
        text: String,
        targetInputName: String? = null,
        submit: Boolean = false,
        x: Int? = null,
        y: Int? = null
    ): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service is not running.")
        }

        // Resolve target input element
        var targetRect: Rect? = null
        val rootForTyping = service.getAppRootNode() ?: service.getActiveRootNode()
        val elementsForTyping = if (rootForTyping != null) groundingEngine.scanScreen(rootForTyping) else emptyList()
        val visibleUiText = elementsForTyping.joinToString(" ") {
            "${it.text} ${it.contentDescription} ${it.viewId}"
        }.lowercase(Locale.ROOT)
        val browserPackage = (rootForTyping?.packageName?.toString() ?: "").let {
            it.contains("chrome", ignoreCase = true) || it.contains("browser", ignoreCase = true)
        }
        val isGoogleFlowPage = groundingEngine.isGoogleFlowSurface(elementsForTyping) ||
            visibleUiText.contains("flow.google") || visibleUiText.contains("labs.google/fx/tools/flow")
        val isGoogleAiStudioPage = visibleUiText.contains("aistudio.google") || visibleUiText.contains("google ai studio")
        val flowSettingsLabels = listOf("720p", "360p", "ingredients", "frames", "omni 1.1", "4s", "8s", "10s")
        val flowSettingsOverlayVisible = isGoogleFlowPage &&
            flowSettingsLabels.count { visibleUiText.contains(it) } >= 5 &&
            visibleUiText.contains("frames") && visibleUiText.contains("ingredients")

        // Flow's expanded generation settings sheet covers the composer. Dismiss it and
        // force a fresh screen read before typing so stale coordinates cannot hit a setting.
        if (flowSettingsOverlayVisible) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(450)
            dispatchImmediateScreenFrame()
            val refreshed = inspectScreen(waitForContent = true)
            return@withContext TaskActionResult(
                success = false,
                message = "Google Flow's settings panel was open, so no text was typed. I closed the panel; inspect the refreshed screen and target the prompt field before retrying.",
                currentStateSummary = refreshed,
                verified = false
            )
        }

        // PREFER the input's REAL accessibility bounds over model-estimated coordinates.
        // (The model was copying example numbers like x=500,y=895 instead of reading the screen.)
        val realInputRect: Rect? = try {
            val found = if (isGoogleFlowPage || isGoogleAiStudioPage) {
                elementsForTyping.firstOrNull { element ->
                    val label = element.primaryLabel.lowercase(Locale.ROOT)
                    !element.viewId.contains("url_bar", ignoreCase = true) &&
                        !element.viewId.contains("omnibox", ignoreCase = true) &&
                        (label.contains("what do you want to create") || label.contains("ask gemini") ||
                            label.contains("enter a prompt") || label.contains("write a prompt") ||
                            ((label.contains("prompt") || label.contains("describe")) &&
                                (element.isEditable || element.className.contains("EditText", ignoreCase = true))))
                }
            } else if (!targetInputName.isNullOrBlank()) {
                val editable = elementsForTyping.filter {
                    (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                        !it.viewId.contains("url_bar", ignoreCase = true) &&
                        !it.viewId.contains("omnibox", ignoreCase = true)
                }
                val requested = targetInputName.lowercase(Locale.ROOT).trim()
                val requestTokens = requested.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 2 }
                val matchingEditable = editable.filter { element ->
                    val labelTokens = "${element.primaryLabel} ${element.viewId}"
                        .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                    (requestTokens.isNotEmpty() && requestTokens.all { it in labelTokens }) ||
                        "${element.primaryLabel} ${element.viewId}".equals(targetInputName, ignoreCase = true)
                }
                when {
                    matchingEditable.size == 1 -> matchingEditable.single()
                    matchingEditable.size > 1 -> null
                    editable.size == 1 -> editable.single()
                    else -> {
                        val anchor = groundingEngine.findTargetElement(elementsForTyping, targetInputName)
                        val nearby = anchor?.let { label ->
                            editable.filter { abs(it.bounds.centerY() - label.bounds.centerY()) <= 180 }
                                .minByOrNull { abs(it.bounds.centerY() - label.bounds.centerY()) }
                        }
                        nearby
                    }
                }
            } else null
            found?.takeIf {
                (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                    !it.viewId.contains("url_bar", ignoreCase = true) &&
                    !it.viewId.contains("omnibox", ignoreCase = true)
            }?.bounds
        } catch (e: Exception) {
            null
        }

        // A fresh frame can take a moment after Android grants MediaProjection; wait briefly
        // before reporting that visual targeting is unavailable.
        if (isGoogleFlowPage && screenCaptureManager.isScreenShareActive() && !screenCaptureManager.hasRecentFrame()) {
            for (attempt in 0 until 20) {
                if (screenCaptureManager.hasRecentFrame()) break
                delay(100L)
            }
        }

        // DIRECT VISUAL COORDINATE FOCUS (fallback only when the real input could not be resolved):
        if (realInputRect != null) {
            targetRect = realInputRect
            Log.i(TAG, "Using real input bounds $realInputRect (ignoring model coordinates x=$x y=$y)")
        } else if (x != null && y != null && x in 0..1000 && y in 0..1000 &&
            (!(isGoogleFlowPage || isGoogleAiStudioPage) || screenCaptureManager.hasRecentFrame())) {
            val dm = service.resources.displayMetrics
            val physicalX = (x / 1000f) * dm.widthPixels
            val physicalY = (y / 1000f) * dm.heightPixels
            targetRect = Rect((physicalX - 40).toInt(), (physicalY - 30).toInt(), (physicalX + 40).toInt(), (physicalY + 30).toInt())
            Log.i(TAG, "Direct visual focus at normalized ($x, $y) -> targetRect $targetRect")
        } else if (isGoogleFlowPage || isGoogleAiStudioPage) {
            val product = if (isGoogleAiStudioPage) "Google AI Studio" else "Google Flow"
            val reason = if (!screenCaptureManager.hasRecentFrame()) {
                if (screenCaptureManager.isScreenShareActive()) {
                    "$product's prompt field is not exposed to accessibility. Screen Share is enabled, but Android has not delivered a fresh frame yet; wait for the preview to update and retry once."
                } else {
                    "$product's prompt field is not exposed to accessibility and no visual frame is available. Enable Screen Share once, then JARVIS can target the visible prompt field."
                }
            } else {
                "$product's prompt field could not be identified from the current frame. Keep the editor/composer visible and inspect the screen before retrying."
            }
            return@withContext TaskActionResult(false, reason, groundingEngine.formatElementsSummary(elementsForTyping, rootForTyping), verified = false)
        } else {
            val elements = elementsForTyping

            if (!targetInputName.isNullOrBlank()) {
                val editable = elements.filter {
                    (it.isEditable || it.className.contains("EditText", ignoreCase = true)) &&
                        !it.viewId.contains("url_bar", ignoreCase = true) &&
                        !it.viewId.contains("omnibox", ignoreCase = true)
                }
                val requestedTokens = targetInputName.lowercase(Locale.ROOT)
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 2 }
                if (editable.isEmpty()) {
                    return@withContext TaskActionResult(
                        success = false,
                        message = "The named input '$targetInputName' is not exposed as an editable field. No text was entered.",
                        currentStateSummary = groundingEngine.formatElementsSummary(elements, rootForTyping),
                        verified = false
                    )
                }
                if (editable.size > 1 && editable.none { element ->
                        val label = "${element.primaryLabel} ${element.viewId}".lowercase(Locale.ROOT)
                        label.contains(targetInputName.lowercase(Locale.ROOT))
                    }) {
                    val anchor = groundingEngine.findTargetElement(elements, targetInputName)
                    val nearby = anchor?.let { label ->
                        editable.filter { abs(it.bounds.centerY() - label.bounds.centerY()) <= 180 }
                            .minByOrNull { abs(it.bounds.centerY() - label.bounds.centerY()) }
                    }
                    if (nearby == null) {
                        return@withContext TaskActionResult(
                            success = false,
                            message = "Could not uniquely identify the '$targetInputName' input. No text was entered; inspect the current screen and name the exact field.",
                            currentStateSummary = groundingEngine.formatElementsSummary(elements, rootForTyping),
                            verified = false
                        )
                    }
                    targetRect = nearby.bounds
                } else {
                    val named = editable.filter { element ->
                        val labelTokens = "${element.primaryLabel} ${element.viewId}"
                            .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                        requestedTokens.isNotEmpty() && requestedTokens.all { it in labelTokens }
                    }
                    targetRect = (named.singleOrNull() ?: editable.singleOrNull())?.bounds
                    if (targetRect == null) {
                        return@withContext TaskActionResult(
                            success = false,
                            message = "Could not uniquely identify the '$targetInputName' input. No text was entered.",
                            currentStateSummary = groundingEngine.formatElementsSummary(elements, rootForTyping),
                            verified = false
                        )
                    }
                }
            } else {
                // Pick the best candidate input field (prefer web prompt placeholders or non-URL-bar inputs)
                val webPromptInput = elements.firstOrNull {
                    val label = it.primaryLabel.lowercase(Locale.ROOT)
                    (label.contains("what do you want to create") || label.contains("describe your") || label.contains("ask gemini") ||
                        (label.contains("prompt") && (it.isEditable || it.className.contains("EditText", ignoreCase = true)))) &&
                    !it.viewId.contains("url_bar", ignoreCase = true)
                }
                val nonUrlInputs = elements.filter { (it.isEditable || it.className.contains("EditText", ignoreCase = true)) && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
                val inputElement = webPromptInput ?: nonUrlInputs.firstOrNull() ?: elements.firstOrNull { it.isEditable && !it.viewId.contains("url_bar", ignoreCase = true) && !it.viewId.contains("omnibox", ignoreCase = true) }
                targetRect = inputElement?.bounds
            }
        }

        val browserBeforeTyping = browserPackage || isGoogleFlowPage
        // In a browser, submitting is a deliberate, separate step (tap the real Generate arrow after SEEING the text).
        val composeField = targetInputName?.lowercase(Locale.ROOT)?.let { name ->
            listOf("recipient", "email", "message", "compose", "subject", "body", "to").any(name::contains)
        } == true
        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val success = SerializedActionExecutor.execute(
            "type:${targetInputName ?: "focused-field"}",
            expectedGeneration = AgentTaskEngine.current()?.actionGeneration
        ) {
            service.smartTypeText(text, targetRect, submit && !browserBeforeTyping && !composeField)
        } == true
        val rootPkg = service.getActiveRootNode()?.packageName?.toString() ?: ""
        val isBrowser = browserBeforeTyping || rootPkg.contains("chrome", ignoreCase = true) || rootPkg.contains("browser", ignoreCase = true)
        val verifiedSuccess: Boolean
        val responseMessage: String
        val stateSummary: String

        // In Chrome, if smartTypeText was successful (via SET_TEXT, PASTE, or Gboard chip),
        // dismiss the soft keyboard so the page returns to full size and submit button isn't covered!
        // (Removed the unconditional GLOBAL_ACTION_BACK: when the keyboard was not open it navigated Chrome
        //  BACK out of the Flow project page. In Flow the Generate arrow stays visible above the keyboard.)
        if (success && isBrowser) {
            delay(300)
        }

        // AUTO-SUBMIT: Only if explicitly requested via submit=true
        // (Removed the blind tap at a fixed screen position for browsers: with the keyboard open it hit the
        //  keyboard Enter key instead of Generate. The model must tap the real Generate arrow it SEES.)
        if (success && submit && !isBrowser) {
            delay(120)
        }

        delay(400)
        dispatchImmediateScreenFrame()
        val rootAfter = service.getAppRootNode() ?: service.getActiveRootNode()
        val elementsAfter = if (rootAfter != null) groundingEngine.scanScreen(rootAfter) else emptyList()

        // A dispatched paste is not proof that a web prompt received the text. Require readback.
        val isVerified = if (isBrowser) {
            success && service.lastTypeVerified
        } else {
            val verification = com.jarvis.assistant.automation.agent.ActionVerifier.verifyTyping(
                service = service,
                targetRect = targetRect,
                expectedText = text
            )
            verification.isVerified
        }

        verifiedSuccess = success && isVerified
        recordObservation(service, groundingEngine.formatElementsSummary(elementsAfter, rootAfter))
        AgentTaskEngine.recordAction("type:${targetInputName ?: "input"}", if (verifiedSuccess) "field readback verified" else "field readback failed", verifiedSuccess)
        AgentTaskEngine.setState(if (verifiedSuccess) AgentTaskEngine.State.CONTINUING else AgentTaskEngine.State.RECOVERING)
        responseMessage = if (verifiedSuccess) {
            "Text entered and verified in the named input. Inspect the fresh screen; submit only by clicking the visible intended control when the user explicitly requested submission."
        } else if (success && !isVerified && isBrowser) {
            "NOT CONFIRMED: the text was sent but is NOT visible in the page input. Do NOT say it was typed and do NOT tap Generate. LOOK at the latest screen frame: if the prompt box still shows its placeholder, tap inside the prompt box ONCE, then call type_text again. If it fails twice, tell the user honestly."
        } else if (success && !isVerified) {
            "FAILED: Text input was attempted, but could NOT be verified in the input field. NEXT ACTION: Tap the input box directly to focus it, then retry."
        } else if (isBrowser) {
            "FAILED: No page input box was found (the address bar is never used). If this is the Flow homepage there is NO prompt box yet: tap '+ New project' first, wait for the editor, then call type_text."
        } else {
            "FAILED: Could not type into input field. Tap the text field first, then call type_text."
        }
        stateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)

        return@withContext TaskActionResult(
            success = verifiedSuccess,
            message = responseMessage,
            currentStateSummary = stateSummary,
            verified = verifiedSuccess
        )
    }

    private fun verifyTextInTree(service: JarvisAccessibilityService, snippet: String): Boolean {
        val root = service.getAppRootNode() ?: service.getActiveRootNode()
        if (root != null && verifyTextInNode(root, snippet)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (window in service.windows) {
                val wRoot = window.root ?: continue
                if (verifyTextInNode(wRoot, snippet)) return true
            }
        }
        return false
    }

    private fun verifyTextInNode(node: AccessibilityNodeInfo, snippet: String): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(snippet) || desc.contains(snippet)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (verifyTextInNode(child, snippet)) return true
        }
        return false
    }

    /**
     * Performs a directional scroll with loop guard.
     */
    suspend fun handleScroll(direction: String, distance: String = "small"): TaskActionResult = withContext(Dispatchers.IO) {
        AgentTaskEngine.setState(AgentTaskEngine.State.OBSERVING)
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service not running.")
        }

        consecutiveScrollCount++
        if (consecutiveScrollCount > 15) {
            resetScrollCount()
            val root = service.getActiveRootNode()
            val elements = groundingEngine.scanScreen(root)
            val (pkg, title) = groundingEngine.extractScreenContext(root, elements)
            return@withContext TaskActionResult(
                success = false,
                message = "Scrolled 15 times in a row without finding target. You are currently in app '$pkg' on screen '$title'. If the item is not here, try navigating to another section or folder.",
                currentStateSummary = groundingEngine.formatElementsSummary(elements, root)
            )
        }

        AgentTaskEngine.setState(AgentTaskEngine.State.ACTING)
        val preSignature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
        val success = mutateUi("scroll:$direction") { service.performSmartScroll(direction, distance) }
        AgentTaskEngine.current()?.scrollCount = (AgentTaskEngine.current()?.scrollCount ?: 0) + 1
        dispatchImmediateScreenFrame()
        val uiWait = waitForUiState(preSignature, expectedPackage = service.getActiveRootNode()?.packageName?.toString(), timeoutMs = 8_000L)
        val rootAfter = uiWait.root ?: service.getActiveRootNode()
        val postSignature = uiWait.signature ?: com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(rootAfter)
        if (postSignature.isEquivalentTo(preSignature)) {
            AgentTaskEngine.current()?.noProgressCount = (AgentTaskEngine.current()?.noProgressCount ?: 0) + 1
        } else {
            AgentTaskEngine.current()?.noProgressCount = 0
        }
        recordObservation(service)
        AgentTaskEngine.recordAction("scroll:$direction", if (success) "scroll dispatched" else "scroll not dispatched", success && !postSignature.isEquivalentTo(preSignature))
        AgentTaskEngine.setState(AgentTaskEngine.State.CONTINUING)
        val elementsAfter = groundingEngine.scanScreen(rootAfter)
        val dynamicElements = elementsAfter.filter { it.bounds.top > 350 }
        val currentSignature = if (dynamicElements.isNotEmpty()) {
            dynamicElements.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        } else {
            elementsAfter.takeLast(6).joinToString("|") { "${it.id}:${it.primaryLabel}@${it.bounds.top}" }
        }

        if (currentSignature.isNotBlank() && currentSignature == lastScreenSignature) {
            duplicateSignatureCount++
            if (duplicateSignatureCount >= 2) {
                resetScrollCount()
                val (pkg, title) = groundingEngine.extractScreenContext(rootAfter, elementsAfter)
                return@withContext TaskActionResult(
                    success = false,
                    message = "Reached end of screen in direction '$direction'. Cannot scroll further in this view. Current screen: '$title' in app '$pkg'. If looking for a photo/document, try opening another folder like 'WhatsApp Images', 'Screenshots', or 'Downloads'.",
                    currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter)
                )
            }
        } else {
            duplicateSignatureCount = 0
        }
        lastScreenSignature = currentSignature

        return@withContext TaskActionResult(
            success = success && uiWait.ready,
            message = "Scrolled screen $direction (scroll #$consecutiveScrollCount, distance: $distance).",
            currentStateSummary = groundingEngine.formatElementsSummary(elementsAfter, rootAfter),
            verified = success && uiWait.ready && !postSignature.isEquivalentTo(preSignature)
        )
    }

    /**
     * Dispatches system navigation keys (back, home, recents).
     */
    suspend fun handleKey(key: String): TaskActionResult = withContext(Dispatchers.IO) {
        resetScrollCount()
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return@withContext TaskActionResult(false, "Accessibility Service not running.")
        }

        val success = mutateUi("navigation:$key") { service.performSystemGesture(key) }
        delay(400)
        dispatchImmediateScreenFrame()
        return@withContext TaskActionResult(
            success = success,
            message = "Pressed system key '$key'.",
            currentStateSummary = captureCurrentStateSummary(service)
        )
    }

    /**
     * Inspects the current screen state in real time and returns a detailed summary
     * of the active foreground app, screen title, and visible content/elements.
     */
    suspend fun inspectScreen(waitForContent: Boolean = false): String = withContext(Dispatchers.IO) {
        val service = JarvisAccessibilityService.instance
            ?: return@withContext "Screen context unavailable: Accessibility Service is not active."
        var root = service.getActiveRootNode()
        var elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()

        if (waitForContent) {
            var attempts = 0
            while (attempts < 4 && elements.none { it.text.isNotBlank() || it.contentDescription.isNotBlank() }) {
                delay(250)
                root = service.getActiveRootNode()
                elements = if (root != null) groundingEngine.scanScreen(root) else emptyList()
                attempts++
            }
        }

        if (root == null && elements.isEmpty()) {
            return@withContext "Screen context unavailable: Screen is blank or inaccessible."
        }

        dispatchImmediateScreenFrame()
        val recentOtp = JarvisAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        val screenSource = if (screenCaptureManager.hasRecentFrame()) {
            "[Screen share active: a recent visual frame is being streamed.]\n"
        } else {
            val flowDetected = groundingEngine.isGoogleFlowSurface(elements)
            if (flowDetected) {
                "[Google Flow detected. Current labeled controls can be used from accessibility. Screen coordinates require a fresh Screen Share frame; if the requested control has no accessible label, explain that limitation once.]\n"
            } else {
                "[Current accessibility labels are available. Use a unique labeled target for actions; image-only and unlabeled controls are unavailable.]\n"
            }
        }
        val summary = screenSource + groundingEngine.formatElementsSummary(elements, root) + otpNotice
        Log.i(TAG, "Screen inspection generated:\n$summary")
        return@withContext summary
    }

    private fun captureCurrentStateSummary(service: JarvisAccessibilityService): String {
        val root = service.getActiveRootNode() ?: return "Screen empty or not accessible."
        val elements = groundingEngine.scanScreen(root)
        val recentOtp = JarvisAccessibilityService.getRecentOtp()
        val otpNotice = if (recentOtp != null) "\n[SYSTEM NOTICE: Detected incoming SMS/Notification OTP: '$recentOtp' (available for auto-entry)]" else ""
        return groundingEngine.formatElementsSummary(elements, root) + otpNotice
    }

    private fun isTogglePhrase(phrase: String): Boolean {
        val lower = phrase.lowercase(Locale.ROOT)
        return lower.contains("switch") || lower.contains("toggle") ||
                lower.contains("enable") || lower.contains("disable") ||
                lower.contains("turn on") || lower.contains("turn off")
    }

    private fun isSensitiveTarget(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("pay") || combined.contains("payment") ||
                combined.contains("delete all") || combined.contains("factory reset") ||
                combined.contains("format") || combined.contains("purchase") ||
                combined.contains("transfer money") || combined.contains("send money")
    }

    private fun isClearSearchButton(target: String, desc: String?): Boolean {
        val combined = "$target ${desc ?: ""}".lowercase(Locale.ROOT)
        return combined.contains("clear search") || combined.contains("clear query") ||
                combined.contains("clear text") || combined.contains("clear input")
    }

    private fun isBrowserSearchScreen(service: JarvisAccessibilityService): Boolean {
        val root = service.getAppRootNode() ?: service.getActiveRootNode() ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (!pkg.contains("chrome", ignoreCase = true) && !pkg.contains("browser", ignoreCase = true)) {
            return false
        }
        val elements = groundingEngine.scanScreen(root)
        return groundingEngine.isGoogleSearchResults(elements)
    }

    private fun isBrowserOverflowMenu(target: UiElementInfo?): Boolean {
        if (target == null) return false
        val text = target.primaryLabel.lowercase(Locale.ROOT)
        val vid = target.viewId.lowercase(Locale.ROOT)
        val desc = target.contentDescription.lowercase(Locale.ROOT)
        return desc.contains("more options") ||
               desc.contains("customize and control") ||
               text.contains("more options") ||
               vid.contains("menu_button") ||
               vid.contains("more_menu") ||
               vid.contains("overflow")
    }
}
