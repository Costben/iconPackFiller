package dev.artplus.iconpackfiller.pack

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 回归：真实图标包（如 Aura）只写 `ComponentInfo{pkg/activity}` 精确条目，
 * 不带包级条目。若调用方用 `activityName = null` 重新匹配，会全部漏掉。
 */
class AppFilterMatchTest {

    private fun parse(xml: String): AppFilterDocument =
        AppFilterParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    /** 只有精确条目、没有包级条目的文档（真实包形态）。 */
    private fun componentOnlyDoc(): AppFilterDocument = parse(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <item component="ComponentInfo{app.lawnchair/app.lawnchair.LawnchairLauncher}" drawable="lawnchair" />
            <item component="ComponentInfo{com.coolapk.market/com.coolapk.market.view.main.MainActivity}" drawable="coolapk" />
        </resources>
        """.trimIndent(),
    )

    @Test
    fun `exact component entry matches only with activity name`() {
        val doc = componentOnlyDoc()
        val withActivity = doc.match("app.lawnchair", "app.lawnchair.LawnchairLauncher")
        assertNotNull(withActivity)
        assertEquals("lawnchair", withActivity.drawableName)

        // 这正是先前 reference 加载失败的根因
        assertNull(doc.match("app.lawnchair", null))
    }

    @Test
    fun `activity name normalization matches unqualified and leading dot forms`() {
        val doc = componentOnlyDoc()
        for (activity in listOf(
            "app.lawnchair.LawnchairLauncher",
            ".LawnchairLauncher",
        )) {
            val hit = doc.match("app.lawnchair", activity)
            assertNotNull(hit, "activity=$activity")
            assertEquals("lawnchair", hit.drawableName)
        }
    }

    @Test
    fun `package level entry still acts as fallback`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{bin.mt.plus}" drawable="ic_mt" />
                <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="ic_wechat" />
            </resources>
            """.trimIndent(),
        )
        assertEquals("ic_mt", doc.match("bin.mt.plus", null)?.drawableName)
        assertEquals("ic_mt", doc.match("bin.mt.plus", "any.Activity")?.drawableName)
        assertEquals("ic_wechat", doc.match("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")?.drawableName)
    }

    @Test
    fun `unknown package returns null`() {
        assertNull(componentOnlyDoc().match("com.unknown.app", null))
        assertNull(componentOnlyDoc().match("com.unknown.app", "com.unknown.app.Main"))
    }

    @Test
    fun `exact match wins over package level entry`() {
        val doc = parse(
            """
            <resources>
                <item component="ComponentInfo{com.example.same}" drawable="pkg_level" />
                <item component="ComponentInfo{com.example.same/com.example.same.Special}" drawable="exact" />
            </resources>
            """.trimIndent(),
        )
        assertEquals("exact", doc.match("com.example.same", "com.example.same.Special")?.drawableName)
        assertEquals("pkg_level", doc.match("com.example.same", "com.example.same.Other")?.drawableName)
        assertEquals("pkg_level", doc.match("com.example.same", null)?.drawableName)
    }
}
