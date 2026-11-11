package kr.carlog.pleos

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionLeaseTest {
    @Test fun ownershipRemainsUntilCancelledChildCleanupFinishes() = runBlocking {
        val first = Any()
        val second = Any()
        val job = Job()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        CaptureSessionLease.register(first)
        CaptureSessionLease.register(second)
        try {
            assertTrue(CaptureSessionLease.acquire(first))
            CoroutineScope(coroutineContext + job).launch(start = CoroutineStart.UNDISPATCHED) {
                try { CompletableDeferred<Unit>().await() }
                finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); allowCleanup.await() } }
            }
            CaptureSessionLease.unregisterWhenComplete(first, job)
            job.cancel()
            cleanupStarted.await()
            assertFalse(CaptureSessionLease.acquire(second))
            allowCleanup.complete(Unit)
            job.join()
            assertTrue(CaptureSessionLease.acquire(second))
        } finally {
            allowCleanup.complete(Unit)
            job.cancel()
            CaptureSessionLease.release(first)
            CaptureSessionLease.unregister(first)
            CaptureSessionLease.release(second)
            CaptureSessionLease.unregister(second)
        }
    }
}
