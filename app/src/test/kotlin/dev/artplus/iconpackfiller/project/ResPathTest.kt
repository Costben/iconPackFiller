package dev.artplus.iconpackfiller.project

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResPathTest {

    @Test
    fun `png resources are classified by extension`() {
        assertEquals(PackEntryKind.PNG, ResPath.kind("res/mipmap-xxhdpi/ic_launcher.png"))
        assertEquals(PackEntryKind.PNG, ResPath.kind("res/drawable/foo.webp"))
    }

    @Test
    fun `vector xml in drawable is a vector`() {
        assertEquals(PackEntryKind.VECTOR_XML, ResPath.kind("res/drawable/ic_foo.xml"))
        assertEquals(PackEntryKind.VECTOR_XML, ResPath.kind("res/drawable-hdpi/ic_foo.xml"))
    }

    @Test
    fun `adaptive xml in mipmap-anydpi is adaptive`() {
        assertEquals(
            PackEntryKind.ADAPTIVE_XML,
            ResPath.kind("res/mipmap-anydpi-v26/ic_launcher.xml"),
        )
    }

    @Test
    fun `unknown resources are other`() {
        assertEquals(PackEntryKind.OTHER, ResPath.kind("res/raw/data.bin"))
        assertEquals(PackEntryKind.OTHER, ResPath.kind(null))
        assertEquals(PackEntryKind.OTHER, ResPath.kind(""))
    }

    @Test
    fun `density qualifier is extracted`() {
        assertEquals("xxhdpi", ResPath.density("res/mipmap-xxhdpi/ic_launcher.png"))
        assertEquals("hdpi", ResPath.density("res/drawable-hdpi/ic.xml"))
        assertEquals("anydpi-v26", ResPath.density("res/mipmap-anydpi-v26/ic.xml"))
        assertNull(ResPath.density("res/drawable/ic.xml"))
        assertNull(ResPath.density(null))
    }
}
