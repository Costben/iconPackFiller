package dev.artplus.iconpackfiller.project.db

import androidx.room.TypeConverter
import dev.artplus.iconpackfiller.project.GenerationStatus
import dev.artplus.iconpackfiller.project.PackEntryKind
import dev.artplus.iconpackfiller.project.PackEntryOrigin
import dev.artplus.iconpackfiller.project.SourceKind

/**
 * 枚举以 name 字符串落库：可读、可手查，新增枚举值不会破坏旧行。
 */
class ProjectConverters {

    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter fun stringToSourceKind(value: String): SourceKind = enumValueOf(value)

    @TypeConverter fun statusToString(value: GenerationStatus): String = value.name

    @TypeConverter fun stringToStatus(value: String): GenerationStatus = enumValueOf(value)

    @TypeConverter fun kindToString(value: PackEntryKind): String = value.name

    @TypeConverter fun stringToKind(value: String): PackEntryKind = enumValueOf(value)

    @TypeConverter fun originToString(value: PackEntryOrigin): String = value.name

    @TypeConverter fun stringToOrigin(value: String): PackEntryOrigin = enumValueOf(value)
}
