package com.helmet.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.helmet.guard.MainActivity
import com.helmet.guard.R
import com.helmet.guard.domain.ConnectionState
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.EmergencyUiState

object GuardNotifications {
    const val CHANNEL_MONITORING = "monitoring_v2"
    const val CHANNEL_EMERGENCY = "emergency_v2"
    const val MONITORING_ID = 1001
    const val EMERGENCY_ID = 911

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val monitoring = NotificationChannel(
            CHANNEL_MONITORING,
            context.getString(R.string.channel_monitoring),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.channel_monitoring_desc); setShowBadge(false) }
        val emergency = NotificationChannel(
            CHANNEL_EMERGENCY,
            context.getString(R.string.channel_emergency),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_emergency_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 900)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            setSound(alarmSound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        manager.createNotificationChannels(listOf(monitoring, emergency))
    }

    fun monitoring(context: Context, connection: ConnectionState, recording: Boolean): Notification {
        val text = buildString {
            append(connection.message)
            if (recording) append(" · 正在记录实验数据")
        }
        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HelmetGuard 正在守护")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun emergency(context: Context, state: EmergencyUiState): Notification {
        val title = when (state.stage) {
            EmergencyStage.COUNTDOWN -> "检测到疑似事故 · ${state.secondsLeft} 秒后求救"
            EmergencyStage.LOCATING -> "正在获取事故位置"
            EmergencyStage.SENDING -> "正在向紧急联系人发送短信"
            EmergencyStage.SENT -> "求救短信已提交"
            EmergencyStage.PARTIAL_SUCCESS -> "部分求救短信已提交"
            EmergencyStage.FAILED -> "自动求救失败，请立即手动呼救"
            EmergencyStage.SIMULATED -> "模拟事故演练已完成"
            EmergencyStage.CANCELLED -> "事故警报已取消"
            EmergencyStage.SAFE -> "安全监测运行中"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_EMERGENCY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(state.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.detail))
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.stage in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING))
            .setAutoCancel(state.stage !in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING))
        if (state.canCancel) {
            builder.addAction(0, context.getString(R.string.action_cancel_alarm), action(context, HelmetGuardService.ACTION_SAFE, state.eventId))
            builder.addAction(0, context.getString(R.string.action_send_now), action(context, HelmetGuardService.ACTION_SEND_NOW, state.eventId))
        }
        return builder.build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1001,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(context: Context, action: String, eventId: Long?): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            this.action = action
            putExtra(HelmetGuardService.EXTRA_EVENT_ID, eventId ?: -1L)
        }
        return PendingIntent.getBroadcast(
            context,
            (eventId ?: 0L).toInt() xor action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
