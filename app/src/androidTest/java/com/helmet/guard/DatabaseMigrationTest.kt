package com.helmet.guard

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.data.db.GuardDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationTest {
    @Test fun upgradingIncidentColumnsPreservesExistingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-regression.db"
        context.deleteDatabase(name)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accidents(eventId INTEGER PRIMARY KEY NOT NULL, detail TEXT NOT NULL)")
                        db.execSQL("INSERT INTO accidents VALUES (42, '保留原事故')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        GuardDatabase.MIGRATION_2_3.migrate(db)
                    }
                }
            ).build()
        )
        try {
            open(2).use { it.writableDatabase }
            open(3).use { helper ->
                helper.writableDatabase.query("SELECT * FROM accidents WHERE eventId=42").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("保留原事故", cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    for (field in listOf("hardwareDeviceId", "hardwareEventId", "hardwareAddress")) {
                        assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(field)))
                    }
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
