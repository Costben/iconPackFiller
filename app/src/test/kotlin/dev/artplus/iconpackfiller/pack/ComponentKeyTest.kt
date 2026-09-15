package dev.artplus.iconpackfiller.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentKeyTest {

    @Test
    fun `parses fully qualified component`() {
        val key = ComponentKey.parse("ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI}")
        assertNotNull(key)
        assertEquals("com.tencent.mm", key.packageName)
        assertEquals("com.tencent.mm.ui.launcherui", key.activityName)
        assertTrue(!key.isPackageLevel)
    }

    @Test
    fun `expands leading dot activity`() {
        val key = ComponentKey.parse("ComponentInfo{org.telegram.messenger/.DefaultIcon}")
        assertNotNull(key)
        assertEquals("org.telegram.messenger", key.packageName)
        assertEquals("org.telegram.messenger.defaulticon", key.activityName)
    }

    @Test
    fun `expands bare class name`() {
        val key = ComponentKey.parse("ComponentInfo{com.foo.bar/MainActivity}")
        assertNotNull(key)
        assertEquals("com.foo.bar.mainactivity", key.activityName)
    }

    @Test
    fun `parses package level entry`() {
        val key = ComponentKey.parse("ComponentInfo{bin.mt.plus}")
        assertNotNull(key)
        assertEquals("bin.mt.plus", key.packageName)
        assertNull(key.activityName)
        assertTrue(key.isPackageLevel)
    }

    @Test
    fun `keeps inner class dollar sign`() {
        val key = ComponentKey.parse("ComponentInfo{com.foo.bar/com.foo.bar.Outer\$Inner}")
        assertNotNull(key)
        assertEquals("com.foo.bar.outer\$inner", key.activityName)
    }

    @Test
    fun `normalizes case for package and activity`() {
        val key = ComponentKey.parse("ComponentInfo{COM.Foo.BAR/COM.Foo.Bar.Main}")
        assertNotNull(key)
        assertEquals("com.foo.bar", key.packageName)
        assertEquals("com.foo.bar.main", key.activityName)
    }

    @Test
    fun `accepts raw component without wrapper`() {
        val key = ComponentKey.parse("com.foo.bar/com.foo.bar.Main")
        assertNotNull(key)
        assertEquals("com.foo.bar", key.packageName)
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(ComponentKey.parse(null))
        assertNull(ComponentKey.parse(""))
        assertNull(ComponentKey.parse("ComponentInfo{}"))
        assertNull(ComponentKey.parse("ComponentInfo{/Main}"))
        assertNull(ComponentKey.parse("ComponentInfo{com.foo/}"))
    }

    @Test
    fun `flatten round trips`() {
        val key = ComponentKey.parse("ComponentInfo{com.foo.bar/com.foo.bar.Main}")!!
        assertEquals("com.foo.bar/com.foo.bar.main", key.flatten())
        val pkg = ComponentKey.parse("ComponentInfo{bin.mt.plus}")!!
        assertEquals("bin.mt.plus", pkg.flatten())
    }
}