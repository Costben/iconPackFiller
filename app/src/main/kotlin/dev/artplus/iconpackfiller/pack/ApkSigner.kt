package dev.artplus.iconpackfiller.pack

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * APK 签名（apksig v1+v2+v3）。
 *
 * 密钥库以 PKCS12 形式随应用分发（`assets/filler-signing.p12`），
 * 每次安装首次读取后缓存。所有补全包共用同一证书，便于启动器识别为同一来源。
 */
object ApkSigner {

    private const val KEYSTORE_ASSET = "filler-signing.p12"
    private const val STORE_PASSWORD = "iconpackfiller"
    private const val KEY_ALIAS = "iconpackfiller"

    data class SigningMaterial(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    private var cached: SigningMaterial? = null

    /**
     * 从 assets 读取签名材料（线程安全，结果缓存）。
     */
    fun loadFromAssets(openAsset: () -> InputStream): SigningMaterial {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val keyStore = KeyStore.getInstance("PKCS12")
            openAsset().use { stream ->
                keyStore.load(stream, STORE_PASSWORD.toCharArray())
            }
            val key = keyStore.getKey(KEY_ALIAS, STORE_PASSWORD.toCharArray()) as PrivateKey
            val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
            val material = SigningMaterial(key, certificate)
            cached = material
            return material
        }
    }

    /**
     * 对 [inputApk] 签名并写出 [outputApk]。v1+v2+v3 全开。
     */
    fun sign(
        inputApk: File,
        outputApk: File,
        material: SigningMaterial,
        minSdkVersion: Int = 26,
    ) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            material.privateKey,
            listOf(material.certificate),
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(minSdkVersion)
            .build()
            .sign()
    }

    /**
     * 便捷入口：打包 + 签名一步到位。
     */
    fun signPacked(
        unsignedApk: File,
        signedApk: File,
        material: SigningMaterial,
        minSdkVersion: Int = 26,
    ): File {
        sign(unsignedApk, signedApk, material, minSdkVersion)
        return signedApk
    }
}