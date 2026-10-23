package com.helmet.guard

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.File
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** Compose idle can precede the native window transition and compositor presentation. */
internal fun captureUiPreview(context: Context, name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    // Test-only wait, longer than our 180/200 ms transitions and the native dialog fade.
    // It does not delay app actions, the coordinator clock, or change screenshot pixels.
    SystemClock.sleep(400)
    instrumentation.waitForIdleSync()
    saveUiPreview(context, name, requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
}

/** Public test images survive Gradle's automatic uninstall so CI can collect them. */
internal fun saveUiPreview(context: Context, name: String, bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HelmetGuard")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } else {
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
