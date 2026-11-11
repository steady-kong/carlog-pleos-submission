package kr.carlog.pleos.speech

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No recognition request, microphone access, audio, or credentials. */
@RunWith(AndroidJUnit4::class)
class SpeechInitializationTest {
    @Test fun onDeviceSilenceReturnsTerminalCallback() {
        assumeTrue("Silent PCM probe is opt-in", InstrumentationRegistry.getArguments().getString("speechPcmProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sdk = createOnDeviceSpeechProbe(context)
        val initialized = CountDownLatch(1)
        val initResult = AtomicReference("no callback")
        val finished = CountDownLatch(1)
        val outcome = AtomicReference("no callback")
        val callbacks = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listener = object : ResultListener {
            override fun onReady() { callbacks.add("ready") }
            override fun onStartedRecognition() { callbacks.add("started") }
            override fun onUpdated(stt: String, completed: Boolean) { callbacks.add(if (completed) "final" else "partial") }
            override fun onUpdatedEpdData(on: Long, off: Long) { callbacks.add("epd") }
            override fun onUpdatedRms(rms: Double) { callbacks.add("rms") }
            override fun onEndedRecognition() { callbacks.add("ended"); outcome.set("ended"); finished.countDown() }
            override fun onError(errorType: ErrorType) { callbacks.add("error"); outcome.set(errorType.toString()); finished.countDown() }
        }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                sdk.initialize(object : OnInitializeListener {
                    override fun onSuccess() { initResult.set("success"); initialized.countDown() }
                    override fun onFailure(errorType: ErrorType) { initResult.set(errorType.toString()); initialized.countDown() }
                })
            }
            assertTrue("initialization timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals("initialization result", "success", initResult.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                sdk.addListener(listener)
                sdk.sendAudio(ByteArray(32_000)) // One second of silent 16 kHz 16-bit mono PCM.
                sdk.completeAudioSend()
            }
            val ended = finished.await(15, TimeUnit.SECONDS)
            val observed = synchronized(callbacks) { callbacks.distinct().joinToString() }
            assertTrue("silent PCM processing timed out; callbacks: $observed", ended)
            assertEquals("silent PCM terminal result", "ended", outcome.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                sdk.removeListener(listener)
                sdk.release()
            }
        }
    }

    @Test fun onDeviceInitializationAndRelease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sdk = createOnDeviceSpeechProbe(context)
        val done = CountDownLatch(1)
        val result = AtomicReference("no callback")
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                sdk.initialize(object : OnInitializeListener {
                    override fun onSuccess() { result.set("success"); done.countDown() }
                    override fun onFailure(errorType: ErrorType) { result.set(errorType.toString()); done.countDown() }
                })
            }
            assertTrue("Speech SDK initialization callback timed out", done.await(15, TimeUnit.SECONDS))
            assertEquals("Speech SDK initialization result", "success", result.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.release() }
        }
    }
}
