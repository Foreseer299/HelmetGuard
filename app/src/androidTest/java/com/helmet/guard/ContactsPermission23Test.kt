package com.helmet.guard

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.contacts.NotificationIdentityResolver
import com.helmet.guard.core.notification.*
import com.helmet.guard.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Run separately after revoking READ_CONTACTS; Android kills the app on revocation. */
class ContactsPermission23Test {
    @Test fun deniedContactsNeverAuthorizesSmsBody() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS))
        val event = NotificationEvent("permission-test", "com.helmet.guard.fixture", "permission-test", null,
            "测试小明", "这段正文不应朗读", "msg", 3, 1L, 1L, false, false, AnnouncementSource.SMS)
        val resolved = NotificationIdentityResolver(context).resolve(event, "tel:+12025550148", "测试小明")
        assertFalse(resolved.senderContactMatched)
        val speech = AnnouncementPolicy.evaluate(resolved, AnnouncementProfile(smsSpeechMode = CommunicationSpeechMode.CONTACT_BODY)).announcement!!.speechText
        assertEquals("测试小明发来短信", speech)
        assertFalse(speech.contains("这段正文"))
    }
}
