package suwayomi.tachidesk.server.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ShutdownManagerTest {
    private val executionOrder = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        executionOrder.clear()
        ShutdownManager.resetForTesting()
    }

    @Test
    fun `should execute actions in LIFO order`() {
        // Register actions in order: A, B, C
        // Should execute in reverse: C, B, A
        ShutdownManager.registerShutdownAction("A", 1.seconds) {
            executionOrder.add("A")
        }
        ShutdownManager.registerShutdownAction("B", 1.seconds) {
            executionOrder.add("B")
        }
        ShutdownManager.registerShutdownAction("C", 1.seconds) {
            executionOrder.add("C")
        }

        runBlocking {
            ShutdownManager.gracefulShutdown(10.seconds)
        }

        assertEquals(listOf("C", "B", "A"), executionOrder)
    }

    @Test
    fun `should not allow duplicate shutdown`() {
        var executionCount = 0
        ShutdownManager.registerShutdownAction("test", 1.seconds) {
            executionCount++
        }

        runBlocking {
            ShutdownManager.gracefulShutdown(2.seconds)
            ShutdownManager.gracefulShutdown(2.seconds) // Should not re-execute
        }

        assertEquals(1, executionCount)
    }

    @Test
    fun `should respect per-action timeout`() {
        var slowActionCompleted = false
        var fastActionCompleted = false

        ShutdownManager.registerShutdownAction("slow", 100.milliseconds) {
            delay(500.milliseconds)
            slowActionCompleted = true
        }
        ShutdownManager.registerShutdownAction("fast", 1.seconds) {
            fastActionCompleted = true
        }

        val start = System.currentTimeMillis()
        runBlocking {
            ShutdownManager.gracefulShutdown(2.seconds)
        }
        val elapsed = System.currentTimeMillis() - start

        // Slow action should timeout
        assertFalse(slowActionCompleted, "Slow action should not complete due to timeout")
        // Fast action should complete
        assertTrue(fastActionCompleted, "Fast action should complete")
        // Should not take full 2 seconds, should respect timeouts
        assertTrue(elapsed < 1500, "Shutdown should complete within reasonable time")
    }

    @Test
    fun `should respect total shutdown timeout`() {
        ShutdownManager.registerShutdownAction("action", 10.seconds) {
            delay(20.seconds)
        }

        val start = System.currentTimeMillis()
        runBlocking {
            ShutdownManager.gracefulShutdown(500.milliseconds)
        }
        val elapsed = System.currentTimeMillis() - start

        // Should not wait full 20 seconds, should bail out at total timeout
        assertTrue(elapsed < 2000, "Shutdown should respect total timeout (${elapsed}ms)")
    }

    @Test
    fun `should continue executing remaining actions if one fails`() {
        val executed = mutableListOf<String>()

        ShutdownManager.registerShutdownAction("A", 1.seconds) {
            executed.add("A")
        }
        ShutdownManager.registerShutdownAction("B", 1.seconds) {
            throw RuntimeException("Simulated error in B")
        }
        ShutdownManager.registerShutdownAction("C", 1.seconds) {
            executed.add("C")
        }

        // Should not throw even though B fails
        runBlocking {
            ShutdownManager.gracefulShutdown(5.seconds)
        }

        // C and A should still execute (LIFO: C, B, A)
        assertTrue(executed.contains("C"), "C should execute")
        assertTrue(executed.contains("A"), "A should execute after B fails")
    }

    @Test
    fun `should report shutdown status`() {
        assertFalse(ShutdownManager.isShuttingDown(), "Should not be shutting down initially")

        ShutdownManager.registerShutdownAction("test", 1.seconds) {
            // isShuttingDown should be true during shutdown
            assertTrue(ShutdownManager.isShuttingDown(), "Should be shutting down during execution")
        }

        runBlocking {
            ShutdownManager.gracefulShutdown(2.seconds)
        }

        assertTrue(ShutdownManager.isShuttingDown(), "Should still report shutting down after completion")
    }

    @Test
    fun `should prevent registration after shutdown starts`() {
        var lateActionExecuted = false

        ShutdownManager.registerShutdownAction("early", 1.seconds) {
            // Try to register during shutdown
            ShutdownManager.registerShutdownAction("late", 1.seconds) {
                lateActionExecuted = true
            }
        }

        runBlocking {
            ShutdownManager.gracefulShutdown(2.seconds)
        }

        assertFalse(lateActionExecuted, "Late-registered action should not execute")
    }

    @Test
    fun `should handle multiple actions with mixed success and timeout`() {
        val results = mutableListOf<String>()

        ShutdownManager.registerShutdownAction("fast1", 1.seconds) {
            results.add("fast1-done")
        }
        ShutdownManager.registerShutdownAction("timeout", 100.milliseconds) {
            delay(5.seconds)
            results.add("timeout-done") // Should not reach here
        }
        ShutdownManager.registerShutdownAction("fast2", 1.seconds) {
            results.add("fast2-done")
        }

        runBlocking {
            ShutdownManager.gracefulShutdown(5.seconds)
        }

        // fast2 and fast1 should complete (LIFO: fast2, timeout, fast1)
        assertTrue(results.contains("fast2-done"), "fast2 should complete")
        assertTrue(results.contains("fast1-done"), "fast1 should complete")
        assertFalse(results.contains("timeout-done"), "timeout action should not complete")
    }
}
