package kr.carlog.pleos

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceCommandScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recognizedCommandRequestsMockEventWithoutRecordingAudio() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val store = ViewModelStore()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = flow {
                emit(Signal(System.currentTimeMillis(), "odometer", 12000.0, "km"))
                awaitCancellation()
            }
        }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, db.dao(), source) as T
        })[CarlogViewModel::class.java]
        val job = vm.viewModelScope.coroutineContext[Job]
        var action: String? = null
        var recognizedSpeech = "이 순간 기록"
        var deferResult = false
        var deferredRequest: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                action = (input as Intent).action
                if (deferResult) { deferredRequest = requestCode; return }
                val result = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(recognizedSpeech))
                dispatchResult(requestCode, Activity.RESULT_OK, result)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val uxState = mutableStateOf(VehicleUxState.Unrestricted)
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MaterialTheme { Screen(vm, uxState = uxState.value) } } }
            compose.waitUntil(10_000) { vm.captureReady.value && vm.latest.value.isNotEmpty() }
            compose.onNodeWithText("음성 기록").performClick()
            compose.waitUntil(10_000) { vm.events.value.isNotEmpty() }
            compose.onNodeWithText("음성 인식 완료 · 이벤트 수집을 요청했습니다.").assertExists()
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, action)
            assertEquals("MOCK", vm.events.value.single().source)
            assertTrue(vm.events.value.single().status in setOf("collecting", "interrupted"))
            assertEquals("", vm.events.value.single().memo)
            compose.runOnIdle { recognizedSpeech = "앞쪽에서 소리가 나요" }
            compose.onNodeWithText("음성 기록").performClick()
            compose.waitUntil(10_000) { vm.events.value.any { it.memo == "앞쪽에서 소리가 나요" } }
            assertEquals("MOCK", vm.events.value.single { it.memo == "앞쪽에서 소리가 나요" }.source)
            deferResult = true
            compose.onNodeWithText("음성 기록").performClick()
            compose.runOnIdle { vm.setMockCollection(false) }
            compose.waitUntil(10_000) { !vm.captureTransitioning.value && !vm.captureReady.value }
            compose.runOnIdle { vm.setMockCollection(true) }
            compose.waitUntil(10_000) { vm.captureReady.value }
            compose.runOnIdle {
                registry.dispatchResult(requireNotNull(deferredRequest), Activity.RESULT_OK,
                    Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("이벤트 기록")))
            }
            compose.onNodeWithText("차량이나 수집 상태가 변경되어 음성 이벤트를 기록하지 않았습니다. 다시 시도해 주세요.").assertExists()
            assertEquals(2, vm.events.value.size)

            compose.onNodeWithText("음성 기록").performClick()
            compose.runOnIdle { uxState.value = VehicleUxState.Unavailable }
            compose.runOnIdle {
                registry.dispatchResult(requireNotNull(deferredRequest), Activity.RESULT_OK,
                    Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("상태 확인 후 저장")))
            }
            compose.onNodeWithText("음성 기록은 차량 사용 상태를 확인한 후 처리합니다.").assertExists()
            assertEquals(2, vm.events.value.size)
            compose.runOnIdle { uxState.value = VehicleUxState.Unrestricted }
            compose.waitUntil(10_000) { vm.events.value.any { it.memo == "상태 확인 후 저장" } }
            assertEquals(1, vm.events.value.count { it.memo == "상태 확인 후 저장" })

            compose.onNodeWithText("음성 기록").performClick()
            compose.runOnIdle { uxState.value = VehicleUxState.Restricted }
            compose.runOnIdle {
                registry.dispatchResult(requireNotNull(deferredRequest), Activity.RESULT_OK,
                    Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("제한 시 취소")))
            }
            compose.waitForIdle()
            compose.runOnIdle { uxState.value = VehicleUxState.Unrestricted }
            compose.onNodeWithText("차량 사용이 제한되어 음성 기록을 취소했습니다.").assertExists()
            assertEquals(3, vm.events.value.size)
            assertTrue(vm.events.value.none { it.memo == "제한 시 취소" })

            compose.onNodeWithText("음성 기록").performClick()
            compose.runOnIdle { uxState.value = VehicleUxState.Unavailable }
            compose.runOnIdle {
                registry.dispatchResult(requireNotNull(deferredRequest), Activity.RESULT_OK,
                    Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("이전 수집 세션")))
                vm.setMockCollection(false)
            }
            compose.waitUntil(10_000) { !vm.captureTransitioning.value && !vm.captureReady.value }
            compose.runOnIdle { vm.setMockCollection(true) }
            compose.waitUntil(10_000) { vm.captureReady.value }
            compose.runOnIdle { uxState.value = VehicleUxState.Unrestricted }
            compose.onNodeWithText("차량이나 수집 상태가 변경되어 음성 이벤트를 기록하지 않았습니다. 다시 시도해 주세요.").assertExists()
            assertEquals(3, vm.events.value.size)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { job?.let { withTimeout(10_000) { it.join() } } }
            db.close()
        }
    }
}
