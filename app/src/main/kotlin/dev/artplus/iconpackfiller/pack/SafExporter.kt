package dev.artplus.iconpackfiller.pack

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * SAF 导出：把签名后的补全包写到用户选择的位置。
 */
object SafExporter {

    /**
     * 把 [sourceApk] 复制到 SAF 目录 [treeUri] 下，文件名 [fileName]。
     *
     * @return 写入后的文档 Uri；失败返回 null。
     */
    fun exportToTree(
        context: Context,
        treeUri: Uri,
        sourceApk: File,
        fileName: String,
    ): Uri? {
        val directory = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!directory.isDirectory) return null
        val existing = directory.findFile(fileName)
        val target = existing?.takeIf { it.isFile } ?: directory.createFile("application/vnd.android.package-archive", fileName)
            ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                sourceApk.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            target.uri
        }.getOrNull()
    }

    /**
     * 生成默认文件名：`<原包名>_filler_<versionCode>.apk`。
     */
    fun fileNameFor(originalPackage: String, versionCode: Int): String {
        val safe = originalPackage.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${safe}_filler_$versionCode.apk"
    }
}