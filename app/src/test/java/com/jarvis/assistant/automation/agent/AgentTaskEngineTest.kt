package com.jarvis.assistant.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskEngineTest {

    @Test
    fun instagramPlanHasOrderedDependenciesAndOnlyVerifiedStepsAdvance() {
        val context = AgentTaskEngine.begin("Instagram unlike all my liked reels", "unit-test-plan")
        val initial = AgentTaskEngine.taskState.value
        assertNotNull(initial)
        assertEquals("Understand goal", initial!!.steps.first().title)
        assertEquals(TaskStepStatus.COMPLETED, initial.steps.first().status)
        assertEquals("Open Instagram", initial.steps.first { it.stepId == initial.currentStepId }.title)

        assertFalse(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "page seen", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Instagram", "foreground verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "liked list identified", verified = true))
        assertEquals("Observe and process matching items", AgentTaskEngine.taskState.value?.steps
            ?.first { it.stepId == AgentTaskEngine.taskState.value?.currentStepId }?.title)
        assertEquals(context.steps.size, AgentTaskEngine.taskState.value?.steps?.size)
    }

    @Test
    fun completionRequiresEvidenceAndBulkIdentitiesAreDeduplicated() {
        val context = AgentTaskEngine.begin("Instagram unlike all liked reels", "unit-test-completion")
        assertFalse(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        assertTrue(AgentTaskEngine.markItemProcessed("reel:one", verified = true))
        assertFalse(AgentTaskEngine.markItemProcessed("reel:one", verified = true))
        assertFalse(AgentTaskEngine.markItemProcessed("reel:two", verified = false))
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Instagram", "foreground verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Navigate to liked content", "liked list identified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Observe and process matching items", "one item verified", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Scroll and wait for more items", "stable end observed", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Verify list end and results", "final evidence recorded", verified = true))
        AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.END_REACHED, "stable end evidence")
        assertTrue(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        val state = AgentTaskEngine.taskState.value
        assertEquals(100, state?.progressPercentage)
        assertEquals(1, state?.processedItems?.size)
        assertEquals("stable end evidence", state?.finalVerificationResult)
        assertEquals(AgentTaskEngine.State.COMPLETED.name, state?.currentStatus)
        assertEquals(context.taskId, state?.taskId)
    }

    @Test
    fun spotifyPlanTracksRealStepProgressRatherThanFakeTime() {
        AgentTaskEngine.begin("Spotify play Midnight City", "unit-test-spotify")
        var state = AgentTaskEngine.taskState.value!!
        assertEquals("Open Spotify", state.steps.first { it.stepId == state.currentStepId }.title)
        assertEquals(20, state.progressPercentage)
        assertTrue(AgentTaskEngine.completeStepByTitle("Open Spotify", "foreground verified", verified = true))
        state = AgentTaskEngine.taskState.value!!
        assertEquals("Search for the requested track", state.steps.first { it.stepId == state.currentStepId }.title)
        assertTrue(AgentTaskEngine.completeStepByTitle("Search for the requested track", "unique match found", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Select one unique match", "selected", verified = true))
        assertTrue(AgentTaskEngine.completeStepByTitle("Verify playback", "playing state visible", verified = true))
        AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, "track and playback confirmed")
        assertTrue(AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED))
        assertEquals(100, AgentTaskEngine.taskState.value?.progressPercentage)
    }

    @Test
    fun pauseAndResumeAreReflectedInTheAuthoritativeSnapshot() {
        AgentTaskEngine.begin("Open Spotify", "unit-test-pause")
        assertTrue(AgentTaskEngine.pauseTask())
        assertTrue(AgentTaskEngine.isPaused.value)
        assertEquals(AgentTaskEngine.State.PAUSED.name, AgentTaskEngine.taskState.value?.currentStatus)
        assertTrue(AgentTaskEngine.resumeTask())
        assertFalse(AgentTaskEngine.isPaused.value)
        assertEquals(AgentTaskEngine.State.OBSERVING.name, AgentTaskEngine.taskState.value?.currentStatus)
    }
}
