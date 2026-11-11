package kr.carlog.pleos

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapIntentTest {
    @Test fun unavailableOrRestrictedMapAppDoesNotCrash() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val latitude = SampleEntity("event", 1000, "latitude", 37.5, "deg")
        val longitude = SampleEntity("event", 1000, "longitude", 127.0, "deg")
        val unavailable = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        val restricted = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw SecurityException() }
        }
        assertFalse(openEventMap(unavailable, latitude, longitude))
        assertFalse(openEventMap(restricted, latitude, longitude))
    }

    @Test fun validSyntheticCoordinatesReachASeparateTestApp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val action = "kr.carlog.pleos.TEST_MAP_${UUID.randomUUID().toString().replace('-', '_')}"
        val result = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { result.complete(intent) }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            val latitude = SampleEntity("event", 1000, "latitude", 37.5, "deg")
            val longitude = SampleEntity("event", 1000, "longitude", 127.0, "deg")
            val intent = eventMapIntent(latitude, longitude)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            intent.setComponent(ComponentName(testPackage, TestMapReceiverActivity::class.java.name))
                .putExtra("replyAction", action).putExtra("replyPackage", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val received = withTimeout(15_000) { result.await() }
            assertEquals("geo:37.5,127.0?q=37.5,127.0", received.getStringExtra("uri"))
            val receiverUid = received.getIntExtra("receiverUid", -1)
            assertTrue(receiverUid > 0)
            assertNotEquals(context.applicationInfo.uid, receiverUid)
            try { eventMapIntent(latitude, longitude.copy(timestamp = 1001)); fail("mismatched times") }
            catch (_: IllegalArgumentException) { }
        } finally { context.unregisterReceiver(receiver) }
    }
}
