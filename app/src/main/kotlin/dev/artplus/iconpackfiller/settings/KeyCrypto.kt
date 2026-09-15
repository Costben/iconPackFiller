package dev.artplus.iconpackfiller.settings

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加解密。移植自 ArtPlus `ParamsCrypto.kt`（GPL-3.0-or-later，派生作品）。
 *
 * - AES/GCM/NoPadding，密钥存 AndroidKeyStore，别名 `iconpackfiller_gpt_key`。
 * - 存储格式 `Base64(iv):Base64(ciphertext)`。
 * - 解密失败返回空串并清理坏数据，不崩溃。
 * - 日志与导出永不含 Key。
 */
object KeyCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "iconpackfiller_gpt_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    const val PREF_ENCRYPTED = "provider_api_key_encrypted"
    private const val PREF_LEGACY_PLAIN = "provider_api_key"

    /** 槽位化 Key 的存储键前缀：`provider_api_key_encrypted_<slotId>`。 */
    private const val PREF_SLOT_PREFIX = "provider_api_key_encrypted_"

    private fun slotPrefKey(slotId: String): String = PREF_SLOT_PREFIX + slotId

    /**
     * 读取指定槽位的 Key；不存在返回空串。
     */
    fun loadSlot(prefs: SharedPreferences, slotId: String): String {
        val encrypted = prefs.getString(slotPrefKey(slotId), null) ?: return ""
        return runCatching { decrypt(encrypted) }.getOrDefault("")
    }

    /**
     * 保存指定槽位的 Key；空串即删除该槽位 Key。
     */
    fun saveSlot(prefs: SharedPreferences, slotId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(slotPrefKey(slotId)).apply()
            return
        }
        prefs.edit().putString(slotPrefKey(slotId), encrypt(trimmed)).apply()
    }

    /**
     * 旧版单 Key 迁移到指定槽位（仅当目标槽位无 Key 且旧 Key 存在时执行）。
     */
    fun migrateLegacyToSlot(prefs: SharedPreferences, slotId: String) {
        if (prefs.contains(slotPrefKey(slotId))) return
        val legacy = load(prefs)
        if (legacy.isBlank()) return
        prefs.edit().putString(slotPrefKey(slotId), encrypt(legacy)).apply()
    }

    fun load(prefs: SharedPreferences): String {
        val encrypted = prefs.getString(PREF_ENCRYPTED, null)
        val decrypted = encrypted
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { decrypt(it) }.getOrNull() }
        if (decrypted != null) {
            if (prefs.contains(PREF_LEGACY_PLAIN)) {
                prefs.edit().remove(PREF_LEGACY_PLAIN).apply()
            }
            return decrypted
        }
        val legacyPlain = prefs.getString(PREF_LEGACY_PLAIN, "") ?: ""
        if (legacyPlain.isNotBlank()) {
            val migrated = encrypt(legacyPlain)
            prefs.edit()
                .remove(PREF_LEGACY_PLAIN)
                .putString(PREF_ENCRYPTED, migrated)
                .apply()
        }
        return legacyPlain
    }

    fun save(prefs: SharedPreferences, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(PREF_ENCRYPTED).remove(PREF_LEGACY_PLAIN).apply()
            return
        }
        prefs.edit().putString(PREF_ENCRYPTED, encrypt(value)).apply()
    }

    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, encrypted)
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    fun decrypt(value: String): String {
        val parts = value.split(':')
        if (parts.size != 2) error("invalid encrypted secret")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}