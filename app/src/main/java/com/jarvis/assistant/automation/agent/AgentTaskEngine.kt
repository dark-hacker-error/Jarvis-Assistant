package com.jarvis.assistant.automation.agent

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Authoritative in-process state for the active, user-requested accessibility task. */
object AgentTaskEngine {
    private const val TAG = "AgentTaskEngine"

    enum class State {
        IDLE, UNDERSTANDING, PLANNING, OBSERVING, RESOLVING_TARGET, ACTING,
        VERIFYING, CONTINUING, REPLANNING, RECOVERING, WAITING_FOR_UI,
        PAUSED, BLOCKED, COMPLETED, FAILED, CANCELLED
    }

    enum class TaskScope { SINGLE_TARGET, ALL_MATCHING, SEARCH_UNTIL_FOUND }
    enum class CompletionEvidence { UNKNOWN, VERIFIED_GOAL, TARGET_NOT_FOUND, END_REACHED, NO_PROGRESS, LOADING, NEEDS_LOGIN, AMBIGUOUS, SAFETY_LIMIT, CANCELLED }
    data class LoopOutcome(val iterations: Int, val reachedLimit: Boolean, val cancelled: Boolean, val error: String? = null)

    data class Context(
        val taskId: String,
        val originalGoal: String,
        val normalizedGoal: String,
        val taskScope: TaskScope,
        val origin: String,
        val actionGeneration: Long,
        var currentApp: String? = null,
        var targetApp: String? = null,
        val plan: MutableList<String> = mutableListOf(),
        var currentStep: String? = null,
        val completedSteps: MutableList<String> = mutableListOf(),
        val pendingSteps: MutableList<String> = mutableListOf(),
        val steps: MutableList<TaskPlanStep> = mutableListOf(),
        var currentStepId: String? = null,
        var recoveryCount: Int = 0,
        var finalVerificationResult: String? = null,
        val processedItemIds: MutableSet<String> = mutableSetOf(),
        val processedItemSignatures: MutableSet<String> = mutableSetOf(),
        var lastAction: String? = null,
        var lastActionResult: String? = null,
        var lastActionVerified: Boolean = false,
        var lastObservation: String? = null,
        var currentScreenSignature: ScreenSignature? = null,
        var previousScreenSignature: ScreenSignature? = null,
        var retryCount: Int = 0,
        var actionRetryCount: Int = 0,
        var scrollCount: Int = 0,
        var noProgressCount: Int = 0,
        var consecutiveFailureCount: Int = 0,
        val startTime: Long = System.currentTimeMillis(),
        var lastProgressTime: Long = startTime,
        var status: State = State.UNDERSTANDING,
        var failureReason: String? = null,
        var completionEvidence: CompletionEvidence = CompletionEvidence.UNKNOWN,
        var completionEvidenceDetail: String? = null,
        @Volatile var cancellationRequested: Boolean = false
    )

    @Volatile private var active: Context? = null
    private val _taskState = MutableStateFlow<AgentTaskSnapshot?>(null)
    val taskState: StateFlow<AgentTaskSnapshot?> = _taskState.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val _controlRevision = MutableStateFlow(0L)
    @Volatile private var resumeObservationRequired = false
    private val executionLoopMutex = Mutex()

    @Synchronized
    fun begin(goal: String, scope: String = "accessibility") : Context {
        val current = active
        if (current != null && current.status !in setOf(State.COMPLETED, State.FAILED, State.CANCELLED)) {
            if (goal.isBlank() || current.normalizedGoal == normalize(goal)) return current
        }
        val actionGeneration = SerializedActionExecutor.startNewTask()
        val normalized = normalize(goal)
        val taskScope = when {
            TaskGoal.fromUserUtterance(goal).isBulkAction -> TaskScope.ALL_MATCHING
            listOf("search", "find", "look for", "find me", "dhoondh", "dhundo", "talaash")
                .any { normalized.contains(it) } -> TaskScope.SEARCH_UNTIL_FOUND
            else -> TaskScope.SINGLE_TARGET
        }
        return Context(UUID.randomUUID().toString(), goal, normalized, taskScope, scope, actionGeneration).also { context ->
            val titles = planTitles(goal)
            context.plan.addAll(titles)
            titles.forEachIndexed { index, title ->
                context.steps += TaskPlanStep(
                    title = title,
                    description = planDescription(title, goal),
                    status = if (index == 0) TaskStepStatus.COMPLETED else TaskStepStatus.PENDING,
                    dependencies = if (index == 0) emptyList() else listOf(context.steps[index - 1].stepId),
                    expectedResult = expectedResult(title),
                    actualResult = if (index == 0) "Goal parsed and task scope classified" else null,
                    startedAt = if (index == 0) context.startTime else null,
                    completedAt = if (index == 0) context.startTime else null
                )
            }
            context.completedSteps += titles.firstOrNull().orEmpty()
            context.pendingSteps.addAll(titles.drop(1))
            active = context
            _isPaused.value = false
            resumeObservationRequired = false
            startNextReady(context)
            publish(context)
        }
    }

    fun current(): Context? = active

    /** Clears task state when a live Jarvis session is explicitly stopped or restarted. */
    @Synchronized
    fun resetForSession() {
        active?.let { it.cancellationRequested = true }
        SerializedActionExecutor.cancelAllPendingActions()
        active = null
        _taskState.value = null
        _isPaused.value = false
        resumeObservationRequired = false
        _controlRevision.value += 1
    }

    /** The single task-owned gate for every serialized accessibility/UI mutation. */
    suspend fun executeUiMutation(action: String, operation: suspend () -> Boolean): Boolean {
        val context = active
        if (context?.cancellationRequested == true) return false
        if (context?.status == State.BLOCKED) return false
        if (!awaitIfRunnable()) return false
        if (context != null && resumeObservationRequired) {
            val expectedBeforePause = context.currentScreenSignature
            val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
            val root = service?.getActiveRootNode()
            if (root == null) return false
            val freshSignature = ScreenSignatureCalculator.computeSignature(root)
            observe(freshSignature, root.packageName?.toString(), "Fresh accessibility observation after resume")
            resumeObservationRequired = false
            if (expectedBeforePause != null && !freshSignature.isEquivalentTo(expectedBeforePause)) {
                context.lastAction = action
                context.lastActionResult = "Action rejected because the UI changed while paused; target must be resolved again from the fresh screen"
                context.lastActionVerified = false
                context.status = State.REPLANNING
                context.recoveryCount++
                publish(context)
                return false
            }
        }
        context?.let { if (it.status !in setOf(State.COMPLETED, State.FAILED, State.CANCELLED)) it.status = State.ACTING }
        val executed = SerializedActionExecutor.execute(
            action,
            expectedGeneration = context?.actionGeneration,
            block = { if (awaitIfRunnable()) operation() else false }
        ) == true
        if (!executed && context != null && !context.cancellationRequested) {
            synchronized(this) {
                active?.takeIf { it.taskId == context.taskId }?.let {
                    it.consecutiveFailureCount++
                    it.lastAction = action
                    it.lastActionResult = "UI mutation was rejected, cancelled, or failed before verification"
                    it.lastActionVerified = false
                    it.status = State.RECOVERING
                }
            }
        }
        publishIfCurrent(context)
        return executed
    }

    @Synchronized
    fun setState(state: State) {
        active?.let {
            if (!it.cancellationRequested) {
                it.status = state
                val step = it.steps.firstOrNull { step -> step.stepId == it.currentStepId }
                val stepStatus = when (state) {
                    State.WAITING_FOR_UI -> TaskStepStatus.WAITING_FOR_UI
                    State.VERIFYING -> TaskStepStatus.VERIFYING
                    State.RECOVERING, State.REPLANNING -> TaskStepStatus.RECOVERING
                    State.BLOCKED -> TaskStepStatus.BLOCKED
                    State.FAILED -> TaskStepStatus.FAILED
                    State.CANCELLED -> TaskStepStatus.CANCELLED
                    State.COMPLETED -> TaskStepStatus.COMPLETED
                    State.PAUSED -> TaskStepStatus.PENDING
                    State.ACTING, State.RESOLVING_TARGET, State.OBSERVING, State.CONTINUING -> TaskStepStatus.RUNNING
                    else -> null
                }
                if (step != null && stepStatus != null && step.status != TaskStepStatus.COMPLETED) {
                    replaceStep(it, step.copy(status = stepStatus, startedAt = step.startedAt ?: System.currentTimeMillis()))
                }
                publish(it)
            }
        }
    }

    @Synchronized
    fun observe(signature: ScreenSignature?, packageName: String?, summary: String? = null) {
        val context = active ?: return
        context.previousScreenSignature = context.currentScreenSignature
        context.currentScreenSignature = signature
        context.currentApp = packageName
        context.lastObservation = summary
        if (signature != null && context.previousScreenSignature?.isEquivalentTo(signature) == false) {
            context.noProgressCount = 0
            context.lastProgressTime = System.currentTimeMillis()
        }
        publish(context)
    }

    @Synchronized
    fun recordAction(action: String, result: String, verified: Boolean) {
        val context = active ?: return
        context.lastAction = action
        context.lastActionResult = result
        context.lastActionVerified = verified
        if (verified) {
            context.consecutiveFailureCount = 0
            context.lastProgressTime = System.currentTimeMillis()
        } else context.consecutiveFailureCount++
        publish(context)
    }

    fun requestCancellation() {
        val context = active ?: return
        context.cancellationRequested = true
        context.status = State.CANCELLED
        _isPaused.value = false
        _controlRevision.value += 1
        context.steps.indices.forEach { index ->
            val step = context.steps[index]
            if (step.status !in setOf(TaskStepStatus.COMPLETED, TaskStepStatus.FAILED)) {
                context.steps[index] = step.copy(status = TaskStepStatus.CANCELLED, failureReason = "Task cancelled")
            }
        }
        SerializedActionExecutor.cancelAllPendingActions()
        publish(context)
        Log.i(TAG, "Task ${context.taskId} cancellation requested")
    }

    @Synchronized
    fun finish(state: State, reason: String? = null): Boolean {
        val context = active ?: return false
        val bulkEndVerified = context.taskScope == TaskScope.ALL_MATCHING &&
            context.completionEvidence == CompletionEvidence.END_REACHED &&
            context.processedItemSignatures.isNotEmpty()
        if (state == State.COMPLETED && context.completionEvidence != CompletionEvidence.VERIFIED_GOAL && !bulkEndVerified) {
            context.failureReason = "Completion rejected: local goal evidence is ${context.completionEvidence}."
            return false
        }
        context.status = state
        context.failureReason = reason
        if (state == State.COMPLETED) {
            context.finalVerificationResult = context.completionEvidenceDetail
            context.steps.indices.forEach { index ->
                val step = context.steps[index]
                if (step.status == TaskStepStatus.RUNNING || step.status == TaskStepStatus.VERIFYING) {
                    context.steps[index] = step.copy(status = TaskStepStatus.COMPLETED, actualResult = context.completionEvidenceDetail, completedAt = System.currentTimeMillis())
                    if (step.title !in context.completedSteps) context.completedSteps += step.title
                }
            }
        } else if (state == State.FAILED || state == State.BLOCKED) {
            context.steps.firstOrNull { it.status !in setOf(TaskStepStatus.COMPLETED, TaskStepStatus.CANCELLED) }?.let { step ->
                replaceStep(context, step.copy(status = if (state == State.BLOCKED) TaskStepStatus.BLOCKED else TaskStepStatus.FAILED, failureReason = reason))
            }
        }
        publish(context)
        return true
    }

    fun isCancelled(): Boolean = active?.cancellationRequested == true

    fun pauseTask(): Boolean {
        val context = active ?: return false
        if (context.status in setOf(State.COMPLETED, State.FAILED, State.CANCELLED, State.BLOCKED)) return false
        _isPaused.value = true
        context.status = State.PAUSED
        _controlRevision.value += 1
        publish(context)
        return true
    }

    fun resumeTask(): Boolean {
        val context = active ?: return false
        if (!_isPaused.value || context.cancellationRequested) return false
        _isPaused.value = false
        context.status = State.OBSERVING
        context.lastObservation = "Resumed; fresh screen observation required before the next action"
        resumeObservationRequired = true
        _controlRevision.value += 1
        publish(context)
        return true
    }

    @Synchronized
    fun resumeAfterUserInput(): Boolean {
        val context = active ?: return false
        if (context.cancellationRequested || context.status in setOf(State.COMPLETED, State.FAILED, State.CANCELLED)) return false
        context.steps.firstOrNull { it.stepId == context.currentStepId }?.let { step ->
            if (step.status in setOf(TaskStepStatus.BLOCKED, TaskStepStatus.RECOVERING, TaskStepStatus.WAITING_FOR_UI)) {
                replaceStep(context, step.copy(status = TaskStepStatus.RUNNING, failureReason = null))
            }
        }
        context.failureReason = null
        context.status = State.OBSERVING
        context.lastObservation = "User input received; fresh screen observation required before resuming"
        resumeObservationRequired = true
        publish(context)
        return true
    }

    suspend fun awaitIfRunnable(): Boolean {
        while (_isPaused.value && !isCancelled()) {
            val observedRevision = _controlRevision.value
            _controlRevision.first { it != observedRevision }
        }
        return !isCancelled()
    }

    /** One engine-owned, non-recursive loop for an app-side deterministic workflow. */
    suspend fun runAutonomousLoop(
        maxIterations: Int,
        step: suspend (iteration: Int, task: Context) -> Boolean
    ): LoopOutcome {
        if (!executionLoopMutex.tryLock()) return LoopOutcome(0, false, false, "Another task loop already owns execution")
        val taskId = active?.taskId ?: run {
            executionLoopMutex.unlock()
            return LoopOutcome(0, false, false, "No active task")
        }
        var iterations = 0
        try {
            while (iterations < maxIterations.coerceAtLeast(1)) {
                if (!awaitIfRunnable()) return LoopOutcome(iterations, false, true)
                val context = active?.takeIf { it.taskId == taskId } ?: return LoopOutcome(iterations, false, true, "Task was replaced")
                setState(State.OBSERVING)
                val shouldContinue = step(iterations, context)
                iterations++
                if (isCancelled()) return LoopOutcome(iterations, false, true)
                if (!shouldContinue) return LoopOutcome(iterations, false, false)
                if (active?.taskId != taskId) return LoopOutcome(iterations, false, true, "Task was replaced")
                if (active?.status in setOf(State.COMPLETED, State.FAILED, State.CANCELLED, State.BLOCKED)) {
                    return LoopOutcome(iterations, false, false, active?.failureReason)
                }
            }
            recordCompletionEvidence(CompletionEvidence.SAFETY_LIMIT, "App-controlled task loop reached its $maxIterations iteration limit")
            finish(State.BLOCKED, "Task loop reached its safety limit; remaining steps were not marked complete")
            return LoopOutcome(iterations, true, false, "Task loop reached its safety limit")
        } catch (cancelled: CancellationException) {
            if (isCancelled()) return LoopOutcome(iterations, false, true)
            throw cancelled
        } catch (error: Exception) {
            failCurrentStep(error.message ?: "Task-loop step failed")
            return LoopOutcome(iterations, false, false, error.message ?: "Task-loop step failed")
        } finally {
            executionLoopMutex.unlock()
        }
    }

    @Synchronized
    fun markItemProcessed(identity: String, verified: Boolean): Boolean {
        if (!verified || identity.isBlank()) return false
        val context = active ?: return false
        if (!context.processedItemSignatures.add(identity)) return false
        context.processedItemIds.add(identity)
        publish(context)
        return true
    }

    @Synchronized
    fun recordCompletionEvidence(evidence: CompletionEvidence, detail: String) {
        active?.let {
            it.completionEvidence = evidence
            it.completionEvidenceDetail = detail
            it.finalVerificationResult = detail.takeIf { evidence == CompletionEvidence.VERIFIED_GOAL || evidence == CompletionEvidence.END_REACHED }
            publish(it)
        }
    }

    @Synchronized
    fun replacePlan(titles: List<String>) {
        val context = active ?: return
        val preserved = context.steps.filter { it.status == TaskStepStatus.COMPLETED }
        val completedTitles = preserved.map { it.title }.toSet()
        val plan = (preserved.map { it.title } + titles.filterNot { it in completedTitles }).distinct()
        context.plan.clear()
        context.plan.addAll(plan)
        context.steps.clear()
        plan.forEachIndexed { index, title ->
            val old = preserved.firstOrNull { it.title == title }
            val previousId = context.steps.lastOrNull()?.stepId
            context.steps += old ?: TaskPlanStep(
                title = title,
                description = planDescription(title, context.originalGoal),
                dependencies = if (index == 0 || previousId == null) emptyList() else listOf(previousId),
                expectedResult = expectedResult(title)
            )
        }
        context.completedSteps.clear()
        context.completedSteps.addAll(preserved.map { it.title })
        context.pendingSteps.clear()
        context.pendingSteps.addAll(context.steps.filter { it.status != TaskStepStatus.COMPLETED }.map { it.title })
        context.currentStepId = null
        startNextReady(context)
        publish(context)
    }

    @Synchronized
    fun updateActivity(activity: String, state: State = State.OBSERVING) {
        val context = active ?: return
        context.currentStep = activity
        context.lastObservation = activity
        context.status = state
        val currentStep = context.steps.firstOrNull { it.stepId == context.currentStepId }
        if (currentStep != null && currentStep.status != TaskStepStatus.COMPLETED) {
            val mapped = if (state == State.WAITING_FOR_UI) TaskStepStatus.WAITING_FOR_UI else if (state == State.RECOVERING) TaskStepStatus.RECOVERING else currentStep.status
            replaceStep(context, currentStep.copy(status = mapped, actualResult = activity))
        }
        publish(context)
    }

    @Synchronized
    fun completeStepByTitle(title: String, result: String, verified: Boolean): Boolean {
        val context = active ?: return false
        val step = context.steps.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: return false
        if (!verified) {
            replaceStep(context, step.copy(status = TaskStepStatus.RECOVERING, actualResult = result, failureReason = result))
            context.recoveryCount++
            publish(context)
            return false
        }
        val completedIds = context.steps.filter { it.status == TaskStepStatus.COMPLETED }.map { it.stepId }.toSet()
        if (!step.dependencies.all { it in completedIds }) return false
        replaceStep(context, step.copy(status = TaskStepStatus.COMPLETED, actualResult = result, completedAt = System.currentTimeMillis(), failureReason = null))
        if (step.title !in context.completedSteps) context.completedSteps += step.title
        context.pendingSteps.remove(step.title)
        if (context.currentStepId == step.stepId) context.currentStepId = null
        startNextReady(context)
        context.lastProgressTime = System.currentTimeMillis()
        publish(context)
        return true
    }

    @Synchronized
    fun failCurrentStep(reason: String, blocked: Boolean = false) {
        val context = active ?: return
        val current = context.steps.firstOrNull { it.stepId == context.currentStepId }
        if (current != null && current.status != TaskStepStatus.COMPLETED) {
            replaceStep(context, current.copy(
                status = if (blocked) TaskStepStatus.BLOCKED else TaskStepStatus.RECOVERING,
                retryCount = current.retryCount + 1,
                failureReason = reason
            ))
        }
        context.retryCount++
        context.recoveryCount++
        context.failureReason = reason
        context.status = if (blocked) State.BLOCKED else State.RECOVERING
        publish(context)
    }

    @Synchronized
    fun recordVerifiedStep(action: String, result: String, verified: Boolean) {
        val context = active ?: return
        context.lastAction = action
        context.lastActionResult = result
        context.lastActionVerified = verified
        val step = context.steps.firstOrNull { it.stepId == context.currentStepId } ?: return publish(context)
        val normalizedAction = action.lowercase()
        val title = step.title.lowercase()
        val relevant = when {
            normalizedAction.contains("open_app") -> title.contains("open") || title.contains("foreground")
            normalizedAction.contains("search") || normalizedAction.contains("type_text") -> title.contains("search") || title.contains("find")
            normalizedAction.contains("scroll") -> title.contains("scroll") || title.contains("load")
            normalizedAction.contains("click") -> title.contains("navigate") || title.contains("select") || title.contains("perform") || title.contains("unlike")
            normalizedAction.contains("task_completed") -> title.contains("verify") || title.contains("finish")
            else -> false
        }
        if (relevant && verified) {
            replaceStep(context, step.copy(status = TaskStepStatus.COMPLETED, actualResult = result, completedAt = System.currentTimeMillis(), failureReason = null))
            if (step.title !in context.completedSteps) context.completedSteps += step.title
            context.pendingSteps.remove(step.title)
            context.currentStepId = null
            startNextReady(context)
            context.lastProgressTime = System.currentTimeMillis()
        } else if (relevant && !verified) {
            val retries = step.retryCount + 1
            replaceStep(context, step.copy(status = if (retries >= 3) TaskStepStatus.BLOCKED else TaskStepStatus.RECOVERING,
                retryCount = retries, failureReason = result))
            context.retryCount++
            if (retries >= 3) context.status = State.BLOCKED else context.recoveryCount++
        }
        publish(context)
    }

    private fun planTitles(goal: String): List<String> {
        val text = goal.lowercase()
        return when {
            text.contains("instagram") && (text.contains("unlike") || text.contains("liked")) -> listOf(
                "Understand goal", "Open Instagram", "Navigate to liked content", "Observe and process matching items",
                "Scroll and wait for more items", "Verify list end and results"
            )
            text.contains("spotify") && (text.contains("play") || text.contains("find") || text.contains("search")) -> listOf(
                "Understand goal", "Open Spotify", "Search for the requested track", "Select one unique match", "Verify playback"
            )
            else -> listOf("Understand goal", "Open or confirm the target app", "Perform the requested action", "Verify the resulting state")
        }
    }

    private fun planDescription(title: String, goal: String): String = when (title) {
        "Understand goal" -> "Classify the request and identify the requested outcome."
        "Open Instagram" -> "Bring Instagram to the foreground and confirm it is active."
        "Open Spotify" -> "Bring Spotify to the foreground and confirm it is active."
        "Navigate to liked content" -> "Reach an identifiable liked-items list before changing items."
        "Observe and process matching items" -> "Resolve each item from a fresh accessibility observation and verify each change."
        "Scroll and wait for more items" -> "Scroll only after visible matches are handled, then wait for fresh content."
        "Search for the requested track" -> "Search the exact requested title and inspect current results."
        "Select one unique match" -> "Select only a uniquely supported match; stop if results are ambiguous."
        "Verify playback" -> "Confirm the requested track and active playback state are visible."
        else -> "Execute and verify the user's request: $goal"
    }

    private fun expectedResult(title: String): String = when {
        title.contains("Open") -> "Expected app is confirmed in the foreground"
        title.contains("liked") -> "The liked-items list is identified"
        title.contains("process") -> "Each processed item has a verified post-action state"
        title.contains("Scroll") -> "Fresh content is observed or end evidence is established"
        title.contains("Search") -> "The requested search query is visible in fresh results"
        title.contains("Select") -> "One unambiguous requested track is selected"
        title.contains("playback", true) -> "Requested track and active playback are visible"
        title.contains("Verify") -> "The requested outcome has local evidence"
        else -> "The expected result is visible in a fresh observation"
    }

    private fun startNextReady(context: Context) {
        if (context.currentStepId != null) return
        val completed = context.steps.filter { it.status == TaskStepStatus.COMPLETED }.map { it.stepId }.toSet()
        val next = context.steps.firstOrNull { step ->
            step.status in setOf(TaskStepStatus.PENDING, TaskStepStatus.READY) && step.dependencies.all { it in completed }
        } ?: return
        val running = next.copy(status = TaskStepStatus.RUNNING, startedAt = next.startedAt ?: System.currentTimeMillis())
        replaceStep(context, running)
        context.currentStepId = running.stepId
        context.currentStep = running.title
    }

    private fun replaceStep(context: Context, updated: TaskPlanStep) {
        val index = context.steps.indexOfFirst { it.stepId == updated.stepId }
        if (index >= 0) context.steps[index] = updated
    }

    @Synchronized
    private fun publishIfCurrent(context: Context?) {
        if (context != null && active?.taskId == context.taskId) publish(context)
    }

    private fun publish(context: Context) {
        val completed = context.steps.count { it.status == TaskStepStatus.COMPLETED }
        val progress = if (context.steps.isEmpty()) 0 else (completed * 100 / context.steps.size)
        _taskState.value = AgentTaskSnapshot(
            taskId = context.taskId,
            originalGoal = context.originalGoal,
            normalizedGoal = context.normalizedGoal,
            currentPlan = context.plan.joinToString(" → "),
            currentStepId = context.currentStepId,
            steps = context.steps.toList(),
            completedSteps = context.steps.filter { it.status == TaskStepStatus.COMPLETED }.map { it.title },
            pendingSteps = context.steps.filter { it.status != TaskStepStatus.COMPLETED }.map { it.title },
            processedItems = context.processedItemIds.toSet(),
            currentObservation = context.lastObservation,
            lastAction = context.lastAction,
            lastActionResult = context.lastActionResult,
            currentStatus = context.status.name,
            progressPercentage = progress,
            retryCount = context.retryCount,
            recoveryCount = context.recoveryCount,
            lastProgressTimestamp = context.lastProgressTime,
            cancellationRequested = context.cancellationRequested,
            finalVerificationResult = context.finalVerificationResult,
            failureReason = context.failureReason
        )
    }

    fun normalize(goal: String): String = goal.trim().lowercase().replace(Regex("\\s+"), " ")
}
