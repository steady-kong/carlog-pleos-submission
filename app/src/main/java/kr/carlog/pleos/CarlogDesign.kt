package kr.carlog.pleos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Graphite Blue Automotive: keep brand color on actions and selected controls. */
object CarlogColors {
    val Background = Color(0xFFF1F3F6)
    val Surface = Color.White
    val SecondarySurface = Color(0xFFF7F9FB)
    val Subtle = Color(0xFFE8EBF0)
    val Border = Color(0xFFE0E4EA)
    val Divider = Color(0xFFE8EBF0)
    val InverseText = Color.White
    val Primary = Color(0xFF243248)
    val PrimaryHover = Color(0xFF31445E)
    val PrimaryPressed = Color(0xFF192638)
    val PrimarySubtle = Color(0xFFE9EEF9)
    val Text = Color(0xFF192536)
    val SecondaryText = Color(0xFF526071)
    val TertiaryText = Color(0xFF748091)
    val Disabled = Color(0xFF9DA6B2)
    val Success = Color(0xFF18855A)
    val Warning = Color(0xFFBC7A21)
    val Error = Color(0xFFC7474D)
    val Info = Color(0xFF5573BA)
}

object CarlogSpace {
    val Small = 8.dp
    val Gap = 16.dp
    val Inset = 24.dp
    val Section = 32.dp
    val Touch = 64.dp
    val CardRadius = 16.dp
    val HeroRadius = 20.dp
}

object CarlogTypography {
    val kpi = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", letterSpacing = (-.5).sp)
    val styles = Typography(
        headlineLarge = kpi,
        headlineMedium = kpi.copy(fontSize = 32.sp, lineHeight = 40.sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 27.sp),
        bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun CarlogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = CarlogColors.Primary, onPrimary = Color.White,
            primaryContainer = CarlogColors.PrimarySubtle, onPrimaryContainer = CarlogColors.Primary,
            secondary = CarlogColors.SecondaryText, onSecondary = Color.White,
            secondaryContainer = CarlogColors.Subtle, onSecondaryContainer = CarlogColors.Text,
            background = CarlogColors.Background, onBackground = CarlogColors.Text,
            surface = CarlogColors.Surface, onSurface = CarlogColors.Text, surfaceTint = CarlogColors.Surface,
            surfaceVariant = CarlogColors.Subtle, onSurfaceVariant = CarlogColors.SecondaryText,
            surfaceContainerLowest = CarlogColors.Surface, surfaceContainerLow = CarlogColors.SecondarySurface,
            surfaceContainer = CarlogColors.Subtle, surfaceContainerHigh = CarlogColors.Subtle,
            surfaceContainerHighest = CarlogColors.Subtle,
            outline = CarlogColors.Border, outlineVariant = CarlogColors.Border,
            error = CarlogColors.Error, onError = Color.White,
            errorContainer = Color(0xFFFBECEE), onErrorContainer = CarlogColors.Error,
        ),
        typography = CarlogTypography.styles,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content,
    )
}

/** The entire graphite surface is one action and one accessibility target. */
@Composable
fun CarlogHeroAction(enabled: Boolean, requesting: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    Surface(onClick = onClick, enabled = enabled, interactionSource = interactions,
        modifier = Modifier.fillMaxWidth().heightIn(min = 208.dp).semantics { role = Role.Button },
        shape = RoundedCornerShape(CarlogSpace.HeroRadius),
        color = when { pressed -> CarlogColors.PrimaryPressed; enabled || requesting -> CarlogColors.Primary; else -> CarlogColors.Subtle },
        contentColor = if (enabled || requesting) Color.White else CarlogColors.SecondaryText,
        border = if (focused) BorderStroke(3.dp, CarlogColors.Info) else null) {
        Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (requesting) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp, color = Color.White)
            else HmiIcon(HmiSymbol.Record, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (requesting) "기록 시작 중" else "지금 이벤트 기록", style = MaterialTheme.typography.headlineSmall)
                Text(if (enabled) "이 순간의 차량 신호를 남깁니다" else if (requesting) "기록 요청을 처리하고 있습니다" else "수집 상태를 확인하세요", style = MaterialTheme.typography.bodyMedium)
            }
            HmiIcon(HmiSymbol.Arrow, Modifier.size(28.dp))
        }
    }
}

@Composable
fun CarlogNavigation(managementSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Carlog", style = MaterialTheme.typography.headlineSmall, color = CarlogColors.Primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "이벤트", true to "차량 관리").forEach { (management, label) ->
                val selected = management == managementSelected
                Surface(onClick = { onSelect(management) }, modifier = Modifier.heightIn(min = 64.dp).semantics { role = Role.Tab; this.selected = selected },
                    shape = RoundedCornerShape(12.dp), color = if (selected) CarlogColors.PrimarySubtle else CarlogColors.Background,
                    contentColor = if (selected) CarlogColors.Primary else CarlogColors.SecondaryText) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun CarlogSecondaryAction(label: String, symbol: HmiSymbol, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = CarlogSpace.Touch),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CarlogColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CarlogColors.Surface, contentColor = CarlogColors.Text),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        HmiIcon(symbol, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun CarlogUtilityPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = CarlogColors.Subtle, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CarlogColors.Border)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
