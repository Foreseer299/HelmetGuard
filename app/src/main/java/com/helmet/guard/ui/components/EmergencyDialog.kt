package com.helmet.guard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.ui.theme.GuardGreen

/**
 * Only renders the coordinator's seconds; it owns no timer and cannot affect sending.
 * The first observed remaining interval is saved per event across recreation. A fresh
 * mount (e.g. reopening the app) starts at the remaining interval, not an invented total.
 * The number is authoritative; only the ring eases briefly between actual updates.
 */
@Composable
fun CountdownIndicator(eventId: Long?, secondsLeft: Int, color: Color, modifier: Modifier = Modifier) {
    val initialSeconds = rememberSaveable(eventId) { secondsLeft.coerceAtLeast(1) }
    val target = (secondsLeft.toFloat() / initialSeconds).coerceIn(0f, 1f)
    val progress by key(eventId) { animateFloatAsState(target, tween(180), label = "countdownProgress") }
    val ringSize = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.2f)
    Box(
        modifier.size(ringSize).testTag("countdown_indicator").semantics(mergeDescendants = true) {
            // Expose actual coordinator progress immediately, independently of decoration.
            progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            stateDescription = "剩余 $secondsLeft 秒"
        }, contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress }, modifier = Modifier.size(ringSize).testTag("countdown_ring"), color = color,
            trackColor = color.copy(alpha = 0.12f), strokeWidth = 6.dp
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$secondsLeft", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("秒后处理", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Kept outside NavHost so navigation never postpones emergency actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyDialog(graph: AppGraph) {
    val emergency by graph.incidents.state.collectAsState()
    if (emergency.stage != EmergencyStage.COUNTDOWN) return
    BasicAlertDialog(onDismissRequest = {}, modifier = Modifier.testTag("emergency_dialog")) {
        Surface(
            Modifier.fillMaxWidth().heightIn(max = 560.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                // Measure actions first. Only explanatory content scrolls when space is
                // tight, so enlarged labels cannot lose their height to the ring or title.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CountdownIndicator(emergency.eventId, emergency.secondsLeft,
                            if (emergency.isSimulation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Text(if (emergency.isSimulation) "模拟事故演练" else "你现在安全吗？", style = MaterialTheme.typography.headlineSmall)
                    Text(emergency.detail, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (emergency.isSimulation) "本次只验证流程，不会定位或发送短信。" else "安全时请取消；如需帮助，可立即求救。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { graph.incidents.cancel(emergency.eventId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cancel_emergency"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GuardGreen, contentColor = Color.White)
                    ) { Text("我安全，取消报警") }
                    TextButton(
                        onClick = { graph.incidents.sendNow(emergency.eventId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("send_emergency_now"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(if (emergency.isSimulation) "立即完成演练" else "立即求救", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
