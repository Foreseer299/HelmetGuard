package com.helmet.guard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.helmet.guard.service.HelmetGuardService
import com.helmet.guard.ui.GuardApp
import com.helmet.guard.ui.theme.HelmetGuardTheme

class MainActivity : ComponentActivity() {
    private val pendingPermissionActions = mutableListOf<() -> Unit>()
    private var permissionRequestRunning = false
    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        permissionRequestRunning = false
        if (results.any { !it.value }) {
            appGraph.runtime.postMessage("部分权限未授予；对应的蓝牙、定位或短信能力将不可用")
        }
        val actions = pendingPermissionActions.toList()
        pendingPermissionActions.clear()
        actions.forEach { action -> runCatching(action) }
    }
    private val companionChooser = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        appGraph.runtime.postMessage(
            if (result.resultCode == Activity.RESULT_OK) "系统配套设备关联完成，请继续扫描并连接"
            else "未完成配套设备关联"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HelmetGuardTheme {
                GuardApp(
                    graph = appGraph,
                    requestCorePermissions = ::requestCorePermissions,
                    startMonitoring = { startGuardService(HelmetGuardService.ACTION_START) },
                    stopMonitoring = { startGuardService(HelmetGuardService.ACTION_STOP) },
                    associateCompanion = {
                        appGraph.companionPairing.request(
                            { sender -> companionChooser.launch(IntentSenderRequest.Builder(sender).build()) },
                            { error -> appGraph.runtime.postMessage("配套设备关联失败：$error") }
                        )
                    },
                    runServiceAction = { action, configure ->
                        val intent = Intent(this, HelmetGuardService::class.java).setAction(action).also(configure)
                        runCatching { ContextCompat.startForegroundService(this, intent) }
                            .onFailure { appGraph.runtime.postMessage("操作失败：${it.message ?: "系统禁止启动服务"}") }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (appGraph.preferences.announcementsEnabled.first()) com.helmet.guard.service.AnnouncementService.start(this@MainActivity)
        }
    }

    private fun requestCorePermissions(afterResult: () -> Unit) {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            afterResult()
        } else {
            pendingPermissionActions += afterResult
            if (!permissionRequestRunning) {
                permissionRequestRunning = true
                permissionsLauncher.launch(missing.toTypedArray())
            }
        }
    }

    private fun startGuardService(action: String) {
        val intent = Intent(this, HelmetGuardService::class.java).setAction(action)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { appGraph.runtime.postMessage("守护服务启动失败：${it.message ?: "系统限制"}") }
    }
}
