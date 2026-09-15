package dev.artplus.iconpackfiller.pack

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawableDirectoryDetectorTest {

    private fun fixture(name: String = "test-iconpack.apk"): File {
        for (candidate in listOf("app/src/test/resources/$name", "src/test/resources/$name")) {
            val file = File(candidate)
            if (file.isFile) return file
        }
        error("fixture 缺失：$name")
    }

    @Test
    fun `detects xxxhdpi directory from fixture`() {
        val convention = DrawableDirectoryDetector.detect(fixture())
        assertEquals("res/drawable-xxxhdpi-v4", convention.directory)
        assertEquals("xxxhdpi", convention.density)
        assertTrue(convention.sampleCount > 0)
    }

    @Test
    fun `reads pixel size from fixture icons`() {
        val convention = DrawableDirectoryDetector.detect(fixture())
        assertEquals(192, convention.pixelSize)
    }

    @Test
    fun `missing file falls back`() {
        val convention = DrawableDirectoryDetector.detect(File("/nonexistent/fake.apk"))
        assertEquals("res/${DrawableDirectoryDetector.FALLBACK_DIRECTORY}", convention.directory)
        assertEquals(DrawableDirectoryDetector.FALLBACK_DENSITY, convention.density)
        assertNull(convention.pixelSize)
        assertEquals(0, convention.sampleCount)
    }

    @Test
    fun `resPath joins directory and name`() {
        val convention = DrawableDirectoryDetector.detect(fixture())
        assertEquals("res/drawable-xxxhdpi-v4/ap_gen_0.png", convention.resPath("ap_gen_0"))
    }

    @Test
    fun `densityOf strips prefix and version suffix`() {
        assertEquals("nodpi", DrawableDirectoryDetector.densityOf("res/drawable-nodpi-v4"))
        assertEquals("xxxhdpi", DrawableDirectoryDetector.densityOf("res/drawable-xxxhdpi-v4"))
        assertEquals("hdpi", DrawableDirectoryDetector.densityOf("res/drawable-hdpi"))
        assertNull(DrawableDirectoryDetector.densityOf("res/drawable"))
    }

    @Test
    fun `pngPixelSize parses square png headers`() {
        val header = pngHeader(192, 192)
        assertEquals(192, DrawableDirectoryDetector.pngPixelSize(header))

        val big = pngHeader(1254, 1254)
        assertEquals(1254, DrawableDirectoryDetector.pngPixelSize(big))
    }

    @Test
    fun `pngPixelSize rejects non square and non png`() {
        assertNull(DrawableDirectoryDetector.pngPixelSize(pngHeader(100, 50)))
        assertNull(DrawableDirectoryDetector.pngPixelSize(ByteArray(24)))
        assertNull(DrawableDirectoryDetector.pngPixelSize("not a png header at all!!".toByteArray()))
    }

    @Test
    fun `pngPixelSize rejects truncated header`() {
        assertNull(DrawableDirectoryDetector.pngPixelSize(ByteArray(10)))
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes, 0)
        // 长度 + "IHDR"
        bytes[8] = 0; bytes[9] = 0; bytes[10] = 0; bytes[11] = 13
        bytes[12] = 'I'.code.toByte(); bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte(); bytes[15] = 'R'.code.toByte()
        writeInt(bytes, 16, width)
        writeInt(bytes, 20, height)
        return bytes
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
