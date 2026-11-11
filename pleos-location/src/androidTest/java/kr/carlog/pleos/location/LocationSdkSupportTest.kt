package kr.carlog.pleos.location

import android.content.Context
import android.annotation.SuppressLint
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.FileInputStream

/** Support query only: no location access, permissions, initialization, or listeners. */
@RunWith(AndroidJUnit4::class)
class LocationSdkSupportTest {
    @SuppressLint("MissingPermission")
    @Test fun deniedPermissionFirstResultIsNotLocationOnEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Denied permission probe is opt-in", InstrumentationRegistry.getArguments().getString("locationDeniedProbe") == "true")
        val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() == "1"
        }
        assumeTrue("Only an emulator may run this probe", emulator)
        val context = instrumentation.targetContext
        val permission = "pleos.car.permission.FUSED_LOCATION"
        instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, permission)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        val sdk = createLocationSdkProbe(context)
        val done = CountDownLatch(1)
        val outcome = AtomicReference("timeout")
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                outcome.set(if (location == null) "no location" else "unexpected location")
                done.countDown()
            }
            override fun onError(exception: Exception) { outcome.set("error"); done.countDown() }
        }
        try {
            assertTrue("FusedLocation.isSupported() returned false", supportsLocationSdk(sdk))
            instrumentation.runOnMainSync {
                sdk.initialize()
                try { sdk.getLastKnownLocation(listener) }
                catch (_: SecurityException) { outcome.set("security exception"); done.countDown() }
            }
            assertTrue("No denial, empty result, or error callback in 10 seconds", done.await(10, TimeUnit.SECONDS))
            assertTrue("Permission-denied outcome: ${outcome.get()}", outcome.get() in setOf("no location", "error", "security exception"))
        } finally {
            instrumentation.runOnMainSync { sdk.release() }
        }
    }

    @SuppressLint("MissingPermission")
    @Test fun locationSubscriptionReceivesValidCoordinatesOnEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Location stream probe is opt-in", InstrumentationRegistry.getArguments().getString("locationStreamProbe") == "true")
        val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() == "1"
        }
        assumeTrue("Only an emulator may run this probe", emulator)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "pleos.car.permission.FUSED_LOCATION")
        val sdk = createLocationSdkProbe(context)
        val done = CountDownLatch(1)
        val outcome = AtomicReference("timeout")
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (location == null) return
                outcome.set(if (location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                    location.longitude.isFinite() && location.longitude in -180.0..180.0 && location.timestampMillis > 0) "valid" else "invalid")
                done.countDown()
            }
            override fun onError(exception: Exception) { outcome.set("error: ${exception.javaClass.simpleName}"); done.countDown() }
        }
        var registered = false
        try {
            assertTrue("FusedLocation.isSupported() returned false", supportsLocationSdk(sdk))
            instrumentation.runOnMainSync { sdk.initialize(); sdk.registerFusedLocationCallback(listener); registered = true }
            assertTrue("No location callback in 15 seconds", done.await(15, TimeUnit.SECONDS))
            assertEquals("Location callback result", "valid", outcome.get())
        } finally {
            instrumentation.runOnMainSync {
                try { if (registered) sdk.unregisterFusedLocationCallback(listener) } finally { sdk.release() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Test fun lastKnownLocationRespondsOnIsolatedEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Location probe is opt-in", InstrumentationRegistry.getArguments().getString("locationProbe") == "true")
        val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() == "1"
        }
        assumeTrue("Only an emulator may run this probe", emulator)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "pleos.car.permission.FUSED_LOCATION")
        val sdk = createLocationSdkProbe(context)
        val done = CountDownLatch(1)
        val outcome = AtomicReference("timeout")
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                outcome.set(if (location == null) "empty" else "location")
                done.countDown()
            }
            override fun onError(exception: Exception) { outcome.set("error: ${exception.javaClass.simpleName}"); done.countDown() }
        }
        try {
            assertTrue("FusedLocation.isSupported() returned false", supportsLocationSdk(sdk))
            instrumentation.runOnMainSync { sdk.initialize(); sdk.getLastKnownLocation(listener) }
            assertTrue("No location callback in 15 seconds", done.await(15, TimeUnit.SECONDS))
            assertEquals("Location request result", "location", outcome.get())
        } finally {
            instrumentation.runOnMainSync { sdk.release() }
        }
    }

    @Test fun supportedSdkInitializesAndReleasesWithoutLocationAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sdk = createLocationSdkProbe(context)
        assertTrue("FusedLocation.isSupported() returned false", supportsLocationSdk(sdk))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { sdk.initialize() } finally { sdk.release() }
        }
    }

    @Test fun emulatorReportsLocationSdkSupported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("FusedLocation.isSupported() returned false", supportsLocationSdk(createLocationSdkProbe(context)))
    }
}
