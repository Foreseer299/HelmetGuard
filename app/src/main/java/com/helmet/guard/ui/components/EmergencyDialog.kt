package com.helmet.guard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardRed

/** Shown above every destination, so an incident is actionable while viewing data/settings. */
@Composable
fun EmergencyDialog(graph: AppGraph) {
    val emergency by graph.incidents.state.collectAsState()
    if (emergency.stage != EmergencyStage.COUNTDOWN) return
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(88.dp),
                    color = if (emergency.isSimulation) MaterialTheme.colorScheme.primary else GuardRed,
                    strokeWidth = 5.dp
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${emergency.secondsLeft}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("秒后处理", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        title = { Text(if (emergency.isSimulation) "模拟事故演练" else "你现在安全吗？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(emergency.detail)
                Text(
                    if (emergency.isSimulation) "本次只验证流程，不会定位或发送短信。" else "安全时请取消；如需帮助，可立即求救。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { graph.incidents.cancel(emergency.eventId) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GuardGreen)
            ) { Text("我安全，取消报警") }
        },
        dismissButton = {
            TextButton(onClick = { graph.incidents.sendNow(emergency.eventId) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (emergency.isSimulation) "立即完成演练" else "立即求救", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
