package dev.artplus.iconpackfiller.project

import dev.artplus.iconpackfiller.coverage.LaunchableApp
import dev.artplus.iconpackfiller.generate.GenerationPlan
import dev.artplus.iconpackfiller.generate.TargetSelection
import dev.artplus.iconpackfiller.pack.PackNaming
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GenerationIconBuilder] 的纯逻辑单测：一次生成的目标与结果如何映射成图标行。
 */
class GenerationIconBuilderTest {

    private fun plan(pkg: String, activity: String, label: String? = null) = GenerationPlan(
        target = LaunchableApp(
            packageName = pkg,
            activityName = activity,
            label = label,
            isSystemApp = false,
        ),
        references = emptyList(),
    )

    @Test
    fun `build writes one icon per plan in order with pack drawable names`() {
        val plans = listOf(
            plan("com.a", "com.a.Main", "A"),
            plan("com.b", "com.b.Main", "B"),
        )
        val acceptedKeys = plans.mapTo(HashSet()) { TargetSelection.keyOf(it.target) }

        val icons = GenerationIconBuilder.build(plans, acceptedKeys, emptyList())

        assertEquals(listOf("com.a", "com.b"), icons.map { it.packageName })
        assertEquals(listOf("com.a.Main", "com.b.Main"), icons.map { it.activityName })
        assertEquals(listOf("A", "B"), icons.map { it.label })
        assertEquals(PackNaming.drawableNameFor(0), icons[0].drawableName)
        assertEquals(PackNaming.drawableNameFor(1), icons[1].drawableName)
        assertTrue(icons.all { it.accepted })
        assertTrue(icons.all { it.reason == null })
    }

    @Test
    fun `build marks rejected targets with the last failure reason`() {
        val plans = listOf(plan("com.a", "com.a.Main", "A"))
        val outcomes = listOf(
            GenerationIconOutcome("com.a", "A", attempt = 1, accepted = false, reason = "首次校验未通过"),
            GenerationIconOutcome("com.a", "A", attempt = 2, accepted = false, reason = "再次校验未通过"),
        )

        val icons = GenerationIconBuilder.build(plans, emptySet(), outcomes)

        val icon = icons.single()
        assertEquals(false, icon.accepted)
        assertEquals("再次校验未通过", icon.reason)
        assertNull(icon.drawableName)
    }

    @Test
    fun `build clears the reason once a later attempt is accepted`() {
        val plans = listOf(plan("com.a", "com.a.Main", "A"))
        val outcomes = listOf(
            GenerationIconOutcome("com.a", "A", attempt = 1, accepted = false, reason = "校验未通过"),
            GenerationIconOutcome("com.a", "A", attempt = 2, accepted = true, reason = null),
        )
        val acceptedKeys = setOf(TargetSelection.keyOf(plans[0].target))

        val icon = GenerationIconBuilder.build(plans, acceptedKeys, outcomes).single()

        assertTrue(icon.accepted)
        assertNull(icon.reason)
    }

    @Test
    fun `build falls back to orchestrator failure reasons when there are no attempts`() {
        val plans = listOf(
            plan("com.a", "com.a.Main", "A"),
            plan("com.b", "com.b.Main", "B"),
        )

        val icons = GenerationIconBuilder.build(
            plans = plans,
            acceptedKeys = emptySet(),
            outcomes = emptyList(),
            failures = mapOf("com.a" to "生成失败（3 次尝试）：没有可加载的参考图"),
        )

        assertEquals("生成失败（3 次尝试）：没有可加载的参考图", icons[0].reason)
        assertNull(icons[1].reason)
        assertTrue(icons.none { it.accepted })
    }

    @Test
    fun `build prefers attempt reason over orchestrator failure reason`() {
        val plans = listOf(plan("com.a", "com.a.Main", "A"))
        val outcomes = listOf(
            GenerationIconOutcome("com.a", "A", attempt = 1, accepted = false, reason = "校验未通过"),
        )

        val icon = GenerationIconBuilder.build(
            plans = plans,
            acceptedKeys = emptySet(),
            outcomes = outcomes,
            failures = mapOf("com.a" to "provider 失败"),
        ).single()

        assertEquals("校验未通过", icon.reason)
    }

    @Test
    fun `fromOutcomes merges attempts per package and keeps the accepted outcome`() {
        val outcomes = listOf(
            GenerationIconOutcome("com.a", "A", attempt = 1, accepted = false, reason = "校验未通过"),
            GenerationIconOutcome("com.a", "A", attempt = 2, accepted = true, reason = null),
            GenerationIconOutcome("com.b", "B", attempt = 1, accepted = false, reason = "网络失败"),
        )

        val icons = GenerationIconBuilder.fromOutcomes(outcomes)

        assertEquals(listOf("com.a", "com.b"), icons.map { it.packageName })
        assertTrue(icons.first { it.packageName == "com.a" }.accepted)
        assertNull(icons.first { it.packageName == "com.a" }.reason)
        assertEquals("网络失败", icons.first { it.packageName == "com.b" }.reason)
        assertTrue(icons.all { it.activityName == null && it.drawableName == null })
    }

    @Test
    fun `fromOutcomes is empty without outcomes`() {
        assertTrue(GenerationIconBuilder.fromOutcomes(emptyList()).isEmpty())
    }
}
