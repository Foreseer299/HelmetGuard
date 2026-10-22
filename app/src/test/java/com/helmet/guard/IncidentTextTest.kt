package com.helmet.guard

import com.helmet.guard.ui.incidentMetrics
import com.helmet.guard.ui.incidentStageText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class IncidentTextTest {
    @Test fun `incident card keeps literal percent signs and engineering units`() {
        assertEquals("可信度 94% · 峰值 6.42g · 姿态 38°", incidentMetrics(0.94f, 6.42f, 38f))
        assertEquals("可信度 0% · 峰值 0.00g · 姿态 0°", incidentMetrics(0f, 0f, 0f))
        assertEquals("可信度 100% · 峰值 16.00g · 姿态 180°", incidentMetrics(1f, 16f, 180f))
    }

    @Test fun `numeric formatting is stable on phones with another locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("可信度 94% · 峰值 6.42g · 姿态 38°", incidentMetrics(0.94f, 6.42f, 38f))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `accepted sms is not presented as delivered`() {
        assertEquals("已提交运营商", incidentStageText("SENT"))
        assertEquals("部分提交", incidentStageText("PARTIAL_SUCCESS"))
        assertEquals("未知状态", incidentStageText("future-stage"))
    }
}
