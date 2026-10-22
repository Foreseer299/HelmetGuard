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
