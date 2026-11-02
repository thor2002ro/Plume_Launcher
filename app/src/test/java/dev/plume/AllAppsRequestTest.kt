package dev.plume

import org.junit.Assert.*
import org.junit.Test

class AllAppsRequestTest {
    @Test fun aQueuedLoadCannotConsumeTheRequestOnAnActivityBeingRecreated() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        assertFalse(request.consume(ready = true, resumed = true, appsLoaded = true, recreating = true))
        assertTrue(request.pending)
        assertTrue(request.consume(ready = true, resumed = true, appsLoaded = true, recreating = false))
        assertFalse(request.consume(true, true, true))
    }

    @Test fun acceptsOnlyTheExplicitAllAppsAction() {
        val request = AllAppsRequest()
        assertFalse(request.accept(null))
        assertFalse(request.accept("android.intent.action.MAIN"))
        assertFalse(request.pending)
        assertTrue(request.accept(AllAppsRequest.ACTION))
        assertTrue(request.pending)
    }

    @Test fun aColdRequestWaitsUntilTheUiAndAppsAreReady() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        assertFalse(request.consume(ready = false, resumed = true, appsLoaded = true))
        assertFalse(request.consume(ready = true, resumed = false, appsLoaded = true))
        assertFalse(request.consume(ready = true, resumed = true, appsLoaded = false))
        assertTrue(request.pending)
        assertTrue(request.consume(ready = true, resumed = true, appsLoaded = true))
        assertFalse(request.pending)
    }

    @Test fun aWarmRequestCanBeConsumedImmediately() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        assertTrue(request.consume(true, true, true))
        assertFalse(request.consume(true, true, true))
    }

    @Test fun anotherRequestWhileWaitingDoesNotQueueTwoOpens() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        request.accept(AllAppsRequest.ACTION)
        assertTrue(request.consume(true, true, true))
        assertFalse(request.consume(true, true, true))
    }

    @Test fun cancellationPreventsALateLoadFromReopeningTheGrid() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        assertFalse(request.consume(true, true, false))
        request.cancel()
        assertFalse(request.consume(true, true, true))
        assertTrue(request.accept(AllAppsRequest.ACTION))
        assertTrue(request.consume(true, true, true))
    }

    @Test fun unrelatedIntentsDoNotDiscardAnUnconsumedRequest() {
        val request = AllAppsRequest()
        request.accept(AllAppsRequest.ACTION)
        assertFalse(request.accept("dev.plume.UPDATE_STATUS"))
        assertTrue(request.consume(true, true, true))
    }

    @Test fun beingReadyWithoutARequestDoesNotOpenTheGrid() {
        assertFalse(AllAppsRequest().consume(true, true, true))
    }
}
