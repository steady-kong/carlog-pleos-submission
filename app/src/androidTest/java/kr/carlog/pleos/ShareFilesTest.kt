package kr.carlog.pleos

import android.content.Context
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareFilesTest {
    @Test fun explicitCleanupRemovesOnlyGeneratedShareFiles() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = java.io.File(base.cacheDir, "share-cleanup-test-${java.util.UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val context = object : android.content.ContextWrapper(base) { override fun getCacheDir() = root }
        try {
            assertEquals(ShareCleanupResult(0, 0), clearShareFiles(context))
            val dir = java.io.File(root, "carlog-shares").apply { assertTrue(mkdirs()) }
            val event = java.io.File(dir, "carlog-event-123.json").apply { writeText("event") }
            val report = java.io.File(dir, "carlog-report-456.txt").apply { writeText("report") }
            val unrelated = java.io.File(dir, "other.txt").apply { writeText("keep") }
            val folder = java.io.File(dir, "carlog-report-folder.txt").apply { assertTrue(mkdir()) }
            assertEquals(ShareCleanupResult(2, 0), clearShareFiles(context))
            assertFalse(event.exists()); assertFalse(report.exists())
            assertTrue(unrelated.isFile); assertTrue(folder.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun documentPickerReturnsResultProbe() = runBlocking {
        assumeTrue("문서 선택기 결과 진단은 별도 실행", InstrumentationRegistry.getArguments().getString("reportPickerResultProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val action = "kr.carlog.pleos.TEST_PICKER_${java.util.UUID.randomUUID().toString().replace('-', '_')}"
        val received = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.complete(intent) }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            val intent = Intent().setComponent(ComponentName(instrumentation.context.packageName, TestDocumentPickerActivity::class.java.name))
                .putExtra("replyAction", action).putExtra("replyPackage", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val result = withTimeout(15_000) { received.await() }
            assertEquals(android.app.Activity.RESULT_CANCELED, result.getIntExtra("resultCode", Int.MIN_VALUE))
            assertFalse(result.getBooleanExtra("hasUri", true))
        } finally { context.unregisterReceiver(receiver) }
    }

    @Test fun androidDocumentPickerShowsForReport() = runBlocking {
        assumeTrue("문서 선택기 진단은 별도 실행", InstrumentationRegistry.getArguments().getString("reportPickerProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val intent = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
                .createIntent(context, "carlog-report-probe.txt")
            val handler = context.packageManager.resolveActivity(intent, 0)?.activityInfo
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { it.startActivity(intent) }
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var found = false
            var windows = emptyList<String>()
            while (!found && android.os.SystemClock.uptimeMillis() < deadline) {
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                windows = roots.map { "${it.packageName}/${it.className}" }
                found = roots.any { it.packageName?.toString() == handler?.packageName && handler?.packageName != context.packageName }
                if (!found) kotlinx.coroutines.delay(200)
            }
            assertTrue("문서 선택기 창을 찾지 못했습니다. handler=${handler?.packageName}/${handler?.name}, windows=$windows", found)
        } finally {
            scenario?.close()
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test fun androidChooserDeliversReportToSeparateTestApp() = runBlocking {
        assumeTrue("공유 선택기 진단은 별도 실행", InstrumentationRegistry.getArguments().getString("shareChooserProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val expected = "공유 선택기 정비 참고 시험"
        val action = "kr.carlog.pleos.TEST_SHARE_${java.util.UUID.randomUUID().toString().replace('-', '_')}"
        val received = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.complete(intent) }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val payload = createShareIntent(context, expected, false)
                .putExtra("replyAction", action).putExtra("replyPackage", context.packageName)
            val chooser = Intent.createChooser(payload, "시험 공유")
            val handler = context.packageManager.resolveActivity(chooser, 0)?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "확인 불가"
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> activity.startActivity(chooser) }
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var clicked = false
            while (!clicked && android.os.SystemClock.uptimeMillis() < deadline) {
                val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
                val matches = roots.flatMap { it.findAccessibilityNodeInfosByText("Carlog 시험 수신") }
                for (node in matches) {
                    var target: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        clicked = true
                        break
                    }
                }
                if (!clicked) kotlinx.coroutines.delay(200)
            }
            val windows = automation.windows.mapNotNull { it.root?.let { root -> "${root.packageName}/${root.className}" } }
            assertTrue("테스트 수신 앱을 공유 선택기에서 찾지 못했습니다. handler=$handler, windows=$windows", clicked)
            val result = withTimeout(15_000) { received.await() }
            val receiverUid = result.getIntExtra("receiverUid", -1)
            assertTrue(receiverUid > 0)
            assertNotEquals(context.applicationInfo.uid, receiverUid)
            assertNull(result.getStringExtra("error"))
            assertEquals(expected, result.getStringExtra("text"))
        } finally {
            scenario?.close()
            context.unregisterReceiver(receiver)
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test fun separateAppReceivesAndReadsSharedReportAndJson() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        for (json in listOf(false, true)) {
            val expected = if (json) "{\"memo\":\"시연\"}" else "정비 참고 리포트 · 시험 데이터"
            val action = "kr.carlog.pleos.TEST_SHARE_${java.util.UUID.randomUUID().toString().replace('-', '_')}"
            val received = CompletableDeferred<Intent>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { received.complete(intent) }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            try {
                val intent = createShareIntent(context, expected, json)
                    .setComponent(ComponentName(testPackage, TestShareReceiverActivity::class.java.name))
                    .putExtra("replyAction", action)
                    .putExtra("replyPackage", context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val result = withTimeout(15_000) { received.await() }
                val receiverUid = result.getIntExtra("receiverUid", -1)
                assertTrue(receiverUid > 0)
                assertNotEquals(context.applicationInfo.uid, receiverUid)
                assertNull(result.getStringExtra("error"))
                assertEquals(expected, result.getStringExtra("text"))
            } finally { context.unregisterReceiver(receiver) }
        }
    }
    @Test fun uriPermissionGrantsAndRevokesAccessForSeparateUid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = "carlog-share-permission-test"
        val intent = createShareIntent(context, message, false)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        // Packages have different UIDs in each Android user/profile.
        val shellUid = (context.applicationInfo.uid / 100_000) * 100_000 + 2_000
        val mode = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val permission = android.content.pm.PackageManager.PERMISSION_GRANTED
        assertNotEquals(permission, context.checkUriPermission(uri, -1, shellUid, mode))
        context.grantUriPermission("com.android.shell", uri, mode)
        try {
            assertEquals(permission, context.checkUriPermission(uri, -1, shellUid, mode))
        } finally {
            context.revokeUriPermission("com.android.shell", uri, mode)
        }
        assertNotEquals(permission, context.checkUriPermission(uri, -1, shellUid, mode))
    }

    @Test fun largeReportAndJsonAreSharedAsPrivateReadableFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.share", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val data = "차량 이벤트\n".repeat(180_000)
        for (json in listOf(false, true)) {
            val intent = createShareIntent(context, data, json)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(if (json) "application/json" else "text/plain", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.share", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertEquals(data, context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}
