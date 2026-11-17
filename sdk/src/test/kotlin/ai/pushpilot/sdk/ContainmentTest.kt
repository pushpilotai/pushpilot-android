package ai.pushpilot.sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContainmentTest {
    /** An Error from a task (like the 2.1.0 regex crash) is logged; the SDK thread keeps running later tasks. */
    @Test
    fun anErrorInATaskDoesNotStopTheSdkThread() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val scheduler = ExecutorScheduler(executor)
        val ran = CountDownLatch(1)
        scheduler.schedule(0) { throw ExceptionInInitializerError("boom") }
        scheduler.schedule(0) { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
    }
}
