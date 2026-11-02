package kr.carlog.pleos

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarlogViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = Room.databaseBuilder(app, CarlogDatabase::class.java, "carlog.db").build().dao()
    private val source: VehicleDataSource = MockVehicleDataSource()
    private val buffer = RingBufferManager()
    private val vehicle = MutableStateFlow<VehicleEntity?>(null)
    val records = vehicle.filterNotNull().flatMapLatest { dao.records(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val schedules = vehicle.filterNotNull().flatMapLatest { dao.schedules(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val events = vehicle.filterNotNull().flatMapLatest { dao.events(it.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _latest = MutableStateFlow<Map<String, Signal>>(emptyMap())
    val latest = _latest.asStateFlow()
    private val active = mutableMapOf<String, Long>()
    init {
        viewModelScope.launch {
            dao.interruptPending()
            vehicle.value = dao.vehicle() ?: VehicleEntity(name = "내 차량").also { dao.insertVehicle(it) }
            source.signals().collect { signal ->
                buffer.add(signal)
                _latest.value = _latest.value + (signal.name to signal)
                active.toMap().forEach { (id, end) ->
                    if (signal.timestamp <= end) dao.insertSamples(listOf(signal.entity(id)))
                }
            }
        }
    }
    fun samples(id: String) = dao.samples(id)
    fun mark(category: String, memo: String) {
        val car = vehicle.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val event = EventEntity(vehicleId = car.id, timestamp = now, category = category, memo = memo, source = source.source, status = "collecting")
            val before = buffer.snapshot(now)
            try {
                dao.startEvent(event, before.map { it.entity(event.id) })
                active[event.id] = now + 60_000
                // Recover samples received while the Room transaction was suspended.
                dao.insertSamples(buffer.snapshot(System.currentTimeMillis()).filter { it.timestamp > now && it.timestamp <= now + 60_000 }.map { it.entity(event.id) })
                delay((now + 60_000 - System.currentTimeMillis()).coerceAtLeast(0))
                active.remove(event.id)
                dao.setStatus(event.id, "complete")
            } catch (e: Exception) { active.remove(event.id); throw e }
        }
    }
    fun addRecord(type: String, label: String, km: Double, amount: Double, cost: Long, full: Boolean) {
        val car = vehicle.value ?: return
        viewModelScope.launch { dao.insertRecord(RecordEntity(vehicleId = car.id, type = type, timestamp = System.currentTimeMillis(), odometer = km, amount = amount, cost = cost, label = label, full = full)) }
    }
    fun addSchedule(label: String, km: Double, interval: Double) {
        val car = vehicle.value ?: return
        viewModelScope.launch { dao.insertSchedule(ScheduleEntity(vehicleId = car.id, label = label, lastKm = km, intervalKm = interval)) }
    }
}
private fun Signal.entity(id: String) = SampleEntity(id, timestamp, name, value, unit)
private fun date(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { Screen() } } } }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Screen(vm: CarlogViewModel = viewModel()) {
    val records by vm.records.collectAsState(); val schedules by vm.schedules.collectAsState(); val events by vm.events.collectAsState(); val latest by vm.latest.collectAsState()
    var category by remember { mutableStateOf("차량 이상") }; var memo by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<EventEntity?>(null) }
    var type by remember { mutableStateOf("주유") }; var label by remember { mutableStateOf("") }; var km by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var full by remember { mutableStateOf(true) }
    val currentKm = maxOf(latest["odometer"]?.value ?: 0.0, records.maxOfOrNull { it.odometer } ?: 0.0)
    val stats = fuelStats(records.filter { it.type == "주유" }.map { Fuel(it.timestamp, it.odometer, it.amount, it.full) })
    Scaffold(topBar = { TopAppBar(title = { Text("Carlog · 차량 관리") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("시뮬레이션 데이터 · MOCK", color = Color(0xFF9C3200), style = MaterialTheme.typography.titleMedium)
            Text("내 차량 · 누적 ${"%.1f".format(currentKm)} km")
            Text("신뢰 평균 연비: ${stats.average?.let { "%.1f km/L".format(it) } ?: "기록 부족"} · 총 지출 ${records.sumOf { it.cost }}원")
            Text("최근 기록: ${records.firstOrNull()?.let { "${it.type} ${it.label} · ${date(it.timestamp)}" } ?: "없음"}")
            Text("교체 예정", style = MaterialTheme.typography.titleMedium)
            schedules.forEach { Text("${it.label}: ${(it.lastKm + it.intervalKm - currentKm).toInt()} km · ${distanceDue(it.lastKm + it.intervalKm - currentKm)}") }
            HorizontalDivider(); Text("Carlog Events", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("차량 이상", "도로 상태", "특이 주행 상황").forEach { FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("위치 메모", "일반 이벤트").forEach { FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) }) } }
            OutlinedTextField(memo, { memo = it }, label = { Text("이벤트 메모") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.mark(category, memo); memo = "" }) { Text("이 순간 기록 · 전후 60초") }
            Text("앱이 실행되는 동안 수집합니다. 시작 직후에는 이전 기록이 60초보다 짧을 수 있습니다.")
            events.forEach { event -> TextButton(onClick = { selected = event }) { Text("${date(event.timestamp)} · ${event.category} · ${event.status}") } }
            selected?.let { EventDetail(it, vm) }
            HorizontalDivider(); Text("Drive / Wallet / Care", style = MaterialTheme.typography.titleLarge)
            Row { listOf("주유", "지출", "정비", "소모품").forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it) }) } }
            OutlinedTextField(label, { label = it }, label = { Text("항목 / 메모") })
            OutlinedTextField(km, { km = it }, label = { Text("주행거리 km") })
            OutlinedTextField(amount, { amount = it }, label = { Text(if (type == "소모품") "교체 주기 km" else "주유량 L") })
            OutlinedTextField(cost, { cost = it }, label = { Text("금액 원") })
            if (type == "주유") Row { Checkbox(full, { full = it }); Text("가득 주유") }
            Button(onClick = {
                val distance = km.toDoubleOrNull(); val quantity = amount.toDoubleOrNull(); val price = cost.toLongOrNull()
                if (label.isNotBlank() && distance != null && distance.isFinite() && distance >= 0 && (quantity == null || quantity.isFinite()) && (type != "소모품" || (quantity != null && quantity > 0)) && (type != "주유" || (quantity != null && quantity > 0)) && (cost.isBlank() || (price != null && price >= 0))) {
                    if (type == "소모품") vm.addSchedule(label, distance, quantity!!) else vm.addRecord(type, label, distance, quantity ?: 0.0, price ?: 0, full)
                    label = ""; km = ""; amount = ""; cost = ""
                }
            }) { Text("기록 저장") }
            records.take(20).forEach { Text("${date(it.timestamp)} · ${it.type} ${it.label} · ${it.odometer} km · ${it.cost}원") }
        }
    }
}
@Composable fun EventDetail(event: EventEntity, vm: CarlogViewModel) {
    val samples by remember(event.id) { vm.samples(event.id) }.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val lat = samples.lastOrNull { it.name == "latitude" }; val lon = samples.lastOrNull { it.name == "longitude" }
    val fuel = samples.filter { it.name == "fuel" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${event.category} · ${event.source} · ${event.status}", style = MaterialTheme.typography.titleMedium)
        Text("발생 ${date(event.timestamp)} · ${event.memo.ifBlank { "메모 없음" }} · 신호 ${samples.size}개")
        Text("연료량 시계열 (%)")
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            if (fuel.size > 1) {
                val min = fuel.minOf { it.value }; val range = (fuel.maxOf { it.value } - min).coerceAtLeast(.01)
                fuel.zipWithNext().forEachIndexed { index, (a, b) ->
                    drawLine(Color.Blue, Offset(size.width * index / (fuel.size - 1), size.height * (1 - ((a.value - min) / range).toFloat())), Offset(size.width * (index + 1) / (fuel.size - 1), size.height * (1 - ((b.value - min) / range).toFloat())), 2f)
                }
            }
        }
        if (lat != null && lon != null) {
            Text("위치: ${lat.value}, ${lon.value} (시뮬레이션)")
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${lat.value},${lon.value}?q=${lat.value},${lon.value}"))) }) { Text("지도 앱에서 위치 열기") }
        }
        val snapshot = samples.filter { it.timestamp <= event.timestamp }.groupBy { it.name }.mapValues { it.value.last() }
        val report = "Carlog 정비 참고 리포트\n${date(event.timestamp)} · ${event.category}\n출처: ${event.source} · 수집: ${event.status}\n메모: ${event.memo}\n발생 시점: ${snapshot.values.joinToString { "${it.name} ${it.value} ${it.unit} (${date(it.timestamp)})" }}\n수집 신호 ${samples.size}개. 진단 결과가 아닌 운전자 참고 기록입니다."
        Button(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "리포트 공유")) }) { Text("정비 참고 리포트 공유") }
    }
}
