package dev.artplus.iconpackfiller.coverage

import dev.artplus.iconpackfiller.pack.AppFilterParser
import dev.artplus.iconpackfiller.pack.MatchKind
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageCalculatorTest {

    private val appFilterXml = """
        <resources>
            <item component="ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}" drawable="ic_wechat" />
            <item component="ComponentInfo{bin.mt.plus}" drawable="ic_mt" />
            <item component="ComponentInfo{com.absent.app/com.absent.app.Main}" drawable="ic_ghost" />
        </resources>
    """.trimIndent()

    private fun filter() =
        AppFilterParser.parse(ByteArrayInputStream(appFilterXml.toByteArray()))

    private fun app(
        pkg: String,
        activity: String = "$pkg.Main",
        system: Boolean = false,
        hasIcon: Boolean = true,
        aliasCount: Int = 1,
        defaultAlias: Boolean = true,
    ) = LaunchableApp(
        packageName = pkg,
        activityName = activity,
        label = pkg,
        isSystemApp = system,
        aliasCount = aliasCount,
        isDefaultAlias = defaultAlias,
        hasIcon = hasIcon,
    )

    @Test
    fun `splits matched unmatched excluded`() {
        val apps = listOf(
            app("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
            app("bin.mt.plus"),
            app("com.new.app"),
            app("com.sys.app", system = true),
        )
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = setOf("ic_wechat", "ic_mt", "ic_ghost"),
        )
        assertEquals(2, report.matched.size)
        assertEquals(1, report.unmatched.size)
        assertEquals("com.new.app", report.unmatched[0].packageName)
        assertEquals(1, report.excluded.size)
        assertEquals(ExcludeReason.SYSTEM_APP, report.excluded[0].reason)
        assertEquals(4, report.total)
    }

    @Test
    fun `exact match takes precedence over package level`() {
        val apps = listOf(app("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        val report = CoverageCalculator.compute(apps, filter(), setOf("ic_wechat"))
        assertEquals(MatchKind.EXACT, report.matched[0].matchKind)
        assertEquals("ic_wechat", report.matched[0].drawableName)
    }

    @Test
    fun `package level fallback works`() {
        val apps = listOf(app("bin.mt.plus", "bin.mt.plus.MainLightIcon"))
        val report = CoverageCalculator.compute(apps, filter(), setOf("ic_mt"))
        assertEquals(MatchKind.PACKAGE_LEVEL, report.matched[0].matchKind)
    }

    @Test
    fun `missing drawable excluded`() {
        val apps = listOf(app("com.absent.app", "com.absent.app.Main"))
        val report = CoverageCalculator.compute(apps, filter(), setOf("ic_wechat", "ic_mt"))
        assertEquals(0, report.matched.size)
        assertEquals(1, report.excluded.size)
        assertEquals(ExcludeReason.MISSING_DRAWABLE, report.excluded[0].reason)
    }

    @Test
    fun `missing drawable not excluded when rule disabled`() {
        val apps = listOf(app("com.absent.app", "com.absent.app.Main"))
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = emptySet(),
            rules = CoverageRules.DEFAULT.copy(excludeMissingDrawable = false),
        )
        assertEquals(1, report.matched.size)
    }

    @Test
    fun `blacklist excludes`() {
        val apps = listOf(app("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = setOf("ic_wechat"),
            rules = CoverageRules.DEFAULT.copy(blacklist = setOf("COM.TENCENT.MM")),
        )
        assertEquals(1, report.excluded.size)
        assertEquals(ExcludeReason.USER_BLACKLIST, report.excluded[0].reason)
    }

    @Test
    fun `disabled excluded`() {
        val apps = listOf(app("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = setOf("ic_wechat"),
            disabledPackages = setOf("com.tencent.mm"),
        )
        assertEquals(ExcludeReason.DISABLED, report.excluded[0].reason)
    }

    @Test
    fun `no icon excluded`() {
        val apps = listOf(app("com.new.app", hasIcon = false))
        val report = CoverageCalculator.compute(apps, filter(), emptySet())
        assertEquals(ExcludeReason.NO_ICON, report.excluded[0].reason)
    }

    @Test
    fun `aliases collapse to default`() {
        val apps = listOf(
            app("org.telegram.messenger", "org.telegram.messenger.DefaultIcon", aliasCount = 3, defaultAlias = true),
            app("org.telegram.messenger", "org.telegram.messenger.AquaIcon", aliasCount = 3, defaultAlias = false),
            app("org.telegram.messenger", "org.telegram.messenger.PremiumIcon", aliasCount = 3, defaultAlias = false),
        )
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = emptySet(),
        )
        assertEquals(1, report.unmatched.size)
        assertEquals(2, report.excluded.size)
        assertTrue(report.excluded.all { it.reason == ExcludeReason.NON_DEFAULT_ALIAS })
    }

    @Test
    fun `aliases kept when rule disabled`() {
        val apps = listOf(
            app("org.telegram.messenger", "org.telegram.messenger.DefaultIcon", aliasCount = 2, defaultAlias = true),
            app("org.telegram.messenger", "org.telegram.messenger.AquaIcon", aliasCount = 2, defaultAlias = false),
        )
        val report = CoverageCalculator.compute(
            apps = apps,
            appFilter = filter(),
            availableDrawables = emptySet(),
            rules = CoverageRules.DEFAULT.copy(onlyDefaultAlias = false),
        )
        assertEquals(2, report.unmatched.size)
    }

    @Test
    fun `coverage ratio`() {
        val apps = listOf(
            app("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
            app("bin.mt.plus"),
            app("com.new.app"),
        )
        val report = CoverageCalculator.compute(apps, filter(), setOf("ic_wechat", "ic_mt"))
        assertEquals(2f / 3f, report.coverageRatio)
    }

    @Test
    fun `empty input yields empty report`() {
        val report = CoverageCalculator.compute(emptyList(), filter(), emptySet())
        assertEquals(0, report.total)
        assertEquals(0f, report.coverageRatio)
    }
}