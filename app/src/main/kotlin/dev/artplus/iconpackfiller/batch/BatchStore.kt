package dev.artplus.iconpackfiller.batch

import android.content.Context
import dev.artplus.iconpackfiller.generate.GenerationAttempt
import java.io.File
import java.security.MessageDigest

/**
 * 批次存储：每个批次一个目录 `<root>/<id>/`。
 *
 * ```
 * <id>/
 *   batch.json         # BatchRecord
 *   out.apk            # 输出 APK（已签名）
 *   att/<pkg>-<n>.png  # 每次尝试的生成图
 *   att/<pkg>-src.png  # 目标原图（对比用）
 * ```
 *
 * 应用内用 `filesDir` 而非 `cacheDir`：批次要在重启后仍可查看/导出，
 * 缓存目录会被系统回收。根目录可注入，便于 JVM 单测用临时目录。
 */
class BatchStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "batches"))

    init {
        root.mkdirs()
    }

    /**
     * 目录名到安全文件名：包名/组件名里的 `/`、`:` 等会破坏路径。
     * 过长时截断 + 加哈希后缀，避免超出文件系统名长限制。
     */
    private fun safe(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (cleaned.length <= 60) return cleaned
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return cleaned.take(40) + "_" + hash
    }

    fun newId(timestamp: Long = System.currentTimeMillis()): String =
        "batch-$timestamp-" + java.util.UUID.randomUUID().toString().take(8)

    fun dirOf(id: String): File = File(root, id)

    fun attemptDir(id: String): File = File(dirOf(id), "att")

    /**
     * 写入/更新批次记录。
     *
     * 先写 `.tmp` 再原子改名，避免进程中断留下半个 JSON（解析失败会丢整条记录）。
     */
    fun save(record: BatchRecord) {
        val dir = dirOf(record.id).apply { mkdirs() }
        val text = BatchCodec.encode(record)
        val tmp = File(dir, "batch.json.tmp")
        tmp.writeText(text)
        val target = File(dir, "batch.json")
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // 改名失败（跨文件系统等）：退化为直接写
            target.writeText(text)
            tmp.delete()
        }
    }

    fun read(id: String): BatchRecord? {
        val file = File(dirOf(id), "batch.json")
        if (!file.exists()) return null
        return BatchCodec.decode(runCatching { file.readText() }.getOrNull())
    }

    /** 全部批次，创建时间倒序（新的在前）。损坏的记录直接跳过。 */
    fun list(): List<BatchRecord> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> read(dir.name) }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 启动清理：把上次进程留下的 [BatchStatus.RUNNING] 标记为 [BatchStatus.INTERRUPTED]。
     *
     * 生成任务在协程里跑，进程死亡后无法恢复；不标记的话界面会一直显示「运行中」。
     */
    fun markInterrupted(): List<BatchRecord> {
        val stale = list().filter { it.status == BatchStatus.RUNNING }
        for (record in stale) {
            save(record.copy(status = BatchStatus.INTERRUPTED))
        }
        return stale
    }

    /**
     * 落盘一次 provider 请求的产物（生成图 + 目标原图），返回可进 JSON 的记录。
     *
     * 由编排器的 `onAttempt` 增量调用：一旦写盘，用户取消或进程被杀也保得住已生成的图。
     * 写文件失败不抛异常（图丢了不该让整个批次崩掉），只是没有文件名。
     */
    fun persistAttempt(id: String, attempt: GenerationAttempt): AttemptRecord {
        val attDir = attemptDir(id).apply { mkdirs() }
        val base = safe(attempt.packageName) + "-" + attempt.attempt
        val pngFile = "$base.png"
        val sourceFile = safe(attempt.packageName) + "-src.png"

        runCatching { File(attDir, pngFile).writeBytes(attempt.pngBytes) }
        val sourceWritten = attempt.sourcePngBytes?.let { bytes ->
            runCatching { File(attDir, sourceFile).writeBytes(bytes) }.isSuccess
        } ?: false

        return AttemptRecord(
            packageName = attempt.packageName,
            label = attempt.label,
            attempt = attempt.attempt,
            accepted = attempt.accepted,
            reason = attempt.reason,
            pngFile = pngFile.takeIf { File(attDir, it).exists() },
            sourceFile = sourceFile.takeIf { sourceWritten },
            references = attempt.references,
            model = attempt.provenance.model,
            slotId = attempt.provenance.slotId,
            slotName = attempt.provenance.slotName,
            prompt = attempt.prompt,
            referenceDetails = attempt.referenceDetails,
        )
    }

    /** 把签名 APK 复制进批次目录。失败返回 null（不影响批次其他内容）。 */
    fun persistApk(id: String, apk: File?): String? {
        if (apk == null || !apk.exists()) return null
        return runCatching {
            val target = File(dirOf(id), "out.apk")
            apk.copyTo(target, overwrite = true)
            target.name
        }.getOrNull()
    }

    /** 单张尝试 PNG 的绝对路径；文件不存在返回 null。 */
    fun attemptFile(id: String, fileName: String?): File? {
        if (fileName.isNullOrEmpty()) return null
        val file = File(attemptDir(id), fileName)
        return file.takeIf { it.exists() }
    }

    fun outputApk(id: String, fileName: String?): File? {
        if (fileName.isNullOrEmpty()) return null
        val file = File(dirOf(id), fileName)
        return file.takeIf { it.exists() }
    }

    /** 删除批次（含目录与其中所有图/APK）。 */
    fun delete(id: String): Boolean = dirOf(id).deleteRecursively()
}
