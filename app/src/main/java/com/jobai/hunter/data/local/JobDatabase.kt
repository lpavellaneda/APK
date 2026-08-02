package com.jobai.hunter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [JobEntity::class],
    version = 4,   // se quitaron lat/lon/geoPendiente/esLima; se agrego zona y experiencia tipada
    exportSchema = false
)
abstract class JobDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao

    companion object {
        @Volatile
        private var INSTANCE: JobDatabase? = null

        fun getDatabase(context: Context): JobDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JobDatabase::class.java,
                    "job_hunter_db"
                )
                    // Las ofertas se vuelven a scrapear en un minuto; no compensa
                    // escribir Migrations. OJO: borra los estados "postulada".
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
