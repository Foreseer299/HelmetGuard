package com.helmet.guard.ui

import java.util.Locale

/** Keep user text (including literal percent signs) outside numeric format strings. */
fun incidentMetrics(confidence: Float, peakG: Float, orientation: Float): String =
    "可信度 ${(confidence * 100).toInt()}% · " +
        String.format(Locale.CHINA, "峰值 %.2fg · 姿态 %.0f°", peakG, orientation)

fun incidentStageText(stage: String): String = when (stage) {
    "SAFE" -> "待命"
    "COUNTDOWN" -> "等待确认"
    "LOCATING" -> "正在定位"
    "SENDING" -> "正在发送"
    "PARTIAL_SUCCESS" -> "部分提交"
    "SENT" -> "已提交运营商"
    "FAILED" -> "求救失败"
    "CANCELLED" -> "已确认安全"
    "SIMULATED" -> "演练完成"
    else -> "未知状态"
}

fun incidentSourceText(source: String): String = when (source) {
    "PHONE_ALGORITHM" -> "手机算法"
    "HELMET_HARDWARE" -> "头盔检测"
    "MANUAL" -> "手动求救"
    else -> "未知来源"
}

fun smsStateText(state: String): String = when (state) {
    "QUEUED" -> "等待系统回执"
    "SUBMITTED" -> "运营商已接受"
    "DELIVERED" -> "已送达"
    "FAILED" -> "失败"
    "TIMED_OUT" -> "回执超时"
    else -> "状态待确认"
}

fun connectionPhaseText(phase: com.helmet.guard.domain.ConnectionPhase): String = when (phase) {
    com.helmet.guard.domain.ConnectionPhase.UNBOUND -> "未绑定"
    com.helmet.guard.domain.ConnectionPhase.BLUETOOTH_OFF -> "蓝牙关闭"
    com.helmet.guard.domain.ConnectionPhase.PERMISSION_REQUIRED -> "需要权限"
    com.helmet.guard.domain.ConnectionPhase.DISCONNECTED -> "未连接"
    com.helmet.guard.domain.ConnectionPhase.SCANNING -> "搜索中"
    com.helmet.guard.domain.ConnectionPhase.CONNECTING -> "连接中"
    com.helmet.guard.domain.ConnectionPhase.NEGOTIATING,
    com.helmet.guard.domain.ConnectionPhase.DISCOVERING,
    com.helmet.guard.domain.ConnectionPhase.SUBSCRIBING -> "准备数据"
    com.helmet.guard.domain.ConnectionPhase.READY -> "数据就绪"
    com.helmet.guard.domain.ConnectionPhase.STALE -> "数据中断"
    com.helmet.guard.domain.ConnectionPhase.ERROR -> "连接异常"
}
