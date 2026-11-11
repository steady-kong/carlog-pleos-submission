package kr.carlog.pleos

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test for the project-owned Pleos emulator and its isolated probe app. */
@RunWith(AndroidJUnit4::class)
class DrivingRestrictionProbeTest {
    @Test fun restrictionObscuresNonDistractionOptimizedActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("drivingRestrictionProbe") == "true")
        assumeTrue(instrumentation.targetContext.packageName == "kr.carlog.pleos.uxprobe")
        fun shell(command: String): String {
            val pfd = instrumentation.uiAutomation.executeShellCommand(command)
            return pfd.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        }
        assumeTrue("Only an emulator may run this probe", shell("getprop ro.kernel.qemu").trim() == "1")
        fun command(state: String): String = shell("cmd hmg_car_service --service drivingstate driving_regulation $state")
        val services = shell("service list")
        val hasService = services.lineSequence().any { it.contains("hmg_car_service:") }
        Log.i("CarlogUxProbe", "hmg_car_service available=$hasService")
        assumeTrue("Emulator does not expose hmg_car_service", hasService)
        val initial = shell("dumpsys car_service").lineSequence().firstOrNull { it.contains("Display id: 0 UXR:") }
        assumeTrue("Probe requires an initially unrestricted display: $initial", initial?.contains("DO: false") == true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var restrictionEnabled = false
        try {
            scenario.onActivity { }
            assertTrue("Probe activity was not resumed before restriction", scenario.state == Lifecycle.State.RESUMED)
            Log.i("CarlogUxProbe", "restriction on: ${command("on")}")
            restrictionEnabled = true
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var current = initial
            while (current?.contains("DO: true") != true && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
                current = shell("dumpsys car_service").lineSequence().firstOrNull { it.contains("Display id: 0 UXR:") }
            }
            Log.i("CarlogUxProbe", "restriction state: $current")
            assertTrue("Command did not activate display restrictions", current?.contains("DO: true") == true)
            while (scenario.state == Lifecycle.State.RESUMED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            Log.i("CarlogUxProbe", "activity state under restriction=${scenario.state}")
            assertTrue("Activity remained resumed after restriction command", scenario.state != Lifecycle.State.RESUMED)
        } finally {
            try {
                if (restrictionEnabled) {
                    Log.i("CarlogUxProbe", "restriction off: ${command("off")}")
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var current: String?
                    do {
                        current = shell("dumpsys car_service").lineSequence().firstOrNull { it.contains("Display id: 0 UXR:") }
                        if (current?.contains("DO: false") != true) SystemClock.sleep(200)
                    } while (current?.contains("DO: false") != true && SystemClock.elapsedRealtime() < deadline)
                    assertTrue("Restriction did not return to the initial state", current?.contains("DO: false") == true)
                }
            } finally {
                scenario.close()
            }
        }
    }

    /** Runs only when explicitly requested on the newly created Google AAOS test AVD. */
    @Test fun standardAaosDrivingRestrictionProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("aaosUxProbe") == "true")
        assumeTrue(instrumentation.targetContext.packageName == "kr.carlog.pleos.uxprobe")
        fun shell(command: String): String {
            val pfd = instrumentation.uiAutomation.executeShellCommand(command)
            return pfd.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        }
        assumeTrue("Only an emulator may run this probe", shell("getprop ro.kernel.qemu").trim() == "1")
        fun uxState(): String? = shell("dumpsys car_service").lineSequence().firstOrNull { it.contains(" UXR: DO:") }
        val initial = uxState()
        assumeTrue("Probe requires an initially unrestricted display: $initial", initial?.contains("DO: false") == true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var attempted = false
        try {
            lateinit var vm: CarlogViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[CarlogViewModel::class.java] }
            assertTrue("Probe activity was not resumed", scenario.state == Lifecycle.State.RESUMED)
            val readyDeadline = SystemClock.elapsedRealtime() + 10_000
            while ((!vm.captureReady.value || vm.latest.value.isEmpty()) && SystemClock.elapsedRealtime() < readyDeadline) SystemClock.sleep(100)
            assertTrue("Mock source did not become ready", vm.captureReady.value && vm.latest.value.isNotEmpty())
            val marker = "ux-probe-${UUID.randomUUID()}"
            instrumentation.runOnMainSync { vm.mark("일반 이벤트", marker) {} }
            val eventDeadline = SystemClock.elapsedRealtime() + 10_000
            while (vm.events.value.none { it.memo == marker } && SystemClock.elapsedRealtime() < eventDeadline) SystemClock.sleep(100)
            val event = vm.events.value.single { it.memo == marker }
            fun sampleCount() = runBlocking { vm.samples(event.id).first().size }
            val before = sampleCount()
            attempted = true
            Log.i("CarlogUxProbe", "AAOS gear drive: ${shell("cmd car_service inject-vhal-event 0x11400400 8")}")
            Log.i("CarlogUxProbe", "AAOS speed: ${shell("cmd car_service inject-vhal-event 0x11600207 10")}")
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var state = uxState()
            while (state?.contains("DO: true") != true && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                state = uxState()
            }
            Log.i("CarlogUxProbe", "AAOS restriction state: $state")
            assertTrue("AAOS did not enter restricted state", state?.contains("DO: true") == true)
            val activityDeadline = SystemClock.elapsedRealtime() + 10_000
            while (scenario.state == Lifecycle.State.RESUMED && SystemClock.elapsedRealtime() < activityDeadline) SystemClock.sleep(200)
            Log.i("CarlogUxProbe", "AAOS activity state: ${scenario.state}")
            assertTrue("Activity remained resumed under restrictions", scenario.state != Lifecycle.State.RESUMED)
            SystemClock.sleep(4_000)
            val after = sampleCount()
            Log.i("CarlogUxProbe", "Mock event samples before=$before after=$after status=${vm.events.value.single { it.id == event.id }.status}")
            assertTrue("Sample count unexpectedly decreased", after >= before)
        } finally {
            try {
                if (attempted) {
                    shell("cmd car_service inject-vhal-event 0x11600207 0")
                    shell("cmd car_service inject-vhal-event 0x11400400 4")
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var state = uxState()
                    while (state?.contains("DO: false") != true && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(250)
                        state = uxState()
                    }
                    Log.i("CarlogUxProbe", "AAOS restored state: $state")
                    assertTrue("AAOS did not return to initial unrestricted state", state?.contains("DO: false") == true)
                }
            } finally { scenario.close() }
        }
    }
}
