package org.koreader.launcher.device.epd

import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class LarkEPDControllerTest {
    private class CallbackQueue {
        val view: View = mock(View::class.java)
        val callbacks = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        var acceptsCallbacks = true

        init {
            doAnswer { invocation ->
                callbacks.add(invocation.getArgument(0))
                delays.add(invocation.getArgument(1))
                acceptsCallbacks
            }.`when`(view).postDelayed(any(Runnable::class.java), anyLong())
        }
    }

    private fun refresh(controller: LarkEPDController, view: View, mode: Int, epdMode: String? = null) {
        controller.setEpdMode(view, mode, 0, 0, 0, 600, 800, epdMode)
    }

    @Test
    fun waveformMappingsUseOnlyP60qCandidates() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()

        assertEquals("sunxi", controller.getPlatform())
        assertEquals("all", controller.getMode())
        val modes = listOf(controller.getWaveformFull(), controller.getWaveformFullUi(),
            controller.getWaveformPartial(), controller.getWaveformPartialUi(), controller.getWaveformFast())
        assertEquals(listOf(4, 4, 132, 132, 16), modes)
        modes.forEach { refresh(controller, queue.view, it) }
        assertEquals(modes.map { 1 to it }, writes)
        assertEquals(listOf(150L, 150L, 150L), queue.delays)
    }

    @Test
    fun stringFullRefreshUsesGc16EvenWhenNumericModeIsZero() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 0, "EPD_FULL")
        assertEquals(listOf(1 to 4), writes)
        queue.callbacks.single().run()
        assertEquals(listOf(1 to 4, 1 to 132), writes)
    }

    @Test
    fun staleResetCannotOverwriteNewTemporaryModeOnAnotherView() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val oldQueue = CallbackQueue()
        val newQueue = CallbackQueue()
        refresh(controller, oldQueue.view, 4)
        val oldReset = oldQueue.callbacks.single()
        refresh(controller, newQueue.view, 16)
        verify(oldQueue.view).removeCallbacks(oldReset)

        // Execute even after cancellation, as if already dequeued by the UI thread.
        oldReset.run()
        assertEquals(listOf(1 to 4, 1 to 16), writes)
        newQueue.callbacks.single().run()
        newQueue.callbacks.single().run()
        assertEquals(listOf(1 to 4, 1 to 16, 1 to 132), writes)
    }

    @Test
    fun partialRefreshCancelsTemporaryResetWithoutSchedulingAnother() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 4)
        val reset = queue.callbacks.single()
        refresh(controller, queue.view, 132)
        verify(queue.view).removeCallbacks(reset)
        reset.run()
        assertEquals(1, queue.callbacks.size)
        assertEquals(listOf(1 to 4, 1 to 132), writes)
    }

    @Test
    fun pauseInvalidatesResetAndRejectsBackgroundRefreshesUntilResume() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 4)
        val reset = queue.callbacks.single()
        controller.pause()
        verify(queue.view).removeCallbacks(reset)
        reset.run()
        refresh(controller, queue.view, 16)
        refresh(controller, queue.view, 132)
        assertEquals(listOf(1 to 4), writes)
        assertEquals(1, queue.callbacks.size)

        controller.resume()
        refresh(controller, queue.view, 16)
        reset.run()
        assertEquals(listOf(1 to 4, 1 to 132, 1 to 16), writes)
        queue.callbacks.last().run()
        assertEquals(listOf(1 to 4, 1 to 132, 1 to 16, 1 to 132), writes)
    }

    @Test
    fun resumeDiscardsResetEvenWithoutPrecedingPause() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 16)
        val reset = queue.callbacks.single()
        controller.resume()
        verify(queue.view).removeCallbacks(reset)
        reset.run()
        assertEquals(listOf(1 to 16, 1 to 132), writes)
    }

    @Test
    fun failedStrategyWriteDoesNotScheduleReset() {
        val controller = LarkEPDController { _, _ -> false }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 4)
        verify(queue.view, never()).postDelayed(any(Runnable::class.java), anyLong())
    }

    @Test
    fun rejectedCallbackRestoresCleanStateImmediately() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            true
        }
        val queue = CallbackQueue()
        queue.acceptsCallbacks = false
        refresh(controller, queue.view, 4)
        assertEquals(listOf(1 to 4, 1 to 132), writes)
        queue.callbacks.single().run()
        assertEquals(2, writes.size)
    }

    @Test
    fun dequeuedResetCannotRunDuringNewStrategyWrite() {
        val writes = mutableListOf<Pair<Int, Int>>()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val controller = LarkEPDController { strategy, mode ->
            writes.add(strategy to mode)
            if (mode == 16) {
                writeEntered.countDown()
                assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
            }
            true
        }
        val queue = CallbackQueue()
        refresh(controller, queue.view, 4)
        val oldReset = queue.callbacks.single()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val refreshTask = executor.submit { refresh(controller, queue.view, 16) }
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            val resetTask = executor.submit {
                resetAttempted.countDown()
                oldReset.run()
            }
            assertTrue(resetAttempted.await(5, TimeUnit.SECONDS))
            releaseWrite.countDown()
            refreshTask.get(5, TimeUnit.SECONDS)
            resetTask.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1 to 4, 1 to 16), writes)
            queue.callbacks.last().run()
            assertEquals(listOf(1 to 4, 1 to 16, 1 to 132), writes)
        } finally {
            releaseWrite.countDown()
            executor.shutdownNow()
        }
    }
}
