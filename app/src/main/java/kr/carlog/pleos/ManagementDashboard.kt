package kr.carlog.pleos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode

/** Summary values come from the existing records and signal stream; no sample values are generated here. */
@Composable
fun ManagementDashboard(
    vehicle: String, distance: String, fuel: String, updated: String,
    economy: String, monthly: String, fuelCost: String, maintenanceCost: String,
    battery: String? = null, monthlyHistory: List<MonthlySpend> = emptyList()
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("차량 현황", style = MaterialTheme.typography.labelMedium, color = CarlogColors.SecondaryText)
            Text(vehicle.ifBlank { "정보 없음" }, style = MaterialTheme.typography.headlineSmall, color = CarlogColors.Text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val availableWidth = maxWidth
                val signals = listOf("연료" to fuel) + listOfNotNull(battery?.let { "배터리" to it })
                if (availableWidth >= 700.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OdometerMetric(distance, Modifier.weight(1.3f))
                        signals.forEach { (title, value) -> SignalMetric(title, value, Modifier.weight(1f)) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OdometerMetric(distance, Modifier.fillMaxWidth())
                        if (availableWidth < 360.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                signals.forEach { (title, value) -> SignalMetric(title, value, Modifier.fillMaxWidth()) }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                signals.forEach { (title, value) -> SignalMetric(title, value, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            if (updated.isNotBlank() && updated != "기록 없음" && updated != "정보 없음") {
                Text("신호 갱신 · $updated", style = MaterialTheme.typography.bodySmall, color = CarlogColors.SecondaryText)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("연비와 이번 달 지출", style = MaterialTheme.typography.titleLarge, color = CarlogColors.Text)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryStat("평균 연비", economy, Modifier.fillMaxWidth())
                        PrimaryStat("이번 달 지출", monthly, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryStat("평균 연비", economy, Modifier.weight(1f))
                        PrimaryStat("이번 달 지출", monthly, Modifier.weight(1f))
                    }
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = CarlogColors.SecondarySurface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    CompactStat("주유", fuelCost)
                    HorizontalDivider(color = CarlogColors.Border)
                    CompactStat("정비", maintenanceCost)
                }
            }
        }
        MonthlyTrend(monthlyHistory)
    }
}

@Composable
private fun OdometerMetric(distance: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarlogColors.SecondarySurface)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기록 주행거리", style = MaterialTheme.typography.titleSmall, color = CarlogColors.SecondaryText)
            MetricValue(distance, 36)
        }
    }
}

private fun isEmptyMetric(value: String) =
    value.isBlank() || value in setOf("정보 없음", "기록 없음", "기록 부족")

@Composable
private fun MetricValue(value: String, size: Int = 30) {
    if (isEmptyMetric(value)) {
        Text(value.ifBlank { "정보 없음" }, style = MaterialTheme.typography.titleMedium, color = CarlogColors.SecondaryText)
    } else {
        val match = Regex("^(.+?)(\\s*(?:km/L|km|원|%))$").matchEntire(value.trim())
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.Bold)) { append(match?.groupValues?.get(1) ?: value) }
            match?.groupValues?.get(2)?.let { withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)) { append(it) } }
        }, style = CarlogTypography.kpi, color = CarlogColors.Text, lineHeight = (size + 8).sp)
    }
}

@Composable
private fun SignalMetric(title: String, value: String, modifier: Modifier = Modifier) {
    val percent = value.trim().removeSuffix("%").trim().toFloatOrNull()
        ?.takeIf { it.isFinite() && it in 0f..100f }
    Card(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CarlogColors.Border),
        colors = CardDefaults.cardColors(containerColor = CarlogColors.Surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (percent != null) "$title · MOCK" else title, style = MaterialTheme.typography.titleSmall, color = CarlogColors.SecondaryText)
            MetricValue(value)
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (percent <= 20f) CarlogColors.Warning else CarlogColors.Info,
                    trackColor = CarlogColors.Subtle)
            }
        }
    }
}

@Composable
private fun PrimaryStat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CarlogColors.Border),
        colors = CardDefaults.cardColors(containerColor = CarlogColors.Surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = CarlogColors.SecondaryText)
            MetricValue(value)
        }
    }
}

@Composable
private fun CompactStat(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
        Spacer(Modifier.width(16.dp))
        Text(value.ifBlank { "정보 없음" }, style = MaterialTheme.typography.titleSmall,
            color = if (isEmptyMetric(value)) CarlogColors.SecondaryText else CarlogColors.Text)
    }
}

@Composable
private fun MonthlyTrend(history: List<MonthlySpend>) {
    val months = history.takeLast(6)
    if (months.size < 2 || months.map { it.month }.distinct().size < 2 || months.any { it.total.signum() < 0 }) return
    val maximum = months.maxOf { it.total }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CarlogColors.Border),
        colors = CardDefaults.cardColors(containerColor = CarlogColors.Surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("월별 지출 추이", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = CarlogColors.Text)
            months.forEach { item ->
                val fraction = if (maximum.signum() == 0) 0f else BigDecimal(item.total)
                    .divide(BigDecimal(maximum), 4, RoundingMode.HALF_UP).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.month, style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
                        Text("${item.total}원", style = MaterialTheme.typography.bodyMedium, color = CarlogColors.Text)
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(),
                        color = CarlogColors.Info, trackColor = CarlogColors.Subtle)
                }
            }
        }
    }
}
