package com.helmet.guard

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.ui.GuardApp
import com.helmet.guard.ui.theme.HelmetGuardTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiPreviewTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(dark: Boolean, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            HelmetGuardTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GuardApp(context.appGraph, { _ -> }, {}, {}, {}, { _, _ -> })
                }
            }
        }
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        saveUiPreview(context, name, bitmap)
    }

    @Test fun homeLightPreview() { capture(false, "home-light.png") }
    @Test fun homeDarkPreview() { capture(true, "home-dark.png") }
}
