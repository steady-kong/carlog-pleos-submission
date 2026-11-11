package kr.carlog.pleos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class HmiSymbol { Record, Microphone, Check, Alert, History, Arrow, Plus, Tools }
enum class HmiTone { Neutral, Progress, Success, Warning, Error }

/** Decorative vector marks: the adjacent label provides the accessible action name. */
@Composable
fun HmiIcon(symbol: HmiSymbol, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(32.dp)) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * .065f, cap = StrokeCap.Round)
        when (symbol) {
            HmiSymbol.Arrow -> {
                drawLine(tint, Offset(w * .16f, h * .5f), Offset(w * .82f, h * .5f), stroke.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * .55f, h * .23f); lineTo(w * .82f, h * .5f); lineTo(w * .55f, h * .77f) }, tint, style = stroke)
            }
            HmiSymbol.Plus -> {
                drawLine(tint, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), stroke.width, StrokeCap.Round)
            }
            HmiSymbol.Tools -> {
                listOf(.25f to .35f, .5f to .7f, .75f to .45f).forEach { (x, y) ->
                    drawLine(tint, Offset(w * x, h * .15f), Offset(w * x, h * .85f), stroke.width, StrokeCap.Round)
                    drawCircle(tint, w * .085f, Offset(w * x, h * y))
                }
            }
            HmiSymbol.Record -> {
                drawRoundRect(tint, Offset(w * .18f, h * .1f), Size(w * .64f, h * .8f), CornerRadius(w * .1f), style = stroke)
                drawLine(tint, Offset(w * .32f, h * .32f), Offset(w * .68f, h * .32f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * .32f, h * .48f), Offset(w * .57f, h * .48f), stroke.width, StrokeCap.Round)
                drawCircle(tint, w * .09f, Offset(w * .5f, h * .7f))
            }
            HmiSymbol.Microphone -> {
                drawRoundRect(tint, Offset(w * .35f, h * .07f), Size(w * .3f, h * .5f), CornerRadius(w * .15f), style = stroke)
                drawArc(tint, 0f, 180f, false, Offset(w * .2f, h * .28f), Size(w * .6f, h * .44f), style = stroke)
                drawLine(tint, Offset(w * .5f, h * .72f), Offset(w * .5f, h * .9f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * .34f, h * .9f), Offset(w * .66f, h * .9f), stroke.width, StrokeCap.Round)
            }
            HmiSymbol.Check -> drawPath(Path().apply {
                moveTo(w * .18f, h * .52f); lineTo(w * .4f, h * .74f); lineTo(w * .84f, h * .25f)
            }, tint, style = stroke)
            HmiSymbol.Alert -> {
                drawPath(Path().apply { moveTo(w * .5f, h * .1f); lineTo(w * .92f, h * .86f); lineTo(w * .08f, h * .86f); close() }, tint, style = stroke)
                drawLine(tint, Offset(w * .5f, h * .36f), Offset(w * .5f, h * .56f), stroke.width, StrokeCap.Round)
                drawCircle(tint, w * .035f, Offset(w * .5f, h * .71f))
            }
            HmiSymbol.History -> {
                drawCircle(tint, w * .37f, style = stroke)
                drawLine(tint, center, Offset(w * .5f, h * .28f), stroke.width, StrokeCap.Round)
                drawLine(tint, center, Offset(w * .68f, h * .6f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun HmiStatus(title: String, detail: String? = null, tone: HmiTone = HmiTone.Neutral, progress: Float? = null) {
    val color = when (tone) {
        HmiTone.Error -> CarlogColors.Error
        HmiTone.Success -> CarlogColors.Success
        HmiTone.Warning -> CarlogColors.Warning
        HmiTone.Progress -> CarlogColors.Info
        HmiTone.Neutral -> CarlogColors.SecondaryText
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).semantics { if (progress == null) liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tone == HmiTone.Progress) CircularProgressIndicator(Modifier.size(22.dp), color = color, strokeWidth = 2.dp)
                else HmiIcon(if (tone == HmiTone.Success) HmiSymbol.Check else if (tone == HmiTone.Error || tone == HmiTone.Warning) HmiSymbol.Alert else HmiSymbol.History, Modifier.size(22.dp), tint = color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = color)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText) }
                }
            }
            progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = color, trackColor = CarlogColors.Border) }
    }
}

@Composable
fun HmiEmptyState(title: String, description: String, modifier: Modifier = Modifier) {
        Row(modifier.fillMaxWidth().heightIn(min = 120.dp).padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            HmiIcon(HmiSymbol.History, Modifier.size(36.dp), CarlogColors.TertiaryText)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = CarlogColors.Text)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = CarlogColors.SecondaryText)
            }
        }
}
