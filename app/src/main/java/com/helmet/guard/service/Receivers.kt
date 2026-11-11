package com.helmet.guard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.helmet.guard.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (context.appGraph.preferences.monitoringEnabled.first()) {
                    val service = Intent(context, HelmetGuardService::class.java).setAction(HelmetGuardService.ACTION_START)
                    runCatching { ContextCompat.startForegroundService(context, service) }
                }
                context.appGraph.incidents.restorePending()
            } finally { pending.finish() }
        }
    }
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HelmetGuardService.ACTION_SAFE && intent.action != HelmetGuardService.ACTION_SEND_NOW) return
        val service = Intent(context, HelmetGuardService::class.java).apply {
            action = intent.action
            putExtra(HelmetGuardService.EXTRA_EVENT_ID, intent.getLongExtra(HelmetGuardService.EXTRA_EVENT_ID, -1L))
        }
        runCatching { ContextCompat.startForegroundService(context, service) }
    }
}
