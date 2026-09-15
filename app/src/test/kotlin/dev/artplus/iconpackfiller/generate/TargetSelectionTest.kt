package dev.artplus.iconpackfiller.generate

import dev.artplus.iconpackfiller.coverage.LaunchableApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetSelectionTest {

    private fun app(pkg: String, activity: String = "$pkg.Main") = LaunchableApp(
        packageName = pkg,
        activityName = activity,
        label = pkg,
        isSystemApp = false,
        category = 1,
    )

    @Test
    fun `keyOf combines package and activity`() {
        assertEquals("com.a/com.a.Main", TargetSelection.keyOf(app("com.a")))
    }

    /**
     * 同一包名多个 launcher 别名时必须能区分，否则勾一个会连带另一个。
     */
    @Test
    fun `keys distinguish aliases within one package`() {
        val first = app("com.a", "com.a.Main")
        val second = app("com.a", "com.a.Alias")
        assertTrue(TargetSelection.keyOf(first) != TargetSelection.keyOf(second))
    }

    @Test
    fun `null selection means all`() {
        val apps = listOf(app("com.a"), app("com.b"))
        assertEquals(apps, TargetSelection.filter(apps, null))
        assertEquals(2, TargetSelection.selectedCount(apps, null))
        assertTrue(TargetSelection.isAllSelected(apps, null))
    }

    @Test
    fun `empty selection filters everything out`() {
        val apps = listOf(app("com.a"), app("com.b"))
        assertTrue(TargetSelection.filter(apps, emptySet()).isEmpty())
        assertEquals(0, TargetSelection.selectedCount(apps, emptySet()))
        assertFalse(TargetSelection.isAllSelected(apps, emptySet()))
    }

    @Test
    fun `partial selection keeps order and only selected`() {
        val apps = listOf(app("com.a"), app("com.b"), app("com.c"))
        val selected = setOf(TargetSelection.keyOf(apps[2]), TargetSelection.keyOf(apps[0]))
        val filtered = TargetSelection.filter(apps, selected)
        assertEquals(listOf("com.a", "com.c"), filtered.map { it.packageName })
        assertEquals(2, TargetSelection.selectedCount(apps, selected))
        assertFalse(TargetSelection.isAllSelected(apps, selected))
    }

    @Test
    fun `unknown keys are ignored`() {
        val apps = listOf(app("com.a"))
        val filtered = TargetSelection.filter(apps, setOf("com.ghost/com.ghost.Main"))
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `allKeys covers every app`() {
        val apps = listOf(app("com.a"), app("com.a", "com.a.Alias"), app("com.b"))
        assertEquals(3, TargetSelection.allKeys(apps).size)
        assertTrue(TargetSelection.isAllSelected(apps, TargetSelection.allKeys(apps)))
    }

    @Test
    fun `query matches label case-insensitively`() {
        val apps = listOf(
            app("app.lawnchair.lawnicons.play").copy(label = "Lawnicons"),
            app("bin.mt.plus").copy(label = "MT管理器"),
        )
        assertEquals(listOf("Lawnicons"), TargetSelection.filterByQuery(apps, "lawn").map { it.label })
        assertEquals(listOf("MT管理器"), TargetSelection.filterByQuery(apps, "mt管理器").map { it.label })
    }

    @Test
    fun `query matches package name`() {
        val apps = listOf(app("bin.mt.plus"), app("com.other.app"))
        assertEquals(listOf("bin.mt.plus"), TargetSelection.filterByQuery(apps, "MT.PLUS").map { it.packageName })
    }

    @Test
    fun `blank query returns all`() {
        val apps = listOf(app("com.a"), app("com.b"))
        assertEquals(apps, TargetSelection.filterByQuery(apps, ""))
        assertEquals(apps, TargetSelection.filterByQuery(apps, "   "))
    }

    @Test
    fun `query with no match returns empty`() {
        val apps = listOf(app("com.a"))
        assertTrue(TargetSelection.filterByQuery(apps, "zzz").isEmpty())
    }

    @Test
    fun `empty target list is trivially all selected`() {
        assertTrue(TargetSelection.isAllSelected(emptyList(), null))
        assertTrue(TargetSelection.isAllSelected(emptyList(), emptySet()))
        assertEquals(0, TargetSelection.selectedCount(emptyList(), null))
    }
}
