package com.rollapp.shared.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [UploadEntity::class],
    version = 1,
    exportSchema = true
)
abstract class RollDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        const val NAME = "roll.db"
    }
}
