package com.helmet.guard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EmergencyContactEntity::class,
        TelemetrySessionEntity::class,
        TelemetrySampleEntity::class,
        AccidentEntity::class,
        SmsPartEntity::class,
        SystemLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun telemetry(): TelemetryDao
    abstract fun accidents(): AccidentDao
    abstract fun smsParts(): SmsPartDao
    abstract fun logs(): LogDao

    companion object {
        @Volatile private var instance: GuardDatabase? = null

        fun create(context: Context): GuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GuardDatabase::class.java,
                "helmet_guard_v2.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accidents ADD COLUMN hardwareDeviceId INTEGER")
                db.execSQL("ALTER TABLE accidents ADD COLUMN hardwareEventId INTEGER")
                db.execSQL("ALTER TABLE accidents ADD COLUMN hardwareAddress TEXT")
            }
        }

        /** Adds the battery-valid flag without discarding contacts, recordings or incidents. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telemetry_samples ADD COLUMN batteryValid INTEGER")
            }
        }
    }
}
