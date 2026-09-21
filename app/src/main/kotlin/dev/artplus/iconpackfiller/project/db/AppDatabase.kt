package dev.artplus.iconpackfiller.project.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 项目 / 生成历史的本地事实源。
 *
 * 只存元数据与相对路径，图与 APK 在 `filesDir/projects/` 下（见 ProjectPaths）。
 * 旧 `filesDir/batches/` 数据不迁移，新格式从零开始。
 */
@Database(
    entities = [
        ProjectEntity::class,
        PackEntryEntity::class,
        GenerationEntity::class,
        GenerationIconEntity::class,
        AttemptEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(ProjectConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        const val VERSION = 2
        const val NAME = "iconpack-projects.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
