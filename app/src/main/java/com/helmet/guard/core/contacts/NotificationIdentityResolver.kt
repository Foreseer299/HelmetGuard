package com.helmet.guard.core.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.domain.NotificationEvent

/** Local-only lookup. No number, name, notification body or contact cursor is persisted. */
class NotificationIdentityResolver(private val context: Context) {
    fun resolve(event: NotificationEvent, personUri: String? = null, personName: String? = null): NotificationEvent {
        if (event.source !in setOf(AnnouncementSource.SMS, AnnouncementSource.CALL)) return event
        val candidates = listOfNotNull(personName, event.title).map { it.trim() }
        val number = personUri?.takeIf { it.startsWith("tel:") }?.let { Uri.decode(it.removePrefix("tel:")) }
            ?: candidates.firstOrNull { it.matches(Regex("[+\\d ()-]{3,26}")) && it.count(Char::isDigit) >= 3 }
        val contactsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        var contact: String? = null
        if (contactsAllowed) {
            contact = runCatching {
                when {
                    number != null -> lookupPhone(number)
                    personUri?.startsWith("content://com.android.contacts/") == true -> lookupUri(Uri.parse(personUri))
                    else -> candidates.firstNotNullOfOrNull { lookupUniqueName(it) }
                }
            }.getOrNull()
        }
        val label = contact ?: candidates.firstNotNullOfOrNull(::safeLabel)
        return event.copy(senderLabel = label, senderContactMatched = contact != null)
    }

    private fun lookupPhone(number: String): String? = context.contentResolver.query(
        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) safeLabel(cursor.getString(0)) else null }

    private fun lookupUri(uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) safeLabel(cursor.getString(0)) else null }

    private fun lookupUniqueName(name: String): String? {
        val label = safeLabel(name) ?: return null
        // Only the configured system SMS/dialer source reaches this resolver. Ambiguous names
        // do not authorize reading SMS content; exact unique contact names are accepted.
        return context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            arrayOf(label), null
        )?.use { cursor -> if (cursor.count == 1 && cursor.moveToFirst()) safeLabel(cursor.getString(1)) else null }
    }

    private fun safeLabel(raw: String): String? {
        val value = raw.replace(Regex("(正在)?来电|来电显示"), "").trim()
        if (!value.any(Char::isLetter)) return null
        if (value.length !in 1..24 || value.contains(Regex("\\d{3,}|验证码|短信|信息|消息|电话|通知|未接|通话|内容已隐藏|未知|unknown|verification|message", RegexOption.IGNORE_CASE))) return null
        if (value.any { it == '\n' || it == '\r' || it == ':' || it == '：' }) return null
        return value
    }
}
