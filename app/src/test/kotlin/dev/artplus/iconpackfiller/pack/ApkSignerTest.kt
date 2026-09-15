package dev.artplus.iconpackfiller.pack

import com.android.apksig.ApkVerifier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 签名端到端（桌面 JVM）：打包 fixture -> apksig 签名 -> ApkVerifier 校验。
 */
class ApkSignerTest {

    private val fixture: File
        get() = File(javaClass.classLoader!!.getResource("test-iconpack.apk")!!.toURI())

    private val keystore: File
        get() = File("src/main/assets/filler-signing.p12")

    private fun tinyPng(seed: Int): ByteArray {
        val bitmap = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) {
            bitmap.setRGB(x, y, 0xFF000000.toInt() or (seed * 0x010203))
        }
        val output = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(bitmap, "png", output)
        return output.toByteArray()
    }

    @Test
    fun `keystore asset exists and loads`() {
        assertTrue(keystore.isFile, "missing ${keystore.absolutePath}")
        val material = ApkSigner.loadFromAssets { keystore.inputStream() }
        assertEquals("RSA", material.privateKey.algorithm)
        assertTrue(material.certificate.subjectX500Principal.name.contains("IconPackFiller"))
    }

    @Test
    fun `signs packed apk and verifier accepts v1 v2 v3`() {
        val unsigned = File.createTempFile("unsigned-", ".apk")
        val signed = File.createTempFile("signed-", ".apk")

        IconPackPacker().pack(
            fixture,
            unsigned,
            listOf(
                IconInjection(
                    "ComponentInfo{com.example.newapp/com.example.newapp.Main}",
                    "ap_gen_0",
                    tinyPng(5),
                ),
            ),
        )
        val material = ApkSigner.loadFromAssets { keystore.inputStream() }
        ApkSigner.sign(unsigned, signed, material)

        val result = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
        assertTrue(result.isVerified, result.errors.joinToString { it.toString() })
        assertTrue(result.isVerifiedUsingV2Scheme, "v2 not verified")
        assertTrue(result.isVerifiedUsingV3Scheme, "v3 not verified")

        // apksig 对 minSdk>=24 的 APK 跳过 v1 校验，改为直接检查 META-INF 签名文件存在
        val v1Files = java.util.zip.ZipFile(signed).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".SF")) }
                .toList()
        }
        assertTrue(v1Files.any { it.endsWith(".SF") }, "v1 .SF missing: $v1Files")
        assertTrue(v1Files.any { it.endsWith(".RSA") }, "v1 .RSA missing: $v1Files")

        // 签名后的包仍可被 ARSCLib 读回
        com.reandroid.apk.ApkModule.loadApkFile(signed).use { module ->
            assertTrue(module.androidManifest!!.packageName.startsWith("dev.artplus.iconpack."))
        }

        unsigned.delete()
        signed.delete()
    }
}