package kr.carlog.pleos

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/** The current Mock app must not silently acquire the experimental SDKs or permissions. */
@RunWith(AndroidJUnit4::class)
class MockBuildBoundaryTest {
    @Test fun mockAppHasNoExperimentalSpeechOrLocationAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
        for (permission in listOf("pleos.car.permission.STT_SERVICE", "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.CAR_DRIVE",
            "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")) {
            assertFalse("Unexpected permission: $permission", permission in requested)
        }
        // Instrumentation can share the test APK classpath with the target process.
        // Inspect only the installed target APK, not classes loaded by the test APK.
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val dex = apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .map { entry -> apk.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) } }.toList()
            assertFalse("No target APK dex files", dex.isEmpty())
            for (name in listOf("Lai/pleos/playground/stt/SpeechToText;", "Lai/pleos/playground/location/fused/FusedLocation;", "Lai/pleos/playground/vehicle/Vehicle;")) {
                assertFalse("Unexpected experimental SDK in Mock app: $name", dex.any { name in it })
            }
        }
    }
}
