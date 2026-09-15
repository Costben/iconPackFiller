package dev.artplus.iconpackfiller.provider

import android.graphics.Bitmap

/**
 * 图像生成请求。
 *
 * @param prompt 完整提示词（M4 由 ContactSheet 模板生成）。
 * @param images 输入图（参考图 / 目标原图 / ContactSheet 合成图），顺序即语义顺序。
 * @param size 期望输出尺寸，provider 不支持时忽略。
 */
data class ImageRequest(
    val prompt: String,
    val images: List<Bitmap>,
    val size: String = "1024x1024",
)

/**
 * 图像 Provider 抽象。
 *
 * 实现必须：
 * - 可取消（协程取消即中断网络请求）
 * - 有超时（连接 / 读取）
 * - 不把 apiKey 写入日志
 */
interface ImageProvider {
    /** Provider 显示名。 */
    val name: String

    suspend fun generate(request: ImageRequest): Bitmap

    /** 预计调用成本的人类可读描述（UI 费用提示用）。 */
    fun costHint(): String? = null
}

class ImageProviderException(
    message: String,
    cause: Throwable? = null,
    /** HTTP 状态码；网络层异常为 null。 */
    val httpStatus: Int? = null,
    /** 是否值得重试（5xx / 429 / 网络中断）。 */
    val retryable: Boolean = false,
) : Exception(message, cause)
