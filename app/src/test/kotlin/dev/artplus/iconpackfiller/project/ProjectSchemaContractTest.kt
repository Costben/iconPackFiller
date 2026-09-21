package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.project.db.AttemptEntity
import dev.artplus.iconpackfiller.project.db.GenerationEntity
import dev.artplus.iconpackfiller.project.db.GenerationIconEntity
import dev.artplus.iconpackfiller.project.db.PackEntryEntity
import dev.artplus.iconpackfiller.project.db.ProjectEntity
import java.lang.reflect.Modifier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 锁定 Requirements 里写死的五张关系表与列名。
 *
 * Room 用属性名当列名，这里用反射核对，防止后续重构静默改列。
 */
class ProjectSchemaContractTest {

    private fun columns(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

    @Test
    fun `table names match the requirements`() {
        assertEquals("projects", Tables.PROJECT)
        assertEquals("pack_entries", Tables.PACK_ENTRY)
        assertEquals("generations", Tables.GENERATION)
        assertEquals("generation_icons", Tables.GENERATION_ICON)
        assertEquals("attempts", Tables.ATTEMPT)
    }

    @Test
    fun `project columns`() {
        assertTrue(
            columns(ProjectEntity::class.java).containsAll(
                listOf(
                    "id", "packLabel", "packPackage", "packVersionCode", "sourceKind",
                    "packHash", "sourceApkFile", "createdAt", "updatedAt", "activeGenerationId",
                ),
            ),
        )
    }

    @Test
    fun `pack entry columns`() {
        assertTrue(
            columns(PackEntryEntity::class.java).containsAll(
                listOf(
                    "id", "projectId", "componentRaw", "packageName", "activityName",
                    "drawableName", "resPath", "kind", "density", "inPack", "origin", "generationId",
                ),
            ),
        )
    }

    @Test
    fun `generation columns`() {
        assertTrue(
            columns(GenerationEntity::class.java).containsAll(
                listOf(
                    "id", "projectId", "createdAt", "status", "model", "slotId", "slotName",
                    "plannedCount", "generatedCount", "failedCount", "outputPackageName",
                    "outputApkFile", "outputApkName", "paramsJson", "diagnosticsJson",
                ),
            ),
        )
    }

    @Test
    fun `generation icon columns`() {
        assertTrue(
            columns(GenerationIconEntity::class.java).containsAll(
                listOf(
                    "id", "generationId", "packageName", "activityName", "label",
                    "drawableName", "accepted", "reason",
                ),
            ),
        )
    }

    @Test
    fun `attempt columns`() {
        assertTrue(
            columns(AttemptEntity::class.java).containsAll(
                listOf(
                    "id", "generationId", "packageName", "attempt", "accepted", "reason",
                    "pngFile", "sourceFile", "model", "slotId", "slotName", "prompt",
                    "referencesJson", "referenceDetailsJson", "label",
                ),
            ),
        )
    }
}
