package dev.artplus.iconpackfiller.generate

import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.pack.IconStats
import dev.artplus.iconpackfiller.reference.ReferencePair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationPlannerTest {

    private fun app(pkg: String) = LaunchableApp(
        packageName = pkg,
        activityName = "$pkg.Main",
        label = pkg,
        isSystemApp = false,
        category = 1,
    )

    private fun pair(pkg: String, category: Int? = 1, dominant: Int = 0xFF112233.toInt()) = ReferencePair(
        packageName = pkg,
        label = pkg,
        category = category,
        originalStats = IconStats(512, 512, 0f, dominant, false),
        packStats = IconStats(512, 512, 0f, dominant, false),
    )

    private fun targets(vararg packages: String): List<LaunchableApp> = packages.map { app(it) }

    @Test
    fun `plans one entry per unmatched app`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"), pair("com.ref3"))
        val targets = mapOf("com.a" to pair("com.a"), "com.b" to pair("com.b"))
        val plans = GenerationPlanner.plan(targets("com.a", "com.b"), pool, targets)
        assertEquals(2, plans.size)
        assertTrue(plans.all { it.references.isNotEmpty() })
    }

    @Test
    fun `skips apps without target pair`() {
        val pool = listOf(pair("com.ref1"))
        val plans = GenerationPlanner.plan(targets("com.missing"), pool, emptyMap())
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `respects pair count`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"), pair("com.ref3"))
        val targets = mapOf("com.a" to pair("com.a"))
        val plans = GenerationPlanner.plan(
            targets("com.a"),
            pool,
            targets,
            ReferencePairSelectorConfig(pairCount = 2, randomSeed = 1),
        )
        assertEquals(2, plans.single().references.size)
    }

    /**
     * 勾选后只生成选中的应用，但参考池不受影响（画风参考仍需充足）。
     */
    @Test
    fun `plans only explicitly selected targets`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"))
        val all = listOf(app("com.a"), app("com.b"), app("com.c"))
        val selected = setOf(TargetSelection.keyOf(all[1]))
        val filtered = TargetSelection.filter(all, selected)
        val targetPairs = filtered.associate { it.packageName to pair(it.packageName) }

        val plans = GenerationPlanner.plan(filtered, pool, targetPairs)
        assertEquals(1, plans.size)
        assertEquals("com.b", plans.single().target.packageName)
        assertTrue(plans.single().references.isNotEmpty(), "参考对必须仍来自完整池")
    }

    @Test
    fun `empty selection produces no plans`() {
        val plans = GenerationPlanner.plan(emptyList(), listOf(pair("com.ref1")), emptyMap())
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `planFixed uses the same references for every target`() {
        val override = listOf(pair("com.ref1"), pair("com.ref2"))
        val targetPairs = mapOf("com.a" to pair("com.a"), "com.b" to pair("com.b"))
        val plans = GenerationPlanner.planFixed(targets("com.a", "com.b"), targetPairs, override)
        assertEquals(2, plans.size)
        assertEquals(
            listOf("com.ref1", "com.ref2"),
            plans.first().references.map { it.packageName },
        )
        assertTrue(plans.all { it.fixedReferences })
    }

    @Test
    fun `planFixed excludes the target itself`() {
        val override = listOf(pair("com.a"), pair("com.ref1"))
        val plans = GenerationPlanner.planFixed(
            targets("com.a"),
            mapOf("com.a" to pair("com.a")),
            override,
        )
        assertEquals(listOf("com.ref1"), plans.single().references.map { it.packageName })
    }

    @Test
    fun `planFixed keeps override when only self reference`() {
        val override = listOf(pair("com.a"))
        val plans = GenerationPlanner.planFixed(
            targets("com.a"),
            mapOf("com.a" to pair("com.a")),
            override,
        )
        assertEquals(1, plans.single().references.size)
    }

    @Test
    fun `replan keeps fixed references`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"))
        val target = pair("com.a")
        val initial = GenerationPlan(
            target = app("com.a"),
            references = listOf(pool[0]),
            fixedReferences = true,
        )
        val next = GenerationPlanner.replan(initial, target, pool)
        assertEquals(1, next.attempt)
        assertEquals(listOf("com.ref1"), next.references.map { it.packageName })
    }

    @Test
    fun `replan excludes previously used references`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"), pair("com.ref3"))
        val target = pair("com.a")
        val initial = GenerationPlan(
            target = app("com.a"),
            references = listOf(pool[0], pool[1]),
        )
        val next = GenerationPlanner.replan(initial, target, pool, ReferencePairSelectorConfig(randomSeed = 1))
        assertEquals(1, next.attempt)
        assertTrue(next.references.none { it.packageName in setOf("com.ref1", "com.ref2") })
        assertEquals("com.ref3", next.references.single().packageName)
    }

    @Test
    fun `replan falls back to full pool when exhausted`() {
        val pool = listOf(pair("com.ref1"))
        val target = pair("com.a")
        val initial = GenerationPlan(target = app("com.a"), references = listOf(pool[0]))
        val next = GenerationPlanner.replan(initial, target, pool)
        assertEquals(1, next.references.size)
        assertEquals(1, next.attempt)
    }

    @Test
    fun `attempt increments each replan`() {
        val pool = listOf(pair("com.ref1"), pair("com.ref2"))
        val target = pair("com.a")
        var plan = GenerationPlan(target = app("com.a"), references = listOf(pool[0]))
        plan = GenerationPlanner.replan(plan, target, pool)
        plan = GenerationPlanner.replan(plan, target, pool)
        assertEquals(2, plan.attempt)
    }
}