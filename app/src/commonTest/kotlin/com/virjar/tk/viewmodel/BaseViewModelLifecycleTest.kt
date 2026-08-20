package com.virjar.tk.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelLifecycleTest {
    @Test
    fun `destroy cancels action without late error or continuation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = LifecycleTestViewModel(dispatcher)
        val started = CompletableDeferred<Unit>()

        viewModel.startLongAction(started)
        runCurrent()
        started.await()
        viewModel.destroy()
        advanceUntilIdle()

        assertNull(viewModel.error.value)
        assertFalse(viewModel.completedAfterAction)
    }
}

private class LifecycleTestViewModel(dispatcher: CoroutineDispatcher) : BaseViewModel(dispatcher) {
    var completedAfterAction = false
        private set

    fun startLongAction(started: CompletableDeferred<Unit>) {
        scope.launch {
            runViewModelAction("should not surface") {
                started.complete(Unit)
                awaitCancellation()
            }
            completedAfterAction = true
        }
    }
}
