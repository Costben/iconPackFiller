package dev.artplus.iconpackfiller.project

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 项目磁盘布局。
 *
 * ```
 * <root>/<projectId>/
 *   source.apk                        # 导入快照
 *   generations/<generationId>/
 *     out.apk
 *     att/<pkg>-<n>.png
 *     att/<pkg>-src.png
 * ```
 *
 * DB 只存**相对路径**（见 [SOURCE_APK_RELATIVE] / [outputApkRelative] /
 * [attemptPngRelative]），换根目录不影响已有记录。
 */
class ProjectPaths(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "projects"))

    init {
        root.mkdirs()
    }

    fun projectDir(projectId: String): File = File(root, safeName(projectId))

    fun sourceApk(projectId: String): File = File(projectDir(projectId), SOURCE_APK_RELATIVE)

    fun generationDir(projectId: String, generationId: String): File =
        File(projectDir(projectId), "generations/${safeName(generationId)}")

    fun outputApk(projectId: String, generationId: String): File =
        File(generationDir(projectId, generationId), OUT_APK)

    fun attemptDir(projectId: String, generationId: String): File =
        File(generationDir(projectId, generationId), ATT_DIR)

    /** 建项目 + 生成目录（含 `att/`），返回生成目录。 */
    fun ensureGenerationDirs(projectId: String, generationId: String): File {
        val dir = generationDir(projectId, generationId)
        attemptDir(projectId, generationId).mkdirs()
        return dir
    }

    companion object {
        const val SOURCE_APK_RELATIVE = "source.apk"
        const val OUT_APK = "out.apk"
        const val ATT_DIR = "att"

        /** `generations/<id>/out.apk`。 */
        fun outputApkRelative(generationId: String): String =
            "generations/${safeName(generationId)}/$OUT_APK"

        /** `generations/<id>/att/<safePkg>-<n>.png`。 */
        fun attemptPngRelative(generationId: String, packageName: String, attempt: Int): String =
            "generations/${safeName(generationId)}/$ATT_DIR/${safeName(packageName)}-$attempt.png"

        /** `generations/<id>/att/<safePkg>-src.png`（目标原图，对比用）。 */
        fun attemptSourceRelative(generationId: String, packageName: String): String =
            "generations/${safeName(generationId)}/$ATT_DIR/${safeName(packageName)}-src.png"

        /**
         * 目录名到安全文件名：包名/组件名里的 `/`、`:` 等会破坏路径。
         * 过长时截断 + 加哈希后缀，避免超出文件系统名长限制。
         */
        fun safeName(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (cleaned.length <= 60) return cleaned
            val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            val hash = digest.take(4).joinToString("") { "%02x".format(it) }
            return cleaned.take(40) + "_" + hash
        }
    }
}
