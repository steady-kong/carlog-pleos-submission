package kr.carlog.pleos

import android.app.Application
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarlogViewModel internal constructor(app: Application, private val dao: CarlogDao, private val source: VehicleDataSource,
    private val openInputStream: (Uri) -> java.io.InputStream? = { app.contentResolver.openInputStream(it) },
    private val openExportStream: (Uri) -> java.io.OutputStream? = { app.contentResolver.openOutputStream(it) }) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, Room.databaseBuilder(app, CarlogDatabase::class.java, "carlog.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().dao(), MockVehicleDataSource())
    private val _captureError = MutableStateFlow<String?>(null)
    val captureError = _captureError.asStateFlow()
    private val _captureReady = MutableStateFlow(false)
    val captureReady = _captureReady.asStateFlow()
    private val _captureTransitioning = MutableStateFlow(true)
    val captureTransitioning = _captureTransitioning.asStateFlow()
    private var sourceJob: Job? = null
    private val captureOwner = Any()
    private val captureScopeJob = requireNotNull(viewModelScope.coroutineContext[Job])
    private val _vehicleSwitching = MutableStateFlow(false)
    val vehicleSwitching = _vehicleSwitching.asStateFlow()
    val vehicles = dao.vehicles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _recordError = MutableStateFlow<String?>(null)
    val recordError = _recordError.asStateFlow()
    private val _recordSaving = MutableStateFlow(false)
    val recordSaving = _recordSaving.asStateFlow()
    private val _exchangeMessage = MutableStateFlow<String?>(null)
    val exchangeMessage = _exchangeMessage.asStateFlow()
    private val _exportPreparing = MutableStateFlow(false)
    val exportPreparing = _exportPreparing.asStateFlow()
    private val _vehicleExportWriting = MutableStateFlow(false)
    private val _eventExportWriting = MutableStateFlow(false)
    private val _sharePreparing = MutableStateFlow(false)
    private val _reportExportBusy = MutableStateFlow(false)
    val reportExportBusy = _reportExportBusy.asStateFlow()
    private val _reportExportMessage = MutableStateFlow<String?>(null)
    val reportExportMessage = _reportExportMessage.asStateFlow()
    private var pendingReportExport: String? = null
    private val _vehicleExportPreparing = MutableStateFlow(false)
    val vehicleExportPreparing = _vehicleExportPreparing.asStateFlow()
    private val _vehicleExportMessage = MutableStateFlow<String?>(null)
    val vehicleExportMessage = _vehicleExportMessage.asStateFlow()
    private var pendingVehicleExport: String? = null
    private val _vehicleImportPreview = MutableStateFlow<VehicleManagementExportData?>(null)
    val vehicleImportPreview = _vehicleImportPreview.asStateFlow()
    private val _vehicleImportBusy = MutableStateFlow(false)
    val vehicleImportBusy = _vehicleImportBusy.asStateFlow()
    private val _eventEditSaving = MutableStateFlow(false)
    val eventEditSaving = _eventEditSaving.asStateFlow()
    private val _eventEditError = MutableStateFlow<String?>(null)
    val eventEditError = _eventEditError.asStateFlow()
    private val _pendingImport = MutableStateFlow<ImportedEvent?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _backupPreview = MutableStateFlow<PwaBackupPreview?>(null)
    val backupPreview = _backupPreview.asStateFlow()
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage = _backupMessage.asStateFlow()
    private val _backupLoading = MutableStateFlow(false)
    val backupLoading = _backupLoading.asStateFlow()
    private val _shareCleanupMessage = MutableStateFlow<String?>(null)
    val shareCleanupMessage = _shareCleanupMessage.asStateFlow()
    private var backupPreviewJob: Job? = null
    private var backupPreviewGeneration = 0L
    private var pendingExport: String? = null
    private data class PendingRecord(val key: RecordEntity, val dateExplicit: Boolean, val value: RecordEntity)
    private var pendingNewRecord: PendingRecord? = null
    fun clearPendingNewRecord() { if (!_recordSaving.value) pendingNewRecord = null }
    private val _latest = MutableStateFlow<Map<String, Signal>>(emptyMap())
    val latest = _latest.asStateFlow()
    private val _oldestPreEventSampleAge = MutableStateFlow<Long?>(null)
    val oldestPreEventSampleAge = _oldestPreEventSampleAge.asStateFlow()
    private var capture = newCapture()
    private data class PendingVoice(val vehicleId: String, val session: EventCapture, val category: String, val memo: String)
    private var pendingVoice: PendingVoice? = null
    fun beginVoiceCommand(category: String, memo: String): Boolean {
        val car = vehicle.value ?: return false
        if (!_captureReady.value || _vehicleSwitching.value || _markRequesting.value || pendingVoice != null) return false
        pendingVoice = PendingVoice(car.id, capture, category, memo)
        return true
    }
    fun cancelVoiceCommand() { pendingVoice = null }
    fun completeVoiceCommand(recognizedMemo: String, onSaved: () -> Unit): Boolean {
        val pending = pendingVoice ?: return false
        pendingVoice = null
        if (!_captureReady.value || _vehicleSwitching.value || _markRequesting.value || vehicle.value?.id != pending.vehicleId || capture !== pending.session) return false
        mark(pending.category, recognizedMemo, onSaved)
        return true
    }
    private fun newCapture(): EventCapture {
        lateinit var session: EventCapture
        session = EventCapture(viewModelScope, RoomEventCaptureStore(dao), object : CaptureClock {
            override fun wallTimeMillis() = System.currentTimeMillis()
            override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
        }, { if (capture === session) _captureError.value = it },
            onClockReset = { if (capture === session) { _latest.value = emptyMap(); _oldestPreEventSampleAge.value = null } },
            onTerminalFailure = { if (capture === session) { _captureReady.value = false; _captureError.value = it } })
        return session
    }
    private val vehicle = MutableStateFlow<VehicleEntity?>(null)
    val vehicleDetails = vehicle.asStateFlow()
    private val _vehicleError = MutableStateFlow<String?>(null)
    val vehicleError = _vehicleError.asStateFlow()
    fun clearVehicleError() { _vehicleError.value = null }
    private val _vehicleSaving = MutableStateFlow(false)
    val vehicleSaving = _vehicleSaving.asStateFlow()
    val records = vehicle.filterNotNull().flatMapLatest { dao.records(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val schedules = vehicle.filterNotNull().flatMapLatest { dao.schedules(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val events = vehicle.filterNotNull().flatMapLatest { dao.events(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    init {
        CaptureSessionLease.register(captureOwner)
        viewModelScope.launch {
            vehicle.filterNotNull().map { it.id }.distinctUntilChanged().flatMapLatest { dao.observeVehicle(it) }
                .collect { fresh -> if (fresh != null && vehicle.value?.id == fresh.id) vehicle.value = fresh }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val session = capture
                if (_captureReady.value) {
                    val age = session.oldestPreEventSampleAgeMillis()
                    if (capture === session && _captureReady.value) _oldestPreEventSampleAge.value = age
                }
            }
        }
        viewModelScope.launch {
            try {
                // A screen created after a switch starts must not load the old selection.
                CaptureSessionLease.awaitVehicleSwitch()
                if (!CaptureSessionLease.acquire(captureOwner)) {
                    // Show the existing vehicle and its records without recovering or starting capture.
                    vehicle.value = dao.vehicleForStartup()
                    _captureError.value = "다른 화면이 차량 신호 수집을 관리 중입니다. 해당 화면을 닫은 뒤 다시 시도해 주세요."
                    return@launch
                }
                dao.interruptPending()
                vehicle.value = dao.vehicleForStartup() ?: VehicleEntity(name = "내 차량").also { dao.insertVehicle(it) }
                startSource(capture)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                CaptureSessionLease.release(captureOwner)
                _captureError.value = "차량 정보 또는 이전 이벤트 상태를 불러오지 못했습니다."
            }
            finally { _captureTransitioning.value = false }
        }
    }
    private fun startSource(session: EventCapture) {
        _captureReady.value = true
        sourceJob = viewModelScope.launch {
            try {
                source.signals().collect { signal ->
                    session.accept(signal)
                    _latest.value = updateLatestSignals(_latest.value, signal)
                    _oldestPreEventSampleAge.value = session.oldestPreEventSampleAgeMillis()
                }
                session.interruptAll()
                _captureError.value = "차량 신호 수집이 중단됐습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try { session.interruptAll() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Retry recovery before starting another session. */ }
                _captureError.value = when (e) {
                    is CaptureStoppedException -> _captureError.value ?: "차량 신호 수집이 중단됐습니다."
                    is FutureSignalTimestampException -> "차량 신호 시간이 기기 시간보다 앞서 수집을 중단했습니다."
                    is InvalidSignalException -> "유효하지 않은 차량 신호가 수신되어 수집을 중단했습니다."
                    is ConflictingSignalException -> "같은 시각의 차량 신호가 충돌하여 수집을 중단했습니다."
                    is SignalBufferCapacityException -> "차량 신호 버퍼 한도에 도달해 수집을 중단했습니다."
                    else -> "차량 신호 수집 또는 저장에 실패했습니다."
                }
            } finally { if (capture === session) _captureReady.value = false }
        }
    }
    fun setMockCollection(enabled: Boolean) {
        if (_vehicleSwitching.value) return
        if (_captureTransitioning.value || enabled == _captureReady.value) return
        if (enabled && !CaptureSessionLease.acquire(captureOwner)) {
            _captureError.value = "다른 화면이 차량 신호 수집을 관리 중입니다. 해당 화면을 닫은 뒤 다시 시도해 주세요."
            return
        }
        _captureTransitioning.value = true
        _captureReady.value = false
        val oldSession = capture
        viewModelScope.launch {
            try {
                sourceJob?.cancelAndJoin()
                oldSession.interruptAll()
                _latest.value = emptyMap()
                _oldestPreEventSampleAge.value = null
                if (enabled) {
                    dao.interruptPending()
                    if (vehicle.value == null) {
                        vehicle.value = dao.vehicleForStartup() ?: VehicleEntity(name = "내 차량").also { dao.insertVehicle(it) }
                    }
                    capture = newCapture()
                    _captureError.value = null
                    startSource(capture)
                } else _captureError.value = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _captureError.value = "차량 정보 또는 이벤트 상태를 복구하지 못했습니다. 다시 시도해 주세요." }
            finally { _captureTransitioning.value = false }
        }
    }
    override fun onCleared() {
        CaptureSessionLease.unregisterWhenComplete(captureOwner, captureScopeJob)
        super.onCleared()
    }
    fun samples(id: String) = dao.samples(id)
    fun clearEventEditError() { _eventEditError.value = null }
    fun updateEventDetails(baseline: EventEntity, category: String, memo: String, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_eventEditSaving.value) return
        _eventEditSaving.value = true
        viewModelScope.launch {
            try {
                eventCategoryCode(category)
                val updated = baseline.vehicleId == car.id && dao.updateEventDetailsIfUnchanged(
                    baseline.id, car.id, baseline.category, baseline.memo, category, memo) == 1
                if (!updated) {
                    _eventEditError.value = "다른 화면에서 이벤트가 변경됐습니다. 입력을 보존했으니 취소한 뒤 최신 내용을 확인해 주세요."
                    return@launch
                }
                _eventEditError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _eventEditError.value = "이벤트 분류와 메모를 저장하지 못했습니다." }
            finally { _eventEditSaving.value = false }
        }
    }
    fun updateVehicle(baseline: VehicleEntity, name: String, initialKm: Double, maker: String, model: String, year: Int?, fuelType: String?, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val current = vehicle.value ?: return
        if (_vehicleSaving.value) return
        _vehicleSaving.value = true
        viewModelScope.launch {
            try {
                require(validVehicleInfo(name, initialKm, year, fuelType))
                val savedMaker = maker.trim().ifEmpty { null }; val savedModel = model.trim().ifEmpty { null }
                val updated = baseline.copy(name = name.trim(), initialKm = initialKm, maker = savedMaker, model = savedModel, year = year, fuelType = fuelType)
                if (current.id != baseline.id || dao.updateVehicleInfoIfUnchanged(baseline, updated) != 1) {
                    _vehicleError.value = "다른 화면에서 차량 정보가 변경됐습니다. 입력을 보존했으니 취소한 뒤 최신 내용을 확인해 주세요."
                    return@launch
                }
                vehicle.value = updated
                _vehicleError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _vehicleError.value = "차량 정보를 저장하지 못했습니다." }
            finally { _vehicleSaving.value = false }
        }
    }
    private val _markRequesting = MutableStateFlow(false)
    val markRequesting = _markRequesting.asStateFlow()
    fun mark(category: String, memo: String, onSaved: () -> Unit) {
        if (_vehicleSwitching.value || _markRequesting.value) return
        if (!_captureReady.value) return
        val car = vehicle.value ?: return
        val session = capture
        val moment = session.markMoment()
        _markRequesting.value = true
        viewModelScope.launch {
            try {
                if (!_captureReady.value || capture !== session) return@launch
                _captureError.value = null
                session.start(car.id, category, memo, source.source, moment)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is CaptureStoppedException || _captureError.value == null) {
                    _captureError.value = "이벤트를 저장하지 못했습니다."
                }
            } finally { _markRequesting.value = false }
        }
    }
    fun saveRecord(type: String, label: String, km: Double?, amount: Double, cost: Long, full: Boolean, category: String?, engineHours: Double?, engineHoursMode: String?, relatedEventId: String?, repairOutcome: String?, recordDate: Long?, editingId: String?, editingBaseline: RecordEntity?, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                require(validRecordInput(type, label, km, amount, cost, engineHours, engineHoursMode, relatedEventId, repairOutcome))
                val now = System.currentTimeMillis()
                if (relatedEventId != null) {
                    check(type == "정비" && dao.eventById(relatedEventId)?.vehicleId == car.id)
                }
                if (editingId == null) {
                    val candidate = RecordEntity(vehicleId = car.id, type = type, timestamp = recordDate ?: now, odometer = km ?: 0.0, odometerKnown = km != null, amount = amount, cost = cost, label = label, full = full, category = category, engineHours = engineHours, engineHoursMode = engineHoursMode, updatedAt = now, relatedEventId = relatedEventId, repairOutcome = repairOutcome)
                    val key = candidate.copy(id = "", timestamp = recordDate ?: 0, updatedAt = null)
                    val pending = pendingNewRecord?.takeIf { it.key == key && it.dateExplicit == (recordDate != null) }
                        ?: PendingRecord(key, recordDate != null, candidate).also { pendingNewRecord = it }
                    check(dao.insertRecordOnce(pending.value)) { "Record ID conflicts with existing data" }
                    pendingNewRecord = null
                } else {
                    val current = editingBaseline?.takeIf { it.id == editingId && it.vehicleId == car.id }
                        ?: throw IllegalStateException("Missing record baseline")
                    check(current.type == type) { "Record type cannot be changed while editing" }
                    val updated = current.copy(type = type, timestamp = recordDate ?: current.timestamp, odometer = km ?: 0.0, odometerKnown = km != null, amount = amount, cost = cost, label = label, full = full,
                        category = category, engineHours = engineHours, engineHoursMode = engineHoursMode, updatedAt = now, relatedEventId = relatedEventId, repairOutcome = repairOutcome)
                    if (!dao.replaceRecordIfUnchanged(current, updated)) throw RecordEditConflictException()
                }
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RecordEditConflictException) {
                _recordError.value = "다른 화면에서 기록이 변경되었거나 삭제됐습니다. 입력은 유지됩니다. 수정을 취소하고 최신 기록을 확인해 주세요."
            } catch (e: Exception) {
                _recordError.value = "기록의 저장 결과를 확인하지 못했습니다. 기록 목록을 확인한 뒤 다시 시도해 주세요."
            } finally {
                _recordSaving.value = false
            }
        }
    }
    fun deleteRecord(record: RecordEntity, onDeleted: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                check(dao.softDeleteRecordIfUnchanged(record, car.id, System.currentTimeMillis()) == 1)
                _recordError.value = null
                onDeleted()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "기록을 삭제하지 못했습니다. 다른 화면에서 변경되었거나 삭제됐는지 확인해 주세요." }
            finally { _recordSaving.value = false }
        }
    }
    fun eventJsonForExport(eventId: String, onReady: (String) -> Unit) {
        if (_vehicleSwitching.value || _exportPreparing.value || _eventExportWriting.value || _vehicleExportPreparing.value || _vehicleExportWriting.value ||
            _sharePreparing.value || _reportExportBusy.value || pendingExport != null || pendingVehicleExport != null) {
            _exchangeMessage.value = "다른 저장이나 공유 작업이 끝난 뒤 다시 시도해 주세요."
            return
        }
        _exportPreparing.value = true
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val snapshot = dao.eventExportData(eventId) ?: error("Event not ready")
                    exportEventJson(snapshot.event, snapshot.samples)
                }
                _exchangeMessage.value = null
                onReady(json)
            } catch (e: CancellationException) { throw e }
            catch (e: EventExportLimitException) { _exchangeMessage.value = eventExportLimitMessage(e.limit) }
            catch (_: Exception) {
                _exchangeMessage.value = "이 이벤트를 JSON으로 내보낼 수 없습니다. 수집 상태와 신호 개수, 파일 크기 등 교환 제한을 확인해 주세요."
            } finally { _exportPreparing.value = false }
        }
    }
    fun previewVehicleImport(uri: Uri?) {
        if (_vehicleSwitching.value) return
        if (_vehicleImportBusy.value || _vehicleImportPreview.value != null) return
        if (uri == null) { _vehicleExportMessage.value = "차량 관리 JSON 선택이 취소되었거나 문서 선택기를 사용할 수 없습니다."; return }
        _vehicleImportBusy.value = true
        _vehicleExportMessage.value = null
        viewModelScope.launch {
            try {
                _vehicleImportPreview.value = withContext(Dispatchers.IO) {
                    parseVehicleManagementJson(readUtf8File(uri, VEHICLE_MANAGEMENT_MAX_BYTES))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ExchangeTimestampException) { _vehicleExportMessage.value = "차량 관리 JSON을 읽을 수 없습니다. ${e.location ?: "시각"}의 UTC 형식과 범위, 밀리초 정밀도를 확인해 주세요. 원본 파일은 유지됩니다." }
            catch (_: Exception) { _vehicleExportMessage.value = "차량 관리 JSON을 읽을 수 없습니다. 형식과 크기를 확인해 주세요." }
            finally { _vehicleImportBusy.value = false }
        }
    }
    fun cancelVehicleImport() { if (!_vehicleImportBusy.value) _vehicleImportPreview.value = null }
    /** Held only while preparing and launching a share; the receiving app owns later access. */
    fun beginShare(): Boolean {
        if (_vehicleSwitching.value || _sharePreparing.value || _reportExportBusy.value || _vehicleExportPreparing.value ||
            _vehicleExportWriting.value || _eventExportWriting.value || pendingExport != null || pendingVehicleExport != null) return false
        _sharePreparing.value = true
        return true
    }
    fun endShare() { _sharePreparing.value = false }
    fun clearSharedFiles() {
        if (!beginShare()) { _shareCleanupMessage.value = "다른 공유나 저장 작업이 끝난 뒤 다시 시도해 주세요."; return }
        _shareCleanupMessage.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { clearShareFiles(getApplication()) }
                _shareCleanupMessage.value = "임시 공유 파일 ${result.removed}개 삭제${if (result.failed > 0) " · ${result.failed}개 삭제 실패" else ""}"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _shareCleanupMessage.value = "임시 공유 파일을 정리하지 못했습니다." }
            finally { endShare() }
        }
    }

    suspend fun reportFromStorage(eventId: String): String {
        val vehicleId = vehicle.value?.id ?: error("No active vehicle")
        return withContext(Dispatchers.IO) {
            val data = dao.maintenanceReportData(eventId, vehicleId) ?: error("Event unavailable")
            maintenanceReport(data.event, data.samples, data.repairs, data.vehicle)
        }
    }

    fun selectVehicle(target: VehicleEntity) {
        if (_vehicleSwitching.value || _vehicleImportBusy.value || _captureReady.value || _captureTransitioning.value ||
            _recordSaving.value || _vehicleSaving.value || _eventEditSaving.value || _importing.value || _pendingImport.value != null ||
            _vehicleExportPreparing.value || _exportPreparing.value || _vehicleExportWriting.value || _eventExportWriting.value || _sharePreparing.value || _reportExportBusy.value ||
            pendingVehicleExport != null || pendingExport != null) {
            _vehicleExportMessage.value = "수집과 진행 중인 저장·공유 작업을 마친 뒤 차량을 전환해 주세요."
            return
        }
        if (!CaptureSessionLease.beginVehicleSwitch(captureOwner)) {
            _vehicleExportMessage.value = "다른 차량 화면을 닫은 뒤 전환해 주세요."
            return
        }
        _vehicleSwitching.value = true
        _vehicleExportMessage.value = null
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) { dao.createOrSelectVehicle(target) }
                when (result) {
                    VehicleSelectionResult.CREATED, VehicleSelectionResult.SELECTED -> {
                        _latest.value = emptyMap(); _oldestPreEventSampleAge.value = null
                        vehicle.value = target
                        _vehicleExportMessage.value = if (_vehicleImportPreview.value?.vehicle?.id == target.id)
                            "차량을 선택했습니다. 파일 기록은 별도로 가져오기 적용을 눌러 주세요."
                        else "차량을 선택했습니다. 필요하면 Mock 수집을 다시 시작해 주세요."
                    }
                    VehicleSelectionResult.CONFLICT -> _vehicleExportMessage.value = "같은 UUID에 다른 차량 정보가 있어 전환하지 않았습니다."
                    VehicleSelectionResult.ACTIVE_CAPTURE -> _vehicleExportMessage.value = "완료되지 않은 이벤트가 있어 전환하지 않았습니다."
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _vehicleExportMessage.value = "차량을 선택하지 못했습니다." }
            finally {
                _vehicleSwitching.value = false
                CaptureSessionLease.endVehicleSwitch(captureOwner)
            }
        }
    }

    fun applyVehicleImport() {
        if (_vehicleSwitching.value) return
        val data = _vehicleImportPreview.value ?: return
        val carId = vehicle.value?.id ?: return
        if (_vehicleImportBusy.value) return
        _vehicleImportBusy.value = true
        _vehicleExportMessage.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { dao.importVehicleManagement(data, carId) }
                _vehicleExportMessage.value = when (result.status) {
                    VehicleManagementImportStatus.APPLIED -> "가져오기 완료: 추가 ${result.added}건, 동일 기록 ${result.duplicates}건."
                    VehicleManagementImportStatus.VEHICLE_MISMATCH -> "차량 UUID가 달라 가져오지 않았습니다."
                    VehicleManagementImportStatus.VEHICLE_CONFLICT -> "차량 정보가 달라 가져오지 않았습니다."
                    VehicleManagementImportStatus.RECORD_CONFLICT -> "기존 또는 삭제된 기록과 충돌하여 가져오지 않았습니다."
                    VehicleManagementImportStatus.MISSING_EVENT -> "연결된 이벤트 ${result.missingEventIds.size}건이 현재 차량에 없습니다. 이벤트 JSON을 먼저 가져와 주세요. ID: ${result.missingEventIds.take(3).joinToString()}${if (result.missingEventIds.size > 3) " 외 ${result.missingEventIds.size - 3}건" else ""}"
                }
                if (result.status == VehicleManagementImportStatus.APPLIED) _vehicleImportPreview.value = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _vehicleExportMessage.value = "차량 관리 기록 가져오기 결과를 확인하지 못했습니다. 기록을 확인한 뒤 같은 파일로 다시 시도해 주세요." }
            finally { _vehicleImportBusy.value = false }
        }
    }
    fun prepareVehicleExport(onReady: () -> Unit) {
        if (_vehicleSwitching.value) return
        val carId = vehicle.value?.id ?: return
        if (_vehicleExportPreparing.value || _vehicleExportWriting.value || _exportPreparing.value || _eventExportWriting.value ||
            _sharePreparing.value || _reportExportBusy.value || pendingVehicleExport != null || pendingExport != null) {
            _vehicleExportMessage.value = "다른 저장이나 공유 작업이 끝난 뒤 다시 시도해 주세요."
            return
        }
        _vehicleExportPreparing.value = true
        _vehicleExportMessage.value = null
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val snapshot = dao.vehicleManagementExport(carId) ?: error("Vehicle unavailable")
                    exportVehicleManagementJson(snapshot, System.currentTimeMillis())
                }
                pendingVehicleExport = json
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pendingVehicleExport = null; _vehicleExportMessage.value = "차량 관리 기록을 내보낼 수 없습니다. 기록 값과 파일 크기를 확인해 주세요." }
            finally { _vehicleExportPreparing.value = false }
        }
    }
    fun finishVehicleExport(uri: Uri?) {
        val data = pendingVehicleExport
        pendingVehicleExport = null
        if (uri == null) {
            if (data != null) _vehicleExportMessage.value = "차량 관리 JSON 저장이 취소되었거나 문서 선택기를 사용할 수 없습니다."
            return
        }
        if (data == null) { _vehicleExportMessage.value = "저장할 기록이 사라졌습니다. 다시 선택해 주세요."; return }
        _vehicleExportWriting.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = openExportStream(uri) ?: error("Cannot open output")
                    stream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                }
                _vehicleExportMessage.value = "차량 관리 기록 JSON을 저장했습니다."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _vehicleExportMessage.value = "차량 관리 기록 파일을 저장하지 못했습니다. 선택한 파일에 일부 내용이 남았을 수 있습니다." }
            finally { _vehicleExportWriting.value = false }
        }
    }
    fun prepareEventExport(eventId: String, onReady: () -> Unit) {
        eventJsonForExport(eventId) { json ->
            pendingExport = json
            try { onReady() }
            catch (e: CancellationException) { pendingExport = null; throw e }
            catch (_: Exception) { pendingExport = null; _exchangeMessage.value = "이벤트 파일 선택기를 열지 못했습니다." }
        }
    }
    fun finishEventExport(uri: Uri?) {
        val data = pendingExport
        pendingExport = null
        if (uri == null) {
            if (data != null) _exchangeMessage.value = "이벤트 JSON 저장이 취소되었거나 문서 선택기를 사용할 수 없습니다."
            return
        }
        if (data == null) { _exchangeMessage.value = "저장할 이벤트가 사라졌습니다. 다시 선택해 주세요."; return }
        _eventExportWriting.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = openExportStream(uri) ?: error("Cannot open output")
                    stream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                }
                _exchangeMessage.value = "이벤트 JSON 파일을 저장했습니다."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _exchangeMessage.value = "이벤트 파일을 저장하지 못했습니다. 선택한 파일에 일부 내용이 남았을 수 있습니다." }
            finally { _eventExportWriting.value = false }
        }
    }
    /** Keep the exact preview until the file picker completes or is cancelled. */
    fun prepareReportExport(report: String, onReady: () -> Unit) {
        if (_vehicleSwitching.value || _sharePreparing.value || _reportExportBusy.value || _eventExportWriting.value ||
            _vehicleExportWriting.value || _exportPreparing.value || _vehicleExportPreparing.value || pendingExport != null || pendingVehicleExport != null) {
            _reportExportMessage.value = "다른 저장이나 공유 작업이 끝난 뒤 다시 시도해 주세요."
            return
        }
        pendingReportExport = report
        _reportExportBusy.value = true
        _reportExportMessage.value = null
        try { onReady() }
        catch (_: Exception) {
            pendingReportExport = null
            _reportExportBusy.value = false
            _reportExportMessage.value = "리포트 파일 선택기를 열지 못했습니다."
        }
    }
    fun finishReportExport(uri: Uri?) {
        val report = pendingReportExport
        pendingReportExport = null
        if (report == null) {
            if (uri != null) _reportExportMessage.value = "저장할 리포트가 사라졌습니다. 다시 준비해 주세요."
            return
        }
        if (uri == null) {
            _reportExportBusy.value = false
            _reportExportMessage.value = "파일 저장이 취소되었거나 문서 선택기를 사용할 수 없습니다. 공유 기능도 확인해 보세요."
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = openExportStream(uri) ?: error("Cannot open output")
                    stream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                }
                _reportExportMessage.value = "정비 참고 리포트를 저장했습니다."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _reportExportMessage.value = "리포트를 저장하지 못했습니다. 선택한 파일에 일부 내용이 남았을 수 있습니다." }
            finally { _reportExportBusy.value = false }
        }
    }
    fun importEventFile(uri: Uri?) {
        if (_vehicleSwitching.value) return
        if (_importing.value || _pendingImport.value != null) return
        if (uri == null) { _exchangeMessage.value = "이벤트 JSON 선택이 취소되었거나 문서 선택기를 사용할 수 없습니다."; return }
        _importing.value = true
        _exchangeMessage.value = null
        viewModelScope.launch {
            var saving = false
            try {
                val (value, result, destination) = withContext(Dispatchers.IO) {
                    val text = readUtf8File(uri, EVENT_EXCHANGE_MAX_BYTES)
                    val imported = parseEventJson(text)
                    val destination = dao.vehicleById(imported.event.vehicleId)
                    saving = true
                    val result = dao.importEvent(imported)
                    Triple(imported, result, destination.takeIf { result == EventImportResult.ADDED || result == EventImportResult.DUPLICATE })
                }
                if (result == EventImportResult.VEHICLE_NOT_FOUND) _pendingImport.value = value
                else {
                    val base = importMessage(result)
                    _exchangeMessage.value = if (destination != null && destination.id != vehicle.value?.id)
                        "$base 저장 차량: ${destination.name} (${destination.id}). 해당 차량을 선택하면 볼 수 있습니다."
                    else base
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ExchangeTimestampException) { _exchangeMessage.value = "파일을 가져오지 못했습니다. ${e.location ?: "시각"}의 UTC 형식과 범위, 밀리초 정밀도를 확인해 주세요. 원본 파일은 유지됩니다." }
            catch (_: Exception) { _exchangeMessage.value = if (saving) "이벤트 가져오기 결과를 확인하지 못했습니다. 기록을 확인한 뒤 같은 파일로 다시 시도해 주세요."
                else "파일을 가져오지 못했습니다. 완료된 v1 또는 v2 JSON과 차량 ID를 확인해 주세요." }
            finally { _importing.value = false }
        }
    }
    fun cancelImportMapping() { if (!_importing.value) { _pendingImport.value = null; _exchangeMessage.value = null } }
    fun importToCurrentVehicle() {
        if (_vehicleSwitching.value) return
        val value = _pendingImport.value ?: return
        val carId = vehicle.value?.id ?: return
        if (_importing.value) return
        _importing.value = true
        _exchangeMessage.value = null
        viewModelScope.launch {
            try {
                val result = dao.importEvent(value, carId)
                if (result != EventImportResult.VEHICLE_NOT_FOUND) _pendingImport.value = null
                _exchangeMessage.value = importMessage(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _exchangeMessage.value = "차량 연결 후 저장 결과를 확인하지 못했습니다. 기록을 확인한 뒤 다시 시도해 주세요." }
            finally { _importing.value = false }
        }
    }
    private fun importMessage(result: EventImportResult): String = when (result) {
        EventImportResult.ADDED -> "이벤트를 가져왔습니다."
        EventImportResult.DUPLICATE -> "이미 같은 이벤트가 있습니다."
        EventImportResult.CONFLICT -> "같은 ID의 다른 이벤트가 있어 가져오지 않았습니다."
        EventImportResult.VEHICLE_NOT_FOUND -> "선택한 차량을 찾을 수 없습니다."
    }
    private suspend fun readUtf8File(uri: Uri, limit: Int): String {
        val context = currentCoroutineContext()
        context.ensureActive()
        val stream = openInputStream(uri) ?: error("Cannot open input")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                context.ensureActive()
                val read = input.read(buffer)
                context.ensureActive()
                if (read < 0) break
                require(read > 0) { "Input stream did not make progress" }
                require(output.size() + read <= limit) { "File too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        context.ensureActive()
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun previewPwaFile(uri: Uri?) {
        if (uri == null) { if (!_backupLoading.value) _backupMessage.value = "웹 백업 선택이 취소되었거나 문서 선택기를 사용할 수 없습니다."; return }
        backupPreviewJob?.cancel()
        val generation = ++backupPreviewGeneration
        _backupPreview.value = null
        _backupMessage.value = null
        _backupLoading.value = true
        backupPreviewJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { previewPwaBackup(readUtf8File(uri, 25 * 1024 * 1024)) }
                if (generation == backupPreviewGeneration) _backupPreview.value = result
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == backupPreviewGeneration) _backupMessage.value = "백업을 열지 못했습니다. 25 MiB 이하의 Carlog v1 백업을 선택해 주세요." }
            finally { if (generation == backupPreviewGeneration) _backupLoading.value = false }
        }
    }
    fun eventPickerFailed() { _exchangeMessage.value = "이벤트 파일 선택기를 열지 못했습니다." }
    fun vehiclePickerFailed() { _vehicleExportMessage.value = "차량 관리 파일 선택기를 열지 못했습니다." }
    fun backupPickerFailed() { _backupMessage.value = "웹 백업 파일 선택기를 열지 못했습니다." }
    fun closeBackupPreview() {
        backupPreviewGeneration++
        backupPreviewJob?.cancel()
        _backupLoading.value = false
        _backupPreview.value = null
        _backupMessage.value = null
    }
    fun clearRecordError() { _recordError.value = null }
    fun markScheduleDone(schedule: ScheduleEntity, km: Double, doneAt: Long, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                check(km.isFinite() && km >= 0 && doneAt <= System.currentTimeMillis() &&
                    (schedule.intervalKm <= 0 || (km + schedule.intervalKm).isFinite()))
                check(dao.markScheduleDoneIfUnchanged(schedule, car.id, km, doneAt, System.currentTimeMillis()) == 1)
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "교체 기준을 갱신하지 못했습니다. 다른 화면의 변경 또는 이전 교체보다 빠른 날짜나 작은 주행거리인지 확인해 주세요." }
            finally { _recordSaving.value = false }
        }
    }
    fun updateSchedule(schedule: ScheduleEntity, label: String, km: Double, months: Long?, hours: Double?, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                check(dao.updateScheduleIfUnchanged(schedule, car.id, label.trim(), km, months, hours, System.currentTimeMillis()) == 1)
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "교체 주기를 수정하지 못했습니다. 다른 화면에서 변경되었거나 삭제됐는지 확인해 주세요." }
            finally { _recordSaving.value = false }
        }
    }
    fun fillMissingScheduleDate(schedule: ScheduleEntity, doneAt: Long, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                check(doneAt <= System.currentTimeMillis())
                check(dao.fillMissingScheduleDateIfUnchanged(schedule, car.id, doneAt, System.currentTimeMillis()) == 1)
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "교체일을 저장하지 못했습니다. 다른 화면에서 변경되었거나 삭제됐는지 확인해 주세요." }
            finally { _recordSaving.value = false }
        }
    }
    fun deleteSchedule(schedule: ScheduleEntity, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                check(dao.softDeleteScheduleIfUnchanged(schedule, car.id, System.currentTimeMillis()) == 1)
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "교체 주기를 삭제하지 못했습니다. 다른 화면에서 변경되었거나 삭제됐는지 확인해 주세요." }
            finally { _recordSaving.value = false }
        }
    }
    fun addSchedule(label: String, km: Double, interval: Double, months: Long?, lastDoneAt: Long?, hours: Double?, isOil: Boolean, onSaved: () -> Unit) {
        if (_vehicleSwitching.value) return
        val car = vehicle.value ?: return
        if (_recordSaving.value) return
        _recordSaving.value = true
        viewModelScope.launch {
            try {
                dao.insertSchedule(ScheduleEntity(vehicleId = car.id, label = label, lastKm = km, intervalKm = interval, intervalMonths = months, lastDoneAt = lastDoneAt, intervalEngineHours = hours, category = if (isOil) "engine_oil" else null, updatedAt = System.currentTimeMillis()))
                _recordError.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _recordError.value = "교체 주기를 저장하지 못했습니다." }
            finally { _recordSaving.value = false }
        }
    }
}
private fun date(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
private fun eventStatusLabel(status: String) = when (status) {
    "collecting" -> "수집 중"
    "complete" -> "수집 종료"
    "interrupted" -> "수집 중단"
    else -> status
}
/** Decorative category pictograms; adjacent text supplies the accessible name. */
@Composable private fun EventCategoryIcon(category: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val line = w * 0.075f
        when (category) {
            "차량 이상" -> {
                val triangle = Path().apply {
                    moveTo(w * .5f, h * .1f); lineTo(w * .93f, h * .87f)
                    lineTo(w * .07f, h * .87f); close()
                }
                drawPath(triangle, color, style = Stroke(line))
                drawLine(color, Offset(w * .5f, h * .35f), Offset(w * .5f, h * .59f), line)
                drawCircle(color, w * .04f, Offset(w * .5f, h * .73f))
            }
            "도로 상태" -> {
                drawLine(color, Offset(w * .3f, h * .1f), Offset(w * .1f, h * .9f), line)
                drawLine(color, Offset(w * .7f, h * .1f), Offset(w * .9f, h * .9f), line)
                drawLine(color, Offset(w * .5f, h * .14f), Offset(w * .5f, h * .36f), line)
                drawLine(color, Offset(w * .5f, h * .55f), Offset(w * .5f, h * .84f), line)
            }
            "특이 주행 상황" -> {
                drawCircle(color, w * .39f, style = Stroke(line))
                drawCircle(color, w * .09f)
                drawLine(color, Offset(w * .17f, h * .44f), Offset(w * .41f, h * .5f), line)
                drawLine(color, Offset(w * .59f, h * .5f), Offset(w * .83f, h * .44f), line)
                drawLine(color, Offset(w * .5f, h * .59f), Offset(w * .5f, h * .88f), line)
            }
            "위치 메모" -> {
                val pin = Path().apply {
                    moveTo(w * .5f, h * .9f)
                    cubicTo(w * .38f, h * .72f, w * .18f, h * .5f, w * .18f, h * .36f)
                    cubicTo(w * .18f, h * -.04f, w * .82f, h * -.04f, w * .82f, h * .36f)
                    cubicTo(w * .82f, h * .5f, w * .62f, h * .72f, w * .5f, h * .9f)
                    close()
                }
                drawPath(pin, color, style = Stroke(line))
                drawCircle(color, w * .09f, Offset(w * .5f, h * .34f))
            }
            else -> {
                drawCircle(color, w * .39f, style = Stroke(line))
                drawCircle(color, w * .06f)
            }
        }
    }
}
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var uxState by remember { mutableStateOf(VehicleUxState.Unavailable) }
    LaunchedEffect(owner, context) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { vehicleUxRestrictions(context).collect { uxState = it } }
            finally { uxState = VehicleUxState.Unavailable }
        }
    }
    CarlogTheme { Screen(uxState = uxState) }
} } }
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun Screen(vm: CarlogViewModel = viewModel(), uxState: VehicleUxState = VehicleUxState.Unavailable) {
    // Keep the result launcher registered even while the external recognizer pauses
    // this activity and the restricted placeholder replaces the normal UI.
    val voiceLifecycleOwner = LocalLifecycleOwner.current
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var voiceTone by remember { mutableStateOf(HmiTone.Neutral) }
    var voiceResult by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var voiceResumed by remember(voiceLifecycleOwner) {
        mutableStateOf(voiceLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(voiceLifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            voiceResumed = voiceLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        voiceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { voiceLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val voiceCommand = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val candidates = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        voiceResult = result.resultCode to eventVoiceMemo(candidates)
        voiceTone = HmiTone.Progress
        voiceMessage = "음성 인식이 끝났습니다. 차량 사용 상태를 확인하고 있습니다."
    }
    LaunchedEffect(uxState, voiceResumed, voiceResult) {
        val result = voiceResult ?: return@LaunchedEffect
        if (uxState == VehicleUxState.Restricted) {
            voiceResult = null
            vm.cancelVoiceCommand()
            voiceTone = HmiTone.Error
            voiceMessage = "차량 사용이 제한되어 음성 기록을 취소했습니다."
        } else if (uxState == VehicleUxState.Unrestricted && voiceResumed &&
            voiceLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            voiceResult = null
            val recognizedMemo = result.second
            voiceTone = HmiTone.Error
            voiceMessage = when {
                result.first != Activity.RESULT_OK -> { vm.cancelVoiceCommand(); "음성 인식이 취소되었거나 완료되지 않았습니다." }
                recognizedMemo == null -> { vm.cancelVoiceCommand(); "음성 내용을 인식하지 못했습니다. 다시 시도해 주세요." }
                !vm.completeVoiceCommand(recognizedMemo) { } -> "차량이나 수집 상태가 변경되어 음성 이벤트를 기록하지 않았습니다. 다시 시도해 주세요."
                else -> { voiceTone = HmiTone.Success; "음성 인식 완료 · 이벤트 수집을 요청했습니다." }
            }
        }
    }
    if (uxState != VehicleUxState.Unrestricted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Carlog", style = MaterialTheme.typography.headlineSmall)
            Text(if (uxState == VehicleUxState.Restricted) "차량의 주행 제한이 활성화되어 사용할 수 없습니다." else "차량의 사용 제한 상태를 확인할 수 없어 사용할 수 없습니다.", style = MaterialTheme.typography.titleMedium)
            if (voiceResult != null) Text("음성 기록은 차량 사용 상태를 확인한 후 처리합니다.")
        }
        return
    }
    val focusManager = LocalFocusManager.current
    val navigationScope = rememberCoroutineScope()
    val eventScroll = rememberScrollState()
    val managementScroll = rememberScrollState()
    var managementSelected by rememberSaveable { mutableStateOf(false) }
    var recordsTarget by remember { mutableIntStateOf(0) }
    val storedRecords by vm.records.collectAsState(); val storedSchedules by vm.schedules.collectAsState(); val storedEvents by vm.events.collectAsState(); val latest by vm.latest.collectAsState()
    val currentVehicle by vm.vehicleDetails.collectAsState()
    val records = storedRecords.filter { it.vehicleId == currentVehicle?.id }
    val schedules = storedSchedules.filter { it.vehicleId == currentVehicle?.id }
    val events = storedEvents.filter { it.vehicleId == currentVehicle?.id }
    val vehicles by vm.vehicles.collectAsState(); val vehicleSwitching by vm.vehicleSwitching.collectAsState()
    val vehicleError by vm.vehicleError.collectAsState(); val vehicleSaving by vm.vehicleSaving.collectAsState()
    val markRequesting by vm.markRequesting.collectAsState()
    val captureError by vm.captureError.collectAsState(); val captureReady by vm.captureReady.collectAsState(); val captureTransitioning by vm.captureTransitioning.collectAsState(); val recordError by vm.recordError.collectAsState(); val recordSaving by vm.recordSaving.collectAsState()
    val oldestPreEventSampleAge by vm.oldestPreEventSampleAge.collectAsState()
    val exchangeMessage by vm.exchangeMessage.collectAsState()
    val pendingImport by vm.pendingImport.collectAsState(); val importing by vm.importing.collectAsState()
    val backupPreview by vm.backupPreview.collectAsState(); val backupMessage by vm.backupMessage.collectAsState(); val backupLoading by vm.backupLoading.collectAsState()
    val shareCleanupMessage by vm.shareCleanupMessage.collectAsState()
    var confirmShareCleanup by remember { mutableStateOf(false) }
    var eventToolsExpanded by rememberSaveable { mutableStateOf(false) }
    var managementToolsExpanded by rememberSaveable { mutableStateOf(false) }
    val vehicleExportPreparing by vm.vehicleExportPreparing.collectAsState(); val vehicleExportMessage by vm.vehicleExportMessage.collectAsState()
    val vehicleImportPreview by vm.vehicleImportPreview.collectAsState(); val vehicleImportBusy by vm.vehicleImportBusy.collectAsState()
    val vehicleImportFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.previewVehicleImport(it) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEventFile(it) }
    val reportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.finishReportExport(it) }
    val previewFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.previewPwaFile(it) }
    val vehicleExportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.finishVehicleExport(it) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var spendingPeriod by rememberSaveable { mutableStateOf(SpendingPeriod.ALL) }
    var managementDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var recordPreview by remember { mutableStateOf<RecordEntity?>(null) }
    var managementCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var recordEntryExpanded by rememberSaveable { mutableStateOf(false) }
    var visibleRecordCount by rememberSaveable { mutableIntStateOf(20) }
    var visibleTimelineCount by rememberSaveable { mutableIntStateOf(20) }
    var visibleLinkEventCount by rememberSaveable { mutableIntStateOf(10) }
    var useMockOdometer by rememberSaveable { mutableStateOf(false) }
    var useEstimatedOdometer by rememberSaveable { mutableStateOf(false) }
    var type by remember { mutableStateOf("주유") }; var label by remember { mutableStateOf("") }; var km by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var full by remember { mutableStateOf(true) }; var expenseCategory by remember { mutableStateOf("기타") }
    var engineHours by remember { mutableStateOf("") }; var incrementalHours by remember { mutableStateOf(false) }
    var intervalMonths by remember { mutableStateOf("") }; var lastDoneDate by remember { mutableStateOf("") }; var intervalHours by remember { mutableStateOf("") }; var isOil by remember { mutableStateOf(false) }
    var recordDate by remember { mutableStateOf("") }
    var entryError by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RecordEntity?>(null) }
    var pendingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var replacementKm by remember { mutableStateOf("") }; var replacementDate by remember { mutableStateOf("") }
    var replacementError by remember { mutableStateOf<String?>(null) }
    var editingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var missingDateSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var missingScheduleDate by remember { mutableStateOf("") }
    var missingScheduleDateError by remember { mutableStateOf<String?>(null) }
    var deletingSchedule by remember { mutableStateOf<ScheduleEntity?>(null) }
    var scheduleLabel by remember { mutableStateOf("") }; var scheduleKm by remember { mutableStateOf("") }
    var scheduleMonths by remember { mutableStateOf("") }; var scheduleHours by remember { mutableStateOf("") }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var linkedEventId by remember { mutableStateOf<String?>(null) }; var repairOutcome by remember { mutableStateOf("") }
    var editingBaseline by remember { mutableStateOf<RecordEntity?>(null) }
    var vehicleSwitchTarget by remember { mutableStateOf<VehicleEntity?>(null) }
    var editVehicle by remember { mutableStateOf(false) }
    var vehicleEditBaseline by remember { mutableStateOf<VehicleEntity?>(null) }
    var vehicleName by remember { mutableStateOf("") }; var vehicleInitialKm by remember { mutableStateOf("") }
    var vehicleMaker by remember { mutableStateOf("") }; var vehicleModel by remember { mutableStateOf("") }
    var vehicleYear by remember { mutableStateOf("") }; var vehicleFuelType by remember { mutableStateOf<String?>(null) }
    var vehicleInputError by remember { mutableStateOf<String?>(null) }
    fun clearEntry() {
        vm.clearPendingNewRecord()
        label = ""; km = ""; amount = ""; cost = ""; engineHours = ""; intervalMonths = ""; lastDoneDate = ""; intervalHours = ""; recordDate = ""
        full = true; expenseCategory = "기타"; incrementalHours = false; isOil = false
        entryError = null; editingId = null; editingBaseline = null; linkedEventId = null; repairOutcome = ""
    }
    fun openRecordEntry() {
        recordEntryExpanded = true
        focusManager.clearFocus()
        navigationScope.launch { managementScroll.animateScrollTo(recordsTarget) }
    }
    fun entrySnapshot(): List<Any?> = listOf(type, label, km, amount, cost, full, expenseCategory, engineHours,
        incrementalHours, intervalMonths, lastDoneDate, intervalHours, isOil, recordDate, editingId, editingBaseline, linkedEventId, repairOutcome)
    LaunchedEffect(currentVehicle?.id) {
        clearEntry(); selectedId = null; pendingDelete = null; pendingSchedule = null; useMockOdometer = false; useEstimatedOdometer = false
        editingSchedule = null; missingDateSchedule = null; deletingSchedule = null; editVehicle = false
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner, events.any { it.status == "collecting" }) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dashboardClock(intervalMillis = if (events.any { it.status == "collecting" }) 1_000 else 60_000).collect { clockTick = it }
        }
    }
    val now = remember(oldestPreEventSampleAge, latest, captureReady, clockTick) { System.currentTimeMillis() }
    val mockOdometer = latest["odometer"]
    val mockOdometerValid = mockOdometer != null && mockOdometer.unit == "km" && mockOdometer.value.isFinite() && mockOdometer.value >= 0
    val projectedKm = projectedOdometer(records, now)
    val currentKm = maintenanceOdometer(currentVehicle?.initialKm ?: 0.0, records, mockOdometer, useMockOdometer, projectedKm, useEstimatedOdometer)
    // An initial 0 is also the database default, so it alone cannot establish a reading.
    val hasMaintenanceOdometer = records.any { it.deletedAt == null && it.odometerKnown && it.odometer.isFinite() && it.odometer >= 0 } ||
        (currentVehicle?.initialKm?.let { it.isFinite() && it > 0 } == true) ||
        (useMockOdometer && mockOdometerValid) ||
        (useEstimatedOdometer && projectedKm?.let { it.isFinite() && it >= 0 } == true)
    val stats = fuelStats(records.filter { it.type == "주유" }.map { Fuel(it.timestamp, it.odometer, it.amount, it.full) })
    val wallet = spendingStats(records, startMillis = spendingPeriodStart(spendingPeriod, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())))
    val oilRecords = records.filter { it.type == "정비" && it.category == "engine_oil" }.map { OilChange(it.timestamp, it.odometer) }
    val hoursRecords = records.filter { it.type == "주유" && it.engineHours != null }.map { EngineHoursEntry(it.timestamp, it.engineHours, it.engineHoursMode == "incremental") }
    val scheduledDue = prioritizeMaintenance(schedules.map { schedule ->
        scheduledMaintenanceDue(schedule, if (hasMaintenanceOdometer) currentKm else Double.NaN, now, hoursRecords, oilRecords)
    })
    if (confirmShareCleanup) AlertDialog(onDismissRequest = { confirmShareCleanup = false },
        title = { Text("임시 공유 파일 삭제") },
        text = { Text("이전에 공유를 위해 만든 임시 파일을 삭제합니다. 다른 앱에서 아직 읽는 중인 공유가 실패할 수 있습니다. 앱의 이벤트와 저장한 파일은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { confirmShareCleanup = false; vm.clearSharedFiles() }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmShareCleanup = false }) { Text("취소") } })
    vehicleSwitchTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!vehicleSwitching) vehicleSwitchTarget = null },
            title = { Text("차량 선택") },
            text = { Text("${target.name}\n${target.id}\n기존 차량과 기록은 보존됩니다. 작성 중인 입력은 지워집니다. Mock 수집을 먼저 중단해야 합니다.") },
            confirmButton = { TextButton(enabled = !vehicleSwitching, onClick = { vm.selectVehicle(target); vehicleSwitchTarget = null }) { Text("차량 선택 확인") } },
            dismissButton = { TextButton(enabled = !vehicleSwitching, onClick = { vehicleSwitchTarget = null }) { Text("취소") } })
    }
    pendingDelete?.let { record ->
        AlertDialog(onDismissRequest = { if (!recordSaving) pendingDelete = null }, title = { Text("기록 삭제") },
            text = { Column { Text("${record.label} 기록을 삭제할까요? 삭제된 기록은 통계와 목록에서 제외됩니다."); recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !recordSaving, onClick = {
                vm.deleteRecord(record) {
                    if (editingId == record.id) clearEntry()
                    pendingDelete = null
                }
            }) { Text("삭제") } },
            dismissButton = { TextButton(enabled = !recordSaving, onClick = { pendingDelete = null }) { Text("취소") } })
    }
    pendingImport?.let { value ->
        AlertDialog(onDismissRequest = { vm.cancelImportMapping() }, title = { Text("차량 연결 확인") },
            text = { Column {
                Text("파일 차량 ID: ${value.event.vehicleId}\n현재 차량 ID: ${currentVehicle?.id ?: "없음"}\n같은 차량인지 확인한 뒤 연결해 주세요. 원본 차량 ID는 보존됩니다.")
                exchangeMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = currentVehicle != null && !importing, onClick = { vm.importToCurrentVehicle() }) { Text("현재 차량에 연결") } },
            dismissButton = { TextButton(enabled = !importing, onClick = { vm.cancelImportMapping() }) { Text("취소") } })
    }
    pendingSchedule?.let { schedule ->
        AlertDialog(onDismissRequest = { if (!recordSaving) pendingSchedule = null }, title = { Text("${schedule.label} 교체 기준 갱신") },
            text = { Column {
                Text("실제 교체일과 계기판의 주행거리를 직접 입력하세요. 정비 이력은 별도로 기록해야 합니다.")
                OutlinedTextField(replacementDate, { replacementDate = it }, label = { Text("교체일 YYYY-MM-DD") })
                OutlinedTextField(replacementKm, { replacementKm = it }, label = { Text("교체 시 주행거리 km") })
                replacementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !recordSaving, onClick = {
                val enteredKm = replacementKm.toDoubleOrNull()
                val day = try { LocalDate.parse(replacementDate).takeIf { it.year in 1900..2100 && !it.isAfter(LocalDate.now()) } } catch (_: Exception) { null }
                val doneAt = day?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                if (enteredKm != null && enteredKm.isFinite() && enteredKm >= schedule.lastKm &&
                    (schedule.intervalKm <= 0 || (enteredKm + schedule.intervalKm).isFinite()) &&
                    doneAt != null && (schedule.lastDoneAt == null || doneAt >= schedule.lastDoneAt)) {
                    vm.markScheduleDone(schedule, enteredKm, doneAt) { pendingSchedule = null }
                } else replacementError = "이전 교체 이후의 날짜와 계산 가능한 주행거리를 입력해 주세요. 미래 날짜는 사용할 수 없습니다."
            }) { Text("갱신") } }, dismissButton = { TextButton(enabled = !recordSaving, onClick = { pendingSchedule = null }) { Text("취소") } })
    }
    missingDateSchedule?.let { schedule ->
        AlertDialog(onDismissRequest = { if (!recordSaving) missingDateSchedule = null }, title = { Text("누락된 교체일 입력") },
            text = { Column {
                Text("기존 교체 주행거리를 유지하며 정비 이력을 추가하지 않습니다.")
                OutlinedTextField(missingScheduleDate, { missingScheduleDate = it }, label = { Text("마지막 교체일 (YYYY-MM-DD)") })
                missingScheduleDateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !recordSaving, onClick = {
                val day = try { LocalDate.parse(missingScheduleDate).takeIf { it.year in 1900..2100 && !it.isAfter(LocalDate.now()) } } catch (_: Exception) { null }
                val at = day?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                if (at == null) missingScheduleDateError = "오늘 이전 날짜를 YYYY-MM-DD 형식으로 입력해 주세요."
                else vm.fillMissingScheduleDate(schedule, at) { missingDateSchedule = null }
            }) { Text("저장") } }, dismissButton = { TextButton(enabled = !recordSaving, onClick = { missingDateSchedule = null }) { Text("취소") } })
    }
    editingSchedule?.let { schedule ->
        AlertDialog(onDismissRequest = { if (!recordSaving) editingSchedule = null }, title = { Text("교체 주기 수정") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("교체 기준일과 주행거리는 별도로 갱신합니다.")
                OutlinedTextField(scheduleLabel, { scheduleLabel = it }, label = { Text("항목") })
                OutlinedTextField(scheduleKm, { scheduleKm = it }, label = { Text("주기 km (선택)") })
                OutlinedTextField(scheduleMonths, { scheduleMonths = it }, label = { Text("주기 개월 (선택)") })
                if (schedule.category == "engine_oil") OutlinedTextField(scheduleHours, { scheduleHours = it }, label = { Text("주기 가동시간 (선택)") })
                scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !recordSaving, onClick = {
                val kmValue = scheduleKm.toDoubleOrNull(); val monthsValue = scheduleMonths.toLongOrNull(); val hoursValue = scheduleHours.toDoubleOrNull()
                val valid = scheduleLabel.isNotBlank() && (scheduleKm.isBlank() || (kmValue != null && kmValue.isFinite() && kmValue > 0)) &&
                    (kmValue == null || (schedule.lastKm + kmValue).isFinite()) &&
                    (scheduleMonths.isBlank() || (monthsValue != null && monthsValue in 1..1200)) &&
                    (scheduleHours.isBlank() || (hoursValue != null && hoursValue.isFinite() && hoursValue > 0)) &&
                    (kmValue != null || monthsValue != null || hoursValue != null) &&
                    ((monthsValue == null && hoursValue == null) || schedule.lastDoneAt != null)
                if (valid) { vm.updateSchedule(schedule, scheduleLabel, kmValue ?: 0.0, monthsValue, hoursValue) { editingSchedule = null } }
                else scheduleError = "양수 주기를 하나 이상 입력하세요. 기간·가동시간 주기는 교체 기준일이 필요합니다."
            }) { Text("저장") } }, dismissButton = { TextButton(enabled = !recordSaving, onClick = { editingSchedule = null }) { Text("취소") } })
    }
    deletingSchedule?.let { schedule ->
        AlertDialog(onDismissRequest = { if (!recordSaving) deletingSchedule = null }, title = { Text("교체 주기 삭제") },
            text = { Column { Text("${schedule.label} 주기를 목록에서 삭제할까요? 정비 이력은 삭제되지 않습니다."); recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(enabled = !recordSaving, onClick = { vm.deleteSchedule(schedule) { deletingSchedule = null } }) { Text("삭제") } },
            dismissButton = { TextButton(enabled = !recordSaving, onClick = { deletingSchedule = null }) { Text("취소") } })
    }
    if (editVehicle) {
        AlertDialog(onDismissRequest = { if (!vehicleSaving) editVehicle = false }, title = { Text("차량 정보 수정") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(vehicleName, { vehicleName = it }, enabled = !vehicleSaving, label = { Text("차량 이름") })
                OutlinedTextField(vehicleInitialKm, { vehicleInitialKm = it }, enabled = !vehicleSaving, label = { Text("등록 시 주행거리 km") })
                OutlinedTextField(vehicleMaker, { vehicleMaker = it }, enabled = !vehicleSaving, label = { Text("제조사 (선택)") })
                OutlinedTextField(vehicleModel, { vehicleModel = it }, enabled = !vehicleSaving, label = { Text("모델 (선택)") })
                OutlinedTextField(vehicleYear, { vehicleYear = it }, enabled = !vehicleSaving, label = { Text("연식 (선택)") })
                Text("연료 종류 (선택)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = vehicleFuelType == null, enabled = !vehicleSaving, onClick = { vehicleFuelType = null }, label = { Text("미입력") })
                    vehicleFuelTypes.forEach { (code, label) -> FilterChip(selected = vehicleFuelType == code, enabled = !vehicleSaving, onClick = { vehicleFuelType = code }, label = { Text(label) }) }
                }
                Text("차량 ID와 기존 기록은 유지됩니다.")
                vehicleInputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                vehicleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !vehicleSaving, onClick = {
                val initial = vehicleInitialKm.toDoubleOrNull()
                val year = vehicleYear.toIntOrNull()
                if (initial != null && (vehicleYear.isBlank() || year != null) && validVehicleInfo(vehicleName, initial, year, vehicleFuelType)) {
                    vehicleEditBaseline?.let { baseline -> vm.updateVehicle(baseline, vehicleName, initial, vehicleMaker, vehicleModel, year, vehicleFuelType) { editVehicle = false } }
                } else vehicleInputError = "차량 이름과 0 이상의 주행거리, 올바른 연식(1886~2100)을 확인해 주세요."
            }) { Text("저장") } },
            dismissButton = { TextButton(enabled = !vehicleSaving, onClick = { editVehicle = false }) { Text("취소") } })
    }
    recordPreview?.let { record ->
        AlertDialog(onDismissRequest = { recordPreview = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(.9f),
            title = { Text(record.label.ifBlank { record.type }) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${record.type} · ${date(record.timestamp)}")
                Text("${record.cost} 원", style = MaterialTheme.typography.headlineMedium)
                if (record.odometerKnown) Text("${record.odometer} km")
                recordDetails(record)?.let { Text(it) }
                record.repairOutcome?.let { Text(it) }
            } },
            confirmButton = { TextButton(onClick = { recordPreview = null }, modifier = Modifier.heightIn(min = 64.dp)) { Text("닫기") } })
    }
    Scaffold(
        topBar = {
          CarlogNavigation(managementSelected) { selected ->
              focusManager.clearFocus(); managementSelected = selected
          }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(if (managementSelected) managementScroll else eventScroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("시뮬레이션 데이터 · MOCK", color = CarlogColors.Warning, style = MaterialTheme.typography.labelMedium)
                Text(if (captureTransitioning) "연결 대기" else if (captureReady) "수집 중" else "수집 중단", style = MaterialTheme.typography.labelMedium, color = CarlogColors.SecondaryText)
                Text("정차 중 사용", style = MaterialTheme.typography.labelMedium, color = CarlogColors.SecondaryText)
            }
            if (!managementSelected) {
            val selectedEvent = events.firstOrNull { it.id == selectedId }
            if (selectedEvent != null) {
                OutlinedButton(onClick = {
                    selectedId = null
                    navigationScope.launch { eventScroll.scrollTo(0) }
                }, modifier = Modifier.heightIn(min = 56.dp)) { Text("‹  이벤트 목록으로") }
                shareCleanupMessage?.let { Text(it) }
                exchangeMessage?.let { Text(it) }
                key(selectedEvent.id) { EventDetail(selectedEvent, vm, records) { filename -> reportFile.launch(filename) } }
            } else {
            var showAllEvents by rememberSaveable { mutableStateOf(false) }
            val actions: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CarlogSpace.Gap)) {
                    Text("순간 기록", style = MaterialTheme.typography.titleLarge)
                    CarlogHeroAction(enabled = captureReady && !captureTransitioning && !markRequesting, requesting = markRequesting, onClick = {
                        voiceMessage = null
                        vm.mark("일반 이벤트", "") { }
                    })
                    CarlogSecondaryAction(label = "음성 기록", symbol = HmiSymbol.Microphone, enabled = captureReady && !captureTransitioning && !markRequesting, modifier = Modifier.fillMaxWidth(), onClick = {
                        voiceTone = HmiTone.Progress
                        voiceMessage = "음성 인식 중"
                        if (!vm.beginVoiceCommand("일반 이벤트", "")) {
                            voiceTone = HmiTone.Error
                            voiceMessage = "음성 인식을 시작할 수 없습니다. 수집 상태를 확인해 주세요."
                        } else try {
                            voiceCommand.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "기록할 내용을 말씀해 주세요"))
                        } catch (_: ActivityNotFoundException) {
                            vm.cancelVoiceCommand()
                            voiceTone = HmiTone.Error
                            voiceMessage = "사용 가능한 음성 인식 앱이 없습니다. 기록 버튼을 이용해 주세요."
                        } catch (_: SecurityException) {
                            vm.cancelVoiceCommand()
                            voiceTone = HmiTone.Error
                            voiceMessage = "음성 인식 앱을 열 수 없습니다. 기록 버튼을 이용해 주세요."
                        }
                    })
                    Text(
                        if (!captureReady || captureTransitioning) "현재 사전 버퍼: 수집 중단 또는 연결 대기"
                        else oldestPreEventSampleAge?.let { "현재 사전 버퍼: 가장 오래된 신호 ${it / 1000}초 전 · 연속 수집 보장 안 됨" }
                            ?: "현재 사전 버퍼: 신호 대기 중",
                        style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText,
                    )
                    val recentCapture = events.firstOrNull { it.status == "collecting" } ?: events.firstOrNull()
                    when {
                        captureError != null -> HmiStatus("수집·저장 오류", captureError, HmiTone.Error)
                        markRequesting -> HmiStatus("기록 요청 접수 중", "기록을 시작하고 있습니다.", HmiTone.Progress)
                        recentCapture?.status == "collecting" -> {
                            val remaining = ((recentCapture.timestamp + 60_000 - now).coerceAtLeast(0) + 999) / 1000
                            HmiStatus("사후 데이터 수집 중", if (remaining > 0) "약 ${remaining}초 남음 · 아직 수집 완료 전" else "수집 데이터 저장 확인 중", HmiTone.Progress,
                                ((now - recentCapture.timestamp) / 60_000f).coerceIn(0f, .99f))
                        }
                        recentCapture?.status == "complete" -> HmiStatus("최근 이벤트 수집 완료", tone = HmiTone.Success)
                        recentCapture?.status == "interrupted" -> HmiStatus("최근 이벤트 수집 중단", "확보된 신호는 상세에서 확인하세요.", HmiTone.Error)
                        else -> HmiStatus("기록 대기")
                    }
                    voiceMessage?.let { HmiStatus(it, tone = voiceTone) }
            }
            }
            val timeline: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CarlogSpace.Gap)) {
            Text("최근 이벤트", style = MaterialTheme.typography.titleLarge)
            if (events.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = CarlogSpace.Small),
                    horizontalArrangement = Arrangement.spacedBy(CarlogSpace.Gap), verticalAlignment = Alignment.CenterVertically) {
                    HmiIcon(HmiSymbol.History, Modifier.size(28.dp), CarlogColors.TertiaryText)
                    Text("아직 기록된 이벤트가 없습니다.", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                }
            }
            events.take(if (showAllEvents) visibleTimelineCount else 3).forEachIndexed { index, event ->
                OutlinedCard(
                    onClick = { selectedId = event.id; navigationScope.launch { eventScroll.scrollTo(0) } },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "이벤트 ${index + 1}, ${date(event.timestamp)}, ${event.category}, ${eventStatusLabel(event.status)}, ${eventSourceLabel(event.source)}, 메모: ${eventMemoPreview(event.memo)}, ID ${event.id}"
                    }
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        EventCategoryIcon(event.category)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(event.category, style = MaterialTheme.typography.titleLarge)
                            Text(date(event.timestamp), style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                            Text(eventStatusLabel(event.status), style = MaterialTheme.typography.labelMedium,
                                color = when(event.status) { "complete" -> CarlogColors.Success; "collecting" -> CarlogColors.Info; else -> CarlogColors.Error })
                            if (event.memo.isNotBlank()) Text(eventMemoPreview(event.memo), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)

                        }
                        HmiIcon(HmiSymbol.Arrow, Modifier.size(24.dp), tint = CarlogColors.TertiaryText)
                    }
                }
            }
            if (!showAllEvents && events.size > 3) {
                TextButton(onClick = { showAllEvents = true }) { Text("전체 이벤트 보기 (${events.size}개)") }
            } else if (showAllEvents && events.size > visibleTimelineCount) {
                TextButton(onClick = { visibleTimelineCount += minOf(20, events.size - visibleTimelineCount) }) {
                    Text("타임라인 더 보기 (${events.size - visibleTimelineCount}개 남음)")
                }
            }
            }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 700.dp
                if (wide) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(CarlogSpace.Gap), verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(.55f).padding(CarlogSpace.Inset)) { actions() }
                        Box(Modifier.weight(.45f).padding(CarlogSpace.Inset)) { timeline() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(CarlogSpace.Section)) {
                        actions()
                        timeline()
                    }
                }
            }
            shareCleanupMessage?.let { Text(it) }
            exchangeMessage?.let { Text(it) }
            TextButton(onClick = { eventToolsExpanded = !eventToolsExpanded }) {
                Text(if (eventToolsExpanded) "테스트 도구 접기" else "테스트 도구 펼치기")
            }
            if (eventToolsExpanded) { CarlogUtilityPanel {
                Text("테스트 도구 · 시뮬레이션", style = MaterialTheme.typography.titleMedium)
                Text("음성은 인식 앱에서 처리하며 네트워크로 전송될 수 있습니다.", style = MaterialTheme.typography.bodyMedium)
                Text("Mock 수집을 중단하면 진행 중인 이벤트도 중단되고, 다시 시작할 때 이전 버퍼는 지워집니다. 앱 프로세스가 종료되면 수집이 중단됩니다.")
                OutlinedButton(enabled = !captureTransitioning, modifier = Modifier.heightIn(min = 48.dp), onClick = { vm.setMockCollection(!captureReady) }) {
                    Text(if (captureReady) "Mock 수집 중단" else "Mock 수집 다시 시작")
                }
                if (captureReady) Text(oldestPreEventSampleAge?.let { "최근 60초 중 가장 오래된 신호: ${it / 1000}초 전 · 연속 수집을 뜻하지 않음" } ?: "사전 신호 대기 중")
                else Text("사전 신호 수집 중단")
                TextButton(onClick = { confirmShareCleanup = true }) { Text("임시 공유 파일 정리") }
                Button(enabled = !importing, onClick = { try { importFile.launch(arrayOf("application/json")) } catch (_: Exception) { vm.eventPickerFailed() } }) { Text("이벤트 JSON 파일 가져오기") }
            } }
            }
            } else {
            val recordedDistance = records.filter { it.deletedAt == null && it.odometerKnown }
                .mapNotNull { it.odometer.takeIf { value -> value.isFinite() && value >= 0 } }.maxOrNull()
                ?: currentVehicle?.initialKm?.takeIf { it.isFinite() && it > 0 }
            val monthStart = spendingPeriodStart(SpendingPeriod.THIS_MONTH, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
            val monthWallet = spendingStats(records, startMillis = monthStart)
            val monthRecords = records.filter { it.deletedAt == null && (monthStart == null || it.timestamp >= monthStart) }
            val fuelSignal = latest["fuel"]?.takeIf { it.unit == "%" && it.value.isFinite() && it.value in 0.0..100.0 }
            Text("차량 관리", style = MaterialTheme.typography.titleLarge)
            Text("기록할 항목을 선택하세요", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
            val hasEntryDraft = label.isNotBlank() || km.isNotBlank() || amount.isNotBlank() || cost.isNotBlank() ||
                engineHours.isNotBlank() || intervalMonths.isNotBlank() || lastDoneDate.isNotBlank() ||
                intervalHours.isNotBlank() || recordDate.isNotBlank() || repairOutcome.isNotBlank() || linkedEventId != null ||
                !full || expenseCategory != "기타" || incrementalHours || isOil
            val shortcuts = listOf("주유" to HmiSymbol.Record, "소모품" to HmiSymbol.Tools,
                "정비" to HmiSymbol.Tools, "지출" to HmiSymbol.Record)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 700.dp) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(CarlogSpace.Gap)) {
                    shortcuts.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CarlogSpace.Gap)) {
                            row.forEach { (shortcutType, symbol) ->
                                CarlogSecondaryAction(
                                    label = "$shortcutType 목록", symbol = symbol,
                                    modifier = Modifier.weight(1f),
                                    onClick = { managementCategory = if (managementCategory == shortcutType) null else shortcutType },
                                )
                            }
                        }
                    }
                }
            }
            managementCategory?.let { category ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(CarlogSpace.Gap)) {
                        Text("$category 목록", style = MaterialTheme.typography.titleMedium)
                        if (category == "소모품") {
                            val items = schedules.filter { it.deletedAt == null }
                            if (items.isEmpty()) Text("등록된 소모품 주기가 없습니다.")
                            items.take(3).forEach { Text("${it.label} · 마지막 교체 ${"%.0f".format(it.lastKm)} km") }
                            if (items.size > 3) Text("전체 ${items.size}개 · 아래 소모품 관리에서 확인")
                        } else {
                            val items = records.filter { it.deletedAt == null && it.type == category }
                            if (items.isEmpty()) Text("등록된 $category 기록이 없습니다.")
                            items.take(3).forEach { record ->
                                Surface(onClick = { recordPreview = record }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${record.label.ifBlank { record.type }} · ${date(record.timestamp)} · ${record.cost}원", Modifier.padding(12.dp))
                                }
                            }
                            if (items.size > 3) Text("전체 ${items.size}개 · 아래 전체 차량 기록에서 확인")
                        }
                        CarlogSecondaryAction(label = "$category 기록 추가", symbol = HmiSymbol.Plus,
                            enabled = !recordSaving && editingId == null && (!hasEntryDraft || type == category),
                            onClick = { type = category; openRecordEntry() })
                        if (editingId != null || (hasEntryDraft && type != category))
                            Text("작성 중인 기록을 저장하거나 입력 내용을 비워 주세요.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            ManagementDashboard(
                vehicle = currentVehicle?.name ?: "정보 없음",
                distance = recordedDistance?.let { "%.0f km".format(it) } ?: "기록 없음",
                fuel = fuelSignal?.let { "%.0f%%".format(it.value) } ?: "정보 없음",
                updated = listOfNotNull(fuelSignal, latest["ev_battery_soc"]?.takeIf { it.unit == "%" && it.value.isFinite() && it.value in 0.0..100.0 }).maxOfOrNull { it.timestamp }?.let { "${date(it)} · ${if (captureReady) "수집 중" else "마지막 관측"}" } ?: "기록 없음",
                economy = stats.average?.let { "%.1f km/L".format(it) } ?: "기록 부족",
                monthly = if (monthWallet.monthly.isEmpty()) "기록 없음" else "${monthWallet.total}원",
                fuelCost = if (monthRecords.none { it.type == "주유" }) "기록 없음" else "${monthWallet.fuel}원",
                maintenanceCost = if (monthRecords.none { it.type == "정비" }) "기록 없음" else "${monthWallet.maintenance}원",
                battery = latest["ev_battery_soc"]?.takeIf { it.unit == "%" && it.value.isFinite() && it.value in 0.0..100.0 }?.let { "%.0f%%".format(it.value) } ?: "정보 없음",
                monthlyHistory = spendingStats(records).monthly
            )
            Text("최근 차량 관리 기록", style = MaterialTheme.typography.titleLarge)
            if (records.isEmpty()) HmiEmptyState("최근 기록 없음", "주유·정비·지출 기록을 추가해 차량 관리를 시작하세요.")
            records.take(3).forEach { record ->
                Surface(onClick = { recordPreview = record }, color = CarlogColors.Surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        HmiIcon(if (record.type == "정비") HmiSymbol.Tools else HmiSymbol.Record, Modifier.size(28.dp), CarlogColors.SecondaryText)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.label.ifBlank { record.type }, style = MaterialTheme.typography.titleMedium)
                            Text("${record.type} · ${date(record.timestamp)}", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                        }
                        Text(if (record.type == "주행거리") "${record.odometer} km" else "${record.cost} 원", style = MaterialTheme.typography.titleLarge)
                        HmiIcon(HmiSymbol.Arrow, Modifier.size(24.dp))
                    }
                }
            }
            CarlogSecondaryAction(label = "기록 추가", symbol = HmiSymbol.Plus, onClick = { openRecordEntry() })
            TextButton(onClick = { managementDetailsExpanded = !managementDetailsExpanded }) { Text(if (managementDetailsExpanded) "차량 및 통계 상세 접기" else "차량 및 통계 상세 보기") }
            if (managementDetailsExpanded) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("차량 요약", style = MaterialTheme.typography.titleMedium)
                if (records.isEmpty()) Text("최근 차량 기록 없음")
                else records.take(3).forEach { record ->
                    Text("최근 기록: ${date(record.timestamp)} · ${dashboardRecordSummary(record)}")
                }
                Text(currentVehicle?.name ?: "차량", style = MaterialTheme.typography.titleSmall)
                val recordedKm = records.filter { it.odometerKnown }.mapNotNull { it.odometer.takeIf { value -> value.isFinite() && value >= 0 } }.maxOrNull()
                    ?: currentVehicle?.initialKm?.takeIf { it.isFinite() && it > 0 }
                Text(recordedKm?.let { "기록된 주행거리 ${"%.1f".format(it)} km" } ?: "주행거리 기록 없음", style = MaterialTheme.typography.titleMedium)
                currentVehicle?.let { car ->
                    val details = listOfNotNull(car.maker, car.model, car.year?.let { "${it}년" }, vehicleFuelTypes.firstOrNull { it.first == car.fuelType }?.second)
                    if (details.isNotEmpty()) Text(details.joinToString(" · "))
                }
                if (mockOdometer == null) Text("시뮬레이션 주행거리 ${emptyMockSignalStatus(captureReady, captureTransitioning)}")
                else if (mockOdometerValid) {
                    Text("시뮬레이션 주행거리 ${"%.1f".format(mockOdometer.value)} km · ${mockSignalStatus(mockOdometer, captureReady, now)} · ${date(mockOdometer.timestamp)}")
                } else Text("시뮬레이션 주행거리 신호의 단위 또는 범위를 확인할 수 없습니다.")
                val mockFuel = latest["fuel"]
                if (mockFuel == null) Text("시뮬레이션 연료 ${emptyMockSignalStatus(captureReady, captureTransitioning)}")
                else if (mockFuel.unit == "%" && mockFuel.value in 0.0..100.0) {
                    Text("시뮬레이션 연료 ${"%.1f".format(mockFuel.value)}% · ${mockSignalStatus(mockFuel, captureReady, now)} · ${date(mockFuel.timestamp)}")
                } else Text("시뮬레이션 연료 신호의 단위 또는 범위를 확인할 수 없습니다.")
                val mockBattery = latest["ev_battery_soc"]
                if (mockBattery == null) Text("시뮬레이션 EV 배터리 ${emptyMockSignalStatus(captureReady, captureTransitioning)}")
                else if (mockBattery.unit == "%" && mockBattery.value in 0.0..100.0) {
                    Text("시뮬레이션 EV 배터리 ${"%.1f".format(mockBattery.value)}% · ${mockSignalStatus(mockBattery, captureReady, now)} · ${date(mockBattery.timestamp)}")
                } else Text("시뮬레이션 EV 배터리 신호의 단위 또는 범위를 확인할 수 없습니다.")
                currentVehicle?.let { Text("차량 ID: ${it.id}", style = MaterialTheme.typography.bodySmall) }
                if (vehicles.size > 1) {
                    Text("차량 선택 · 전환 전 Mock 수집 중단", style = MaterialTheme.typography.bodySmall)
                    vehicleExportMessage?.let { Text(it) }
                    vehicles.filter { it.id != currentVehicle?.id }.forEach { car ->
                        TextButton(enabled = !vehicleSwitching, onClick = { vehicleSwitchTarget = car }) { Text("${car.name} · ${car.id} 선택") }
                    }
                }
                TextButton(enabled = currentVehicle != null, onClick = {
                    currentVehicle?.let { vm.clearVehicleError(); vehicleEditBaseline = it; vehicleName = it.name; vehicleInitialKm = it.initialKm.toString(); vehicleMaker = it.maker.orEmpty(); vehicleModel = it.model.orEmpty(); vehicleYear = it.year?.toString().orEmpty(); vehicleFuelType = it.fuelType; vehicleInputError = null; editVehicle = true }
                }) { Text("차량 정보 수정") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("연비와 지출", style = MaterialTheme.typography.titleMedium)
            Text("신뢰 평균 연비: ${stats.average?.let { "%.1f km/L".format(it) } ?: "기록 부족"}")
            stats.recent?.let { Text("${if (stats.trustedCount > 0) "최근 신뢰 연비" else "최근 참고 연비"}: ${"%.1f".format(it)} km/L") }
            if (stats.trustedCount > 0) Text("신뢰 구간 ${stats.trustedCount}개 · 최고 ${"%.1f".format(stats.best)} / 최저 ${"%.1f".format(stats.worst)} km/L")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpendingPeriod.entries.forEach { period ->
                    FilterChip(selected = spendingPeriod == period, onClick = { spendingPeriod = period }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(period.label) })
                }
            }
            if (wallet.monthly.isEmpty()) Text("${spendingPeriod.label} 지출 기록 없음")
            else Text("${spendingPeriod.label} 지출 ${wallet.total}원 · 주유 ${wallet.fuel}원 · 정비 ${wallet.maintenance}원 · 일반 지출 ${wallet.other}원")
            val periodStart = spendingPeriodStart(spendingPeriod, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
            val distanceReadings = records.count { it.deletedAt == null && it.type in setOf("주유", "정비", "지출", "주행거리") &&
                (periodStart == null || it.timestamp >= periodStart) && it.odometerKnown && it.odometer.isFinite() && it.odometer >= 0 }
            if (distanceReadings < 2) Text("주행거리 통계: 기록 부족")
            else Text("주행거리·주유·정비·지출 기록 기준 ${"%.0f".format(wallet.distance)} km · km당 ${wallet.costPerKm?.let { "%.1f원".format(it) } ?: "계산 대기"}")
            wallet.byCategory.forEach { category -> Text("  ${if (category.key.startsWith("expense:")) "일반 지출 · " else ""}${category.label} ${category.total}원") }
            Text("월별 지출", style = MaterialTheme.typography.titleSmall)
            if (wallet.monthly.isEmpty()) Text("지출 기록 없음")
            wallet.monthly.takeLast(6).forEach { Text("${it.month} · ${it.total}원") }
            }
            }
            }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("교체 예정", style = MaterialTheme.typography.titleMedium)
            if (scheduledDue.isEmpty()) HmiEmptyState("등록된 소모품 교체 주기가 없습니다.", "소모품 교체 기록을 추가하면 다음 교체 시기를 확인할 수 있습니다.")
            if (!hasMaintenanceOdometer) Text("교체 판정 주행거리: 정보 부족")
            else Text("소모품 교체 판정 기준 ${"%.1f".format(currentKm)} km${if (!useMockOdometer && !(useEstimatedOdometer && projectedKm != null)) " · 입력 기록 기준" else if (!useMockOdometer) " · 입력·추정값 후보" else if (mockOdometerValid && mockOdometer != null && mockSignalStatus(mockOdometer, captureReady, now) == "수집 중") " · 시뮬레이션 적용" else if (mockOdometerValid) " · 시뮬레이션 마지막 값 적용" else " · 시뮬레이션 값 대기"}${if (useMockOdometer && useEstimatedOdometer && projectedKm != null) " · 추정값 후보 포함" else ""}")
            if (!hasMaintenanceOdometer && scheduledDue.isNotEmpty()) Text("현재 주행거리가 없어 거리 기준 교체 예정 결과를 확인할 수 없습니다.", color = MaterialTheme.colorScheme.error)
            projectedKm?.let { Text("입력 기록 기반 추정 주행거리 ${"%.0f".format(it)} km · 실측값 아님${if (useEstimatedOdometer) " · 교체 판정 후보" else " · 교체 판정에 미적용"}") }
            recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            scheduledDue.forEach { (schedule, due, missingEngineHours, invalidCalendarInterval, invalidConfiguration) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasMaintenanceOdometer || due.remaining != null || schedule.intervalKm <= 0) {
                    Text("${schedule.label}: ${maintenanceDueLabel(due)} · ${if (due.remaining == null) "확인 필요" else dueStatusLabel(due.status)}", style = MaterialTheme.typography.titleMedium)
                } else Text("${schedule.label}: 주행거리 정보 부족 · 확인 필요", style = MaterialTheme.typography.titleMedium)
                if (due.remaining != null) {
                    val dueTone = when (due.status) { Due.OK -> HmiTone.Success; Due.SOON -> HmiTone.Warning; else -> HmiTone.Error }
                    HmiStatus(maintenanceDueLabel(due), dueStatusLabel(due.status), dueTone)
                }
                Text(schedule.lastDoneAt?.let { "마지막 교체: ${date(it)}" } ?: "마지막 교체일: 기록 없음", style = MaterialTheme.typography.bodyLarge)
                Text("마지막 교체 기준: ${"%.0f".format(schedule.lastKm)} km", style = MaterialTheme.typography.bodyMedium)
                if (missingEngineHours) Text("엔진 가동시간 기록이 없거나 계산할 수 없어 해당 주기를 확인할 수 없습니다.")
                if (invalidCalendarInterval) Text("날짜 교체 주기를 계산할 수 없습니다. 주기를 수정해 주세요.", color = MaterialTheme.colorScheme.error)
                if (invalidConfiguration) Text("교체 주기 설정이 불완전하거나 유효하지 않습니다. 값을 확인해 주세요.", color = MaterialTheme.colorScheme.error)
                if (schedule.lastDoneAt == null && (schedule.intervalMonths != null || schedule.intervalEngineHours != null)) {
                    TextButton(onClick = { vm.clearRecordError(); missingScheduleDate = ""; missingScheduleDateError = null; missingDateSchedule = schedule }) { Text("누락된 교체일 입력") }
                }
                TextButton(onClick = { vm.clearRecordError(); replacementKm = ""; replacementDate = ""; replacementError = null; pendingSchedule = schedule }) { Text("${schedule.label} 교체 기준 갱신") }
                Row {
                    TextButton(onClick = {
                        scheduleLabel = schedule.label; scheduleKm = schedule.intervalKm.takeIf { it > 0 }?.toString() ?: ""
                        scheduleMonths = schedule.intervalMonths?.toString() ?: ""; scheduleHours = schedule.intervalEngineHours?.toString() ?: ""
                        vm.clearRecordError(); scheduleError = null; editingSchedule = schedule
                    }) { Text("주기 수정") }
                    TextButton(onClick = { vm.clearRecordError(); deletingSchedule = schedule }) { Text("주기 삭제") }
                }
                }
                }
            }
            }
            }
            HorizontalDivider(); Text("차량 기록과 데이터 관리", style = MaterialTheme.typography.titleLarge)
            LaunchedEffect(vehicleImportPreview, vehicleImportBusy, vehicleExportMessage, backupPreview, backupLoading, backupMessage) {
                if (vehicleImportPreview != null || vehicleImportBusy || vehicleExportMessage != null || backupPreview != null || backupLoading || backupMessage != null) {
                    managementToolsExpanded = true
                }
            }
            TextButton(onClick = { managementToolsExpanded = !managementToolsExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (managementToolsExpanded) "차량 테스트 도구 접기" else "차량 테스트 도구 펼치기")
            }
            if (managementToolsExpanded) { CarlogUtilityPanel {
            Text("교체 계산 테스트", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = useMockOdometer, onCheckedChange = { useMockOdometer = it })
                Text("시뮬레이션 주행거리를 교체 판정에 적용")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = useEstimatedOdometer, enabled = projectedKm != null, onCheckedChange = { useEstimatedOdometer = it })
                Text("추정 주행거리를 교체 판정에 적용")
            }
            if (useEstimatedOdometer && projectedKm != null) Text("판정에는 입력·선택한 추정 및 시뮬레이션 값 중 가장 큰 거리를 사용합니다.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = currentVehicle != null && !vehicleExportPreparing, onClick = {
                vm.prepareVehicleExport { vehicleExportFile.launch("carlog-vehicle-records.json") }
            }) { Text("차량 관리 기록 JSON 저장") }
            Text("현재 차량의 활성 기록과 소모품 주기를 저장합니다. 삭제 기록과 이벤트는 포함하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = currentVehicle != null && !vehicleImportBusy && vehicleImportPreview == null, onClick = { try { vehicleImportFile.launch(arrayOf("application/json")) } catch (_: Exception) { vm.vehiclePickerFailed() } }) { Text("차량 관리 JSON 가져오기") }
            Text("같은 차량 UUID와 차량 정보만 허용합니다. 연결된 이벤트는 먼저 가져와야 합니다.", style = MaterialTheme.typography.bodySmall)
            vehicleImportPreview?.let { preview ->
                Text("가져올 차량: ${preview.vehicle.name} · ${preview.vehicle.id}\n현재 차량: ${currentVehicle?.name ?: "없음"} · ${currentVehicle?.id ?: "없음"}\n기록 ${preview.records.size}건 · 소모품 주기 ${preview.schedules.size}건")
                val invalidSchedules = preview.schedules.count(::invalidScheduleConfiguration)
                if (invalidSchedules > 0) Text("설정 확인이 필요한 소모품 주기 ${invalidSchedules}건이 있습니다. 가져온 뒤 값을 확인해 주세요.", color = MaterialTheme.colorScheme.error)
                if (currentVehicle?.id != preview.vehicle.id) {
                    Text("파일의 차량을 별도로 선택한 뒤 기록을 가져올 수 있습니다. 기존 차량에 병합하지 않습니다.")
                    TextButton(enabled = !vehicleSwitching && !vehicleImportBusy, onClick = { vehicleSwitchTarget = preview.vehicle }) { Text("파일 차량 등록·선택") }
                } else if (currentVehicle != preview.vehicle) {
                    Text("차량 정보가 다릅니다. 이름, 초기 주행거리, 제조사, 모델, 연식, 연료 종류를 확인해 주세요.", color = MaterialTheme.colorScheme.error)
                }
                Row {
                    TextButton(enabled = !vehicleSwitching && !vehicleImportBusy && currentVehicle == preview.vehicle, onClick = { vm.applyVehicleImport() }) { Text("가져오기 적용") }
                    TextButton(enabled = !vehicleSwitching && !vehicleImportBusy, onClick = { vm.cancelVehicleImport() }) { Text("취소") }
                }
            }
            vehicleExportMessage?.let { Text(it) }
            Button(onClick = { try { previewFile.launch(arrayOf("application/json")) } catch (_: Exception) { vm.backupPickerFailed() } }) { Text("Carlog 웹 백업 미리보기") }
            if (backupLoading) {
                Text("웹 백업을 읽는 중입니다.")
                TextButton(onClick = { vm.closeBackupPreview() }) { Text("백업 읽기 취소") }
            }
            backupMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            backupPreview?.let { preview ->
                fun tableCount(name: String, count: Int?): String = if (name in preview.missingTables) "확인 불가" else "${count ?: 0}건"
                fun linkedCount(count: Int, vararg required: String): String =
                    if (required.any { it in preview.missingTables }) "전체 확인 불가 (있는 자료 기준 ${count}건)" else "${count}건"
                Text("읽기 전용 검사 · 앱에 데이터를 가져오지 않습니다.")
                if (preview.missingTables.isNotEmpty()) Text("백업에 없는 항목: ${preview.missingTables.joinToString(", ")}. 해당 건수는 확인할 수 없으며 검사 결과는 있는 항목만 기준입니다.", color = MaterialTheme.colorScheme.error)
                Text("차량 ${tableCount("vehicles", preview.vehicles.size)} · 주유 ${tableCount("fuelLogs", preview.totals["fuelLogs"])} · 정비 ${tableCount("maintenanceLogs", preview.totals["maintenanceLogs"])} · 지출 ${tableCount("expenseLogs", preview.totals["expenseLogs"])}")
                preview.vehicles.forEach { Text("${it.name}${if (it.deleted) " (삭제됨)" else ""} · ${it.id}\n주유 ${tableCount("fuelLogs", it.fuels)} · 정비 ${tableCount("maintenanceLogs", it.maintenance)} · 지출 ${tableCount("expenseLogs", it.expenses)}") }
                Text("파일에 포함된 항목 중 삭제 표시 기록 ${preview.deletedRecords}건 · ID 누락 ${preview.invalidIds}건 · ID 중복 ${preview.duplicateIds}건 · 차량 연결 확인 불가 ${tableCount("vehicles", preview.unknownVehicleRefs)}")
                if (preview.invalidDeletionStatuses > 0) Text("삭제 표시 값이 문자열이 아니거나 비어 있는 항목 ${preview.invalidDeletionStatuses}건. 활성·삭제 집계의 정확성을 보장할 수 없습니다.", color = MaterialTheme.colorScheme.error)
                Text("파일에 포함된 활성 기록 중 삭제된 차량 참조 ${tableCount("vehicles", preview.deletedVehicleRefs)} · 중복 차량 ID 참조 ${tableCount("vehicles", preview.ambiguousVehicleRefs)}")
                Text("분류를 확인할 수 없는 활성 지출 ${tableCount("expenseLogs", preview.unknownExpenseCategories)}")
                Text("활성 정비 중 복수 항목 ${tableCount("maintenanceLogs", preview.multiItemMaintenance)} · 항목 배열 확인 불가 ${tableCount("maintenanceLogs", preview.invalidMaintenanceItems)}. 복수 항목의 무손실 이관은 아직 지원하지 않습니다.")
                Text("활성 주행거리: 직접 입력 ${tableCount("odometerReadings", preview.manualOdometerReadings)} · 다른 기록에서 파생 ${tableCount("odometerReadings", preview.derivedOdometerReadings)} · 출처 미확인 ${tableCount("odometerReadings", preview.unknownOdometerSources)}")
                Text("파생 주행거리의 활성 원본 연결을 확인할 수 없는 기록 ${linkedCount(preview.brokenOdometerReferences, "odometerReadings", "fuelLogs", "maintenanceLogs", "expenseLogs")}. 파생 기록을 따로 가져오면 중복될 수 있습니다.")
                Text("같은 활성 원본을 가리키는 추가 주행거리 기록 ${linkedCount(preview.duplicateDerivedOdometerReadings, "odometerReadings", "fuelLogs", "maintenanceLogs", "expenseLogs")}. 원본 연결이 확인된 기록만 검사합니다.")
                Text("영수증 ${tableCount("receiptImages", preview.totals["receiptImages"])} · 사용자 항목 ${tableCount("customCategories", preview.totals["customCategories"])}. 원본 백업을 보관하세요.")
                Text("활성 영수증 중 원본 기록 연결을 확인할 수 없는 항목 ${linkedCount(preview.brokenReceiptReferences, "receiptImages", "fuelLogs", "maintenanceLogs", "expenseLogs")}")
                Text("활성 주유·정비·지출 중 영수증 연결을 확인할 수 없는 항목 ${linkedCount(preview.brokenRecordReceiptReferences, "receiptImages", "fuelLogs", "maintenanceLogs", "expenseLogs")}")
                TextButton(onClick = { vm.closeBackupPreview() }) { Text("미리보기 닫기") }
            }
            } }
            TextButton(modifier = Modifier.onGloballyPositioned { recordsTarget = it.positionInParent().y.toInt() }, onClick = { recordEntryExpanded = !recordEntryExpanded }) {
                Text(if (recordEntryExpanded) "기록 입력 접기" else "차량 관리 기록 추가")
            }
            if (recordEntryExpanded || editingId != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("주행거리", "주유", "지출", "정비", "소모품").forEach { FilterChip(selected = type == it, enabled = editingId == null || it == type, onClick = { type = it }, label = { Text(it) }) } }
            if (editingId != null) Text("기록 수정 중 · 종류는 변경할 수 없습니다", style = MaterialTheme.typography.titleSmall)
            if (type == "지출") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pwaExpenseCategories.forEach { (_, label) -> FilterChip(selected = expenseCategory == label, onClick = { expenseCategory = label }, label = { Text(label) }) }
                }
            }
            OutlinedTextField(label, { label = it }, label = { Text(if (type == "주행거리") "메모 (선택)" else "항목 / 메모") })
            OutlinedTextField(km, { km = it }, label = { Text(if (type == "지출") "주행거리 km (선택)" else "주행거리 km") })
            if (type != "소모품") OutlinedTextField(recordDate, { recordDate = it }, label = { Text(if (editingId == null) "날짜 YYYY-MM-DD (비우면 오늘)" else "새 날짜 YYYY-MM-DD (비우면 유지)") })
            if (type == "주유" || type == "소모품") OutlinedTextField(amount, { amount = it }, label = { Text(if (type == "소모품") "교체 주기 km (선택)" else "주유량 L") })
            if (type != "소모품" && type != "주행거리") OutlinedTextField(cost, { cost = it }, label = { Text("금액 원") })
            if (type == "주유") {
                Row { Checkbox(full, { full = it }); Text("가득 주유") }
                OutlinedTextField(engineHours, { engineHours = it }, label = { Text("엔진 가동시간 (선택)") })
                Row { Checkbox(incrementalHours, { incrementalHours = it }); Text("지난 주유 이후 증가분") }
            }
            if (type == "정비" || type == "소모품") Row { Checkbox(isOil, { isOil = it }); Text("엔진오일") }
            if (type == "정비") {
                OutlinedTextField(repairOutcome, { repairOutcome = it }, label = { Text("정비 결과 (선택)") })
                Text("관련 이벤트 (선택)")
                linkedEventId?.let { id ->
                    val linked = events.firstOrNull { it.id == id }
                    Text(linked?.let { "연결됨: ${date(it.timestamp)} · ${it.category} · ID ${it.id} · 메모: ${eventMemoPreview(it.memo)}" } ?: "선택한 이벤트를 찾을 수 없습니다.")
                }
                TextButton(onClick = { linkedEventId = null }) { Text(if (linkedEventId == null) "연결 없음 · 선택됨" else "연결 없음") }
                events.take(visibleLinkEventCount).forEach { event ->
                    TextButton(onClick = { linkedEventId = event.id }) {
                        Text("${if (linkedEventId == event.id) "선택됨 · " else ""}${date(event.timestamp)} · ${event.category} · ID ${event.id} · 메모: ${eventMemoPreview(event.memo)}")
                    }
                }
                if (events.size > visibleLinkEventCount) {
                    TextButton(onClick = { visibleLinkEventCount += minOf(10, events.size - visibleLinkEventCount) }) {
                        Text("이벤트 더 보기 (${events.size - visibleLinkEventCount}개 남음)")
                    }
                }
            }
            if (type == "소모품") {
                OutlinedTextField(intervalMonths, { intervalMonths = it }, label = { Text("교체 주기 개월 (선택)") })
                OutlinedTextField(lastDoneDate, { lastDoneDate = it }, label = { Text("마지막 교체일 YYYY-MM-DD") })
                if (isOil) OutlinedTextField(intervalHours, { intervalHours = it }, label = { Text("교체 주기 엔진 가동시간 (선택)") })
            }
            entryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            recordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !recordSaving && currentVehicle != null, onClick = {
                val distance = km.toDoubleOrNull(); val quantity = amount.toDoubleOrNull(); val price = cost.toLongOrNull()
                val fuelHours = engineHours.toDoubleOrNull()
                val months = intervalMonths.toLongOrNull()
                val hours = intervalHours.toDoubleOrNull()
                val doneAt = try {
                    LocalDate.parse(lastDoneDate).takeIf { it.year in 1900..2100 }
                        ?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                } catch (_: Exception) { null }
                val enteredDate = try {
                    LocalDate.parse(recordDate).takeIf { it.year in 1900..2100 }
                        ?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                } catch (_: Exception) { null }
                val distanceValid = (type == "지출" && km.isBlank()) || (distance != null && distance.isFinite() && distance >= 0)
                val basicValid = (label.isNotBlank() || type == "주행거리") && distanceValid &&
                    (type == "소모품" || ((recordDate.isBlank() || enteredDate != null) && (type == "주행거리" || cost.isBlank() || (price != null && price >= 0))))
                val fuelValid = type != "주유" || (quantity != null && quantity.isFinite() && quantity > 0 &&
                    (engineHours.isBlank() || (fuelHours != null && fuelHours.isFinite() && fuelHours >= 0)))
                val scheduleValid = type != "소모품" || (
                    (amount.isBlank() || (quantity != null && quantity.isFinite() && quantity > 0)) &&
                    (quantity == null || distance == null || (distance + quantity).isFinite()) &&
                    (intervalMonths.isBlank() || (months != null && months in 1..1200)) &&
                    (!isOil || intervalHours.isBlank() || (hours != null && hours.isFinite() && hours > 0)) &&
                    (quantity != null || months != null || (isOil && hours != null)) &&
                    (lastDoneDate.isBlank() || doneAt != null) &&
                    ((months == null && (!isOil || hours == null)) || doneAt != null))
                if (basicValid && fuelValid && scheduleValid) {
                    val submittedEntry = entrySnapshot()
                    val onSaved = {
                        if (entrySnapshot() == submittedEntry) {
                            clearEntry()
                            focusManager.clearFocus()
                        }
                    }
                    if (type == "소모품") {
                        vm.addSchedule(label, distance!!, quantity ?: 0.0, months, doneAt, hours.takeIf { isOil }, isOil, onSaved)
                    } else {
                        val recordCategory = if (type == "지출") expenseCategory else if (type == "정비" && isOil) "engine_oil" else null
                        vm.saveRecord(type, label.ifBlank { "주행거리" }, distance, if (type == "주유") quantity!! else 0.0, if (type == "주행거리") 0 else price ?: 0, full, recordCategory,
                            fuelHours.takeIf { type == "주유" }, if (type == "주유" && fuelHours != null) if (incrementalHours) "incremental" else "cumulative" else null,
                            linkedEventId.takeIf { type == "정비" }, repairOutcome.takeIf { type == "정비" && it.isNotBlank() }, enteredDate, editingId, editingBaseline, onSaved)
                    }
                } else entryError = "항목과 주행거리, 선택한 주기 또는 입력값을 확인해 주세요. 날짜는 YYYY-MM-DD 형식입니다."
            }) { Text(if (editingId == null) "기록 저장" else "수정 저장") }
            if (editingId != null) TextButton(onClick = { clearEntry(); vm.clearRecordError() }) { Text("수정 취소") }
            }
            Text("전체 차량 기록", style = MaterialTheme.typography.titleLarge)
            if (records.isEmpty()) {
                HmiEmptyState("차량 기록 없음", "주행거리, 주유, 정비 또는 지출 기록을 추가해 보세요.")
                if (!recordEntryExpanded) {
                    CarlogSecondaryAction(label = "첫 기록 추가", symbol = HmiSymbol.Plus, onClick = { openRecordEntry() })
                }
            }
            records.take(visibleRecordCount).forEach { record ->
                Text("${date(record.timestamp)} · ${record.type} ${record.label} · ${if (record.odometerKnown) "${record.odometer} km" else "주행거리 미입력"} · ${record.cost}원")
                recordDetails(record)?.let { Text(it) }
                record.repairOutcome?.let { Text("정비 결과: $it") }
                if (record.relatedEventId != null) Text("이벤트 연결됨")
                Row {
                    TextButton(onClick = {
                        recordEntryExpanded = true; vm.clearRecordError(); editingId = record.id; editingBaseline = record; type = record.type; label = record.label; km = if (record.odometerKnown) record.odometer.toString() else ""
                        recordDate = ""
                        amount = if (record.type == "주유") record.amount.toString() else ""; cost = record.cost.toString(); full = record.full
                        expenseCategory = record.category?.takeIf { record.type == "지출" } ?: "기타"
                        engineHours = record.engineHours?.toString() ?: ""; incrementalHours = record.engineHoursMode == "incremental"
                        isOil = record.type == "정비" && record.category == "engine_oil"; entryError = null
                        linkedEventId = record.relatedEventId; repairOutcome = record.repairOutcome ?: ""
                    }) { Text("편집") }
                    TextButton(onClick = { vm.clearRecordError(); pendingDelete = record }) { Text("삭제") }
                }
            }
            if (records.size > visibleRecordCount) {
                TextButton(onClick = { visibleRecordCount += minOf(20, records.size - visibleRecordCount) }) {
                    Text("기록 더 보기 (${records.size - visibleRecordCount}개 남음)")
                }
            }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun EventDetail(event: EventEntity, vm: CarlogViewModel, records: List<RecordEntity>, launchReportFile: (String) -> Unit) {
    val loadedSamples by remember(event.id) { vm.samples(event.id) }.collectAsState(initial = null)
    val exchangeMessage by vm.exchangeMessage.collectAsState()
    val exportPreparing by vm.exportPreparing.collectAsState()
    val reportExportBusy by vm.reportExportBusy.collectAsState()
    val reportExportMessage by vm.reportExportMessage.collectAsState()
    val editSaving by vm.eventEditSaving.collectAsState(); val editError by vm.eventEditError.collectAsState()
    var editing by remember(event.id) { mutableStateOf(false) }
    var showRawDetails by rememberSaveable(event.id) { mutableStateOf(false) }
    var reportPreview by remember(event.id) { mutableStateOf<String?>(null) }
    val shareScope = rememberCoroutineScope()
    var shareError by remember(event.id) { mutableStateOf<String?>(null) }
    var sharePreparing by remember(event.id) { mutableStateOf(false) }
    var editCategory by remember(event.id) { mutableStateOf(event.category) }; var editMemo by remember(event.id) { mutableStateOf(event.memo) }
    var editBaseline by remember(event.id) { mutableStateOf(event) }
    val samples = loadedSamples ?: emptyList()
    val context = LocalContext.current
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.finishEventExport(it) }
    val snapshot = eventSnapshot(samples, event.timestamp)
    val seriesSnapshot = eventSeriesSnapshot(samples, event.timestamp)
    val coverage = eventCoverage(samples, event.timestamp)
    val coverageText = if (coverage.earliest == null || coverage.latest == null) "저장된 신호 없음" else
        "발생 전·시점 ${coverage.beforeOrAt}개, 이후 ${coverage.after}개 · 기록 범위 ${date(coverage.earliest)} ~ ${date(coverage.latest)}"
    val location = eventLocation(samples, event.id, event.timestamp)
    val lat = location?.first; val lon = location?.second
    val signals = samples.map(::signalSeriesKey).distinct().sortedWith(compareBy({ it.name }, { it.unit }, { it.timestampKind.orEmpty() }))
    var selectedSignal by remember(event.id) { mutableStateOf<SignalSeriesKey?>(null) }
    val selected = selectedSignal?.takeIf { it in signals } ?: signals.firstOrNull { it.name == "fuel" } ?: signals.firstOrNull()
    val series = samples.filter { signalSeriesKey(it) == selected && it.value.isFinite() }.sortedBy { it.timestamp }
    val plottedSeries = remember(event.id, event.timestamp, series) { graphSamples(series, event.timestamp) }
    val linkedMaintenance = records.filter { it.type == "정비" && it.relatedEventId == event.id }
    reportPreview?.let { report ->
        AlertDialog(
            onDismissRequest = { reportPreview = null },
            title = { Text("정비 참고 리포트") },
            text = { Column {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { Text(report) }
                shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                reportExportMessage?.let { Text(it) }
            } },
            confirmButton = { Row { TextButton(enabled = !sharePreparing && !reportExportBusy, onClick = {
                if (!vm.beginShare()) shareError = "다른 공유나 차량 전환이 끝난 뒤 다시 시도해 주세요."
                else {
                    sharePreparing = true; shareError = null
                    shareScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try {
                            val intent = withContext(Dispatchers.IO) { createShareIntent(context, report, false) }
                            context.startActivity(Intent.createChooser(intent, "리포트 파일 공유"))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { shareError = "리포트 파일을 공유하지 못했습니다." }
                        finally { sharePreparing = false; vm.endShare() }
                    }
                }
            }) { Text("공유") }
                TextButton(enabled = !sharePreparing && !reportExportBusy, onClick = {
                    vm.prepareReportExport(report) { launchReportFile("carlog-report-${event.id}.txt") }
                }) { Text("파일 저장") }
            } },
            dismissButton = { TextButton(onClick = { reportPreview = null }) { Text("닫기") } },
        )
    }
    if (editing) {
        AlertDialog(onDismissRequest = { if (!editSaving) editing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 760.dp),
            title = { Text("이벤트 분류와 메모 수정", style = MaterialTheme.typography.headlineSmall) },
            text = { Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(listOf("차량 이상", "도로 상태"), listOf("특이 주행 상황", "위치 메모")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value ->
                            val label = when (value) { "특이 주행 상황" -> "주행 상황"; "위치 메모" -> "위치·메모"; else -> value }
                            val selectedCategory = editCategory == value
                            Card(onClick = { editCategory = value }, enabled = !editSaving,
                                modifier = Modifier.weight(1f).heightIn(min = 88.dp),
                                colors = CardDefaults.cardColors(containerColor = if (selectedCategory) CarlogColors.PrimarySubtle else Color.White),
                                border = BorderStroke(1.dp, if (selectedCategory) CarlogColors.Primary else CarlogColors.Border)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    EventCategoryIcon(value, Modifier.size(28.dp))
                                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (selectedCategory) CarlogColors.Primary else CarlogColors.Text)
                                }
                            }
                        }
                    }
                }
                FilterChip(selected = editCategory == "일반 이벤트", enabled = !editSaving, onClick = { editCategory = "일반 이벤트" }, label = { Text("일반 이벤트") })
                OutlinedTextField(editMemo, { editMemo = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp), enabled = !editSaving, label = { Text("메모") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CarlogColors.Primary, focusedLabelColor = CarlogColors.Primary, cursorColor = CarlogColors.Primary))
                editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled = !editSaving, onClick = {
                vm.updateEventDetails(editBaseline, editCategory, editMemo) { editing = false }
            }, modifier = Modifier.heightIn(min = 64.dp)) { Text("저장") } },
            dismissButton = { TextButton(enabled = !editSaving, onClick = { editing = false }, modifier = Modifier.heightIn(min = 64.dp)) { Text("취소") } })
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, CarlogColors.Border)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EventCategoryIcon(event.category, Modifier.size(32.dp))
                    Text(event.category, style = MaterialTheme.typography.headlineSmall)
                }
                Text(date(event.timestamp), style = MaterialTheme.typography.titleMedium, color = CarlogColors.SecondaryText)
                Text("${eventStatusLabel(event.status)} · ${eventSourceLabel(event.source)}", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                if (loadedSamples == null) Text("저장된 사전 신호 확인 중")
                else {
                    val earliestPre = samples.asSequence().map { it.timestamp }
                        .filter { it <= event.timestamp && it >= event.timestamp.coerceAtLeast(Long.MIN_VALUE + 60_000) - 60_000 }
                        .minOrNull()
                    Text(earliestPre?.let { "저장된 사전 신호: 가장 오래된 신호 발생 ${(event.timestamp - it) / 1000}초 전 · 연속 수집 보장 안 됨" }
                        ?: "저장된 사전 신호 없음", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                }
                HorizontalDivider(color = CarlogColors.Border)
                Text(event.memo.ifBlank { "메모 없음" }, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(enabled = !editSaving, onClick = { editBaseline = event; editCategory = event.category; editMemo = event.memo; vm.clearEventEditError(); editing = true }, modifier = Modifier.heightIn(min = 52.dp)) { Text("분류·메모 수정") }
            }
        }
        Text("주요 차량 정보", style = MaterialTheme.typography.titleLarge)
        Text("이벤트 발생 시점 또는 이전의 마지막 관측값", style = MaterialTheme.typography.bodyMedium)
        val keySignals = listOf("speed", "vehicle_speed", "odometer", "fuel", "ev_battery_soc", "brake_pedal_pressed")
            .mapNotNull { snapshot[it] }.filter { it.value.isFinite() }
        if (loadedSamples == null) Text("신호 불러오는 중")
        else if (keySignals.isEmpty() && location == null) Text("기록된 주요 차량 정보가 없습니다.")
        keySignals.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { sample ->
                    OutlinedCard(Modifier.weight(1f), colors = CardDefaults.outlinedCardColors(containerColor = Color.White), border = BorderStroke(1.dp, CarlogColors.Border)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(signalDisplayName(sample.name), style = MaterialTheme.typography.titleMedium, color = CarlogColors.SecondaryText)
                            Text("${"%.1f".format(sample.value)} ${sample.unit}", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = CarlogColors.Text)
                            Text("관측 ${date(sample.timestamp)}", style = MaterialTheme.typography.bodySmall, color = CarlogColors.SecondaryText)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (lat != null && lon != null) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("위치 · 발생 전 마지막 관측", style = MaterialTheme.typography.titleMedium)
                    Text("${"%.5f".format(lat.value)}, ${"%.5f".format(lon.value)}", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        if (!openEventMap(context, lat, lon)) android.widget.Toast.makeText(context, "지도 앱을 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("지도 앱에서 위치 열기") }
                }
            }
        }
        OutlinedButton(onClick = { showRawDetails = !showRawDetails }, modifier = Modifier.heightIn(min = 52.dp)) {
            Text(if (showRawDetails) "상세 데이터 접기" else "상세 데이터 보기")
        }
        if (showRawDetails) {
        Text("이벤트 ID: ${event.id}", style = MaterialTheme.typography.bodySmall)
        event.originalVehicleId?.let { Text("원본 차량 ID: $it") }
        Text(if (loadedSamples == null) "신호를 불러오는 중" else coverageText)
        Text("기록 범위는 연속 수집이나 모든 신호의 수집을 보장하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        if (loadedSamples != null) {
            Text("발생 시점 또는 이전의 마지막 관측값", style = MaterialTheme.typography.titleSmall)
            if (seriesSnapshot.isEmpty()) Text("기록 없음")
            seriesSnapshot.sortedWith(compareBy({ it.name }, { it.unit }, { it.timestampKind.orEmpty() })).take(20).forEach { sample ->
                Text("${signalDisplayName(sample.name)} (${sample.name}) · ${sample.value} ${sample.unit} · ${Instant.ofEpochMilli(sample.timestamp)} (UTC) · ${timestampKindLabel(sample.timestampKind)}")
            }
            if (seriesSnapshot.size > 20) Text("관측값 ${seriesSnapshot.size}개 중 20개 표시 · 전체는 리포트에서 확인")
        }
        Text("센서 시계열 · 왼쪽 -60초 / 중앙 발생 / 오른쪽 +60초")
        Text("점은 저장된 관측값이며 점 사이의 변화는 확인되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            signals.forEach { signal ->
                FilterChip(selected = signal == selected, onClick = { selectedSignal = signal }, label = { Text("${signalDisplayName(signal.name)} (${signal.unit}) · ${timestampKindLabel(signal.timestampKind)}") })
            }
        }
        if (loadedSamples != null && series.isEmpty()) Text("기록된 신호가 없습니다.")
        else if (series.isNotEmpty()) {
            val signalCoverage = eventCoverage(series, event.timestamp)
            Text("범위: ${series.minOf { it.value }}–${series.maxOf { it.value }} ${selected?.unit.orEmpty()}")
            Text("선택 신호: 발생 전·시점 ${signalCoverage.beforeOrAt}개 · 이후 ${signalCoverage.after}개 · 관측 ${Instant.ofEpochMilli(requireNotNull(signalCoverage.earliest))} ~ ${Instant.ofEpochMilli(requireNotNull(signalCoverage.latest))} (UTC)")
            Text("선택 신호 관측 시각 · 발생 대비 ${eventOffset(requireNotNull(signalCoverage.earliest), event.timestamp)} ~ ${eventOffset(requireNotNull(signalCoverage.latest), event.timestamp)}")
        }
        if (plottedSeries.size < series.size) Text("그래프는 ${series.size}개 중 ${plottedSeries.size}개 표본을 표시합니다. 각 시간 구간의 처음·마지막·최솟값·최댓값을 보존합니다.", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            if (series.isNotEmpty()) {
                val min = series.minOf { it.value }; val max = series.maxOf { it.value }
                val start = event.timestamp - 60_000L
                val inset = minOf(6.dp.toPx(), size.width / 2, size.height / 2)
                fun x(timestamp: Long) = inset + (size.width - 2 * inset) * ((timestamp - start) / 120_000f).coerceIn(0f, 1f)
                fun y(value: Double) = inset + (size.height - 2 * inset) * (1 - chartFraction(value, min, max).toFloat())
                drawLine(Color.Gray, Offset(x(event.timestamp), 0f), Offset(x(event.timestamp), size.height), 1f)
                plottedSeries.forEach { sample ->
                    drawCircle(Color.Blue, 3.dp.toPx(), Offset(x(sample.timestamp), y(sample.value)))
                }
            }
        }
        if (series.isNotEmpty()) {
            val before = series.filter { it.timestamp <= event.timestamp }.takeLast(5)
            val after = series.filter { it.timestamp > event.timestamp }.take(5)
            Text("발생 시점에 가까운 표본 ${before.size + after.size}/${series.size}개 · 시각 UTC", style = MaterialTheme.typography.titleSmall)
            (before + after).forEach { sample ->
                val delta = sample.timestamp - event.timestamp
                Text("${Instant.ofEpochMilli(sample.timestamp)} · ${if (delta > 0) "+" else ""}${delta}ms · ${sample.value} ${sample.unit} · ${timestampKindLabel(sample.timestampKind)}")
            }
            if (series.size > before.size + after.size) Text("나머지 표본은 수집 종료 후 JSON 내보내기가 가능한 경우 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (lat != null && lon != null) {
            Text("발생 전 마지막 위치: ${lat.value}, ${lon.value} (기록 ${date(lat.timestamp)} · ${timestampKindLabel(lat.timestampKind)})")
            TextButton(onClick = {
                if (!openEventMap(context, lat, lon)) android.widget.Toast.makeText(context, "지도 앱을 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("지도 앱에서 위치 열기") }
        } else if (snapshot["latitude"] != null || snapshot["longitude"] != null) Text("위치 신호가 부족하거나 좌표의 시각·시각 종류·단위·범위를 확인할 수 없어 지도를 열 수 없습니다.")
        }
        if (linkedMaintenance.isNotEmpty()) {
            Text("연결된 정비", style = MaterialTheme.typography.titleSmall)
            linkedMaintenance.forEach { Text("${date(it.timestamp)} · ${it.label} · ${it.repairOutcome ?: "결과 미입력"}") }
        }
        Button(enabled = loadedSamples != null && !sharePreparing, onClick = {
            if (!vm.beginShare()) shareError = "다른 공유나 차량 전환이 끝난 뒤 다시 시도해 주세요."
            else {
                sharePreparing = true; shareError = null
                shareScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try { reportPreview = vm.reportFromStorage(event.id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { shareError = "정비 참고 리포트를 불러오지 못했습니다." }
                    finally { sharePreparing = false; vm.endShare() }
                }
            }
        }) { Text("정비 참고 리포트 보기") }
        exchangeMessage?.let { Text(it) }
        reportExportMessage?.let { Text(it) }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !sharePreparing && !exportPreparing && loadedSamples != null && event.status != "collecting", onClick = {
            vm.eventJsonForExport(event.id) { json ->
                if (!vm.beginShare()) shareError = "다른 공유나 차량 전환이 끝난 뒤 다시 시도해 주세요."
                else {
                    sharePreparing = true; shareError = null
                    shareScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try {
                            val intent = withContext(Dispatchers.IO) { createShareIntent(context, json, true) }
                            context.startActivity(Intent.createChooser(intent, "이벤트 JSON 파일 공유"))
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { shareError = "이벤트 JSON 파일을 공유하지 못했습니다." }
                        finally { sharePreparing = false; vm.endShare() }
                    }
                }
            }
        }) { Text("이벤트 JSON 공유") }
        Button(enabled = !exportPreparing && loadedSamples != null && event.status != "collecting", onClick = {
            vm.prepareEventExport(event.id) { exportFile.launch("carlog-event-${event.id}.json") }
        }) { Text("이벤트 JSON 파일 저장") }
    }
}
