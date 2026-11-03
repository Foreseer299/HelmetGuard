package com.helmet.guard.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.helmet.guard.R
import com.helmet.guard.appGraph
import com.helmet.guard.core.call.MultiSimCallMonitor
import com.helmet.guard.domain.EmergencyStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** User-enabled communication monitoring; independent of helmet sensor monitoring. */
class AnnouncementService : Service() {
    private val graph by lazy { appGraph }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls by lazy {
        MultiSimCallMonitor(this) { sim, state, elapsed ->
            val config = graph.announcementSettings.state.value
            if (state != android.telephony.TelephonyManager.CALL_STATE_RINGING || (config.enabled && config.calls)) graph.callSessions.onState(sim, state, elapsed)
        }
    }
    override fun onCreate() {
        super.onCreate()
        GuardNotifications.createChannels(this)
        val notification = NotificationCompat.Builder(this, GuardNotifications.CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("HelmetGuard 通讯提醒已开启")
            .setContentText("按设置处理短信、来电和骑手通知").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 2300, Intent(this, com.helmet.guard.MainActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { startForeground(2300, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        catch (error: RuntimeException) {
            graph.diagnostics.log("service", "communication", "START", "FOREGROUND_DENIED")
            stopSelf(); return
        }
        running.value = true
        graph.announcementAudio.start()
        scope.launch {
            graph.incidents.state.map { it.stage }.distinctUntilChanged().collect { stage ->
                val blocked = stage in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING)
                graph.announcements.setSafetyBlocked(blocked)
                if (blocked) graph.ble.abortAudioForIncident()
            }
        }
        scope.launch {
            graph.preferences.announcementsEnabled.collect { if (!it) stopSelf() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.value) calls.start()
        return START_STICKY
    }
    override fun onDestroy() {
        running.value = false
        calls.stop()
        graph.callSessions.clear()
        graph.announcements.clear()
        graph.announcementAudio.stop()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        val running = MutableStateFlow(false)
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AnnouncementService::class.java)); true
        }.getOrElse { context.appGraph.runtime.postMessage("通讯提醒服务未启动，请在应用内重新开启"); false }
        fun stop(context: Context) { context.stopService(Intent(context, AnnouncementService::class.java)) }
    }
}
