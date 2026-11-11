package com.helmet.guard.core.sms

internal enum class SmsDeliveryStatus { DELIVERED, PENDING, FAILED, UNKNOWN }

/** Interprets the status report, not the PendingIntent transport result. */
internal fun classifySmsDelivery(format: String, status: Int): SmsDeliveryStatus = when (format) {
    "3gpp" -> when (status) {
        0 -> SmsDeliveryStatus.DELIVERED // TP-ST: received by the SME.
        in 0x20..0x3f -> SmsDeliveryStatus.PENDING // SMSC is still trying.
        in 0x40..0x7f -> SmsDeliveryStatus.FAILED
        // Forwarded without confirmation / replaced / reserved are not proof of receipt.
        else -> SmsDeliveryStatus.UNKNOWN
    }
    "3gpp2" -> {
        // Android's CDMA SmsMessage exposes (errorClass << 8 | messageStatus) << 16.
        val errorClass = (status ushr 24) and 0xff
        val messageStatus = (status ushr 16) and 0xff
        when {
            errorClass == 0 && messageStatus == 2 -> SmsDeliveryStatus.DELIVERED
            errorClass == 0 && messageStatus == 3 -> SmsDeliveryStatus.FAILED
            errorClass == 2 -> SmsDeliveryStatus.PENDING
            errorClass == 3 -> SmsDeliveryStatus.FAILED
            else -> SmsDeliveryStatus.UNKNOWN
        }
    }
    else -> SmsDeliveryStatus.UNKNOWN
}
