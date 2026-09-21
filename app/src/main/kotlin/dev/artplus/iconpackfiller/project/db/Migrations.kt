package dev.artplus.iconpackfiller.project.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.artplus.iconpackfiller.project.Tables

/**
 * 迁移脚手架。
 *
 * 规则：
 * - 每次改 schema 必须把 [AppDatabase] 的 `version` +1，并在此登记 `Migration(n, n+1)`；
 * - 禁止 `fallbackToDestructiveMigration`——项目库是用户的本地事实源；
 * - 登记后跑一次 build，Room 会把 schema 导出到 `app/schemas/`，据此写迁移测试。
 *
 * 示例（v1 -> v2 新增列）：
 * ```
 * private val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE ${Tables.GENERATION} ADD COLUMN note TEXT")
 *     }
 * }
 * val ALL = arrayOf<Migration>(MIGRATION_1_2)
 * ```
 */
object Migrations {

    /**
     * v1 -> v2：补齐编排落库所需的两个展示字段。
     *
     * - `generations.diagnosticsJson`：批次级诊断（旧 BatchRecord.diagnostics）。
     * - `attempts.label`：目标应用展示名（旧 AttemptRecord.label）。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ${Tables.GENERATION} ADD COLUMN diagnosticsJson TEXT")
            db.execSQL("ALTER TABLE ${Tables.ATTEMPT} ADD COLUMN label TEXT")
        }
    }

    /** 全部已登记迁移，按版本升序。 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
