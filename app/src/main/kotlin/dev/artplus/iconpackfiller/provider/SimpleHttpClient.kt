package dev.artplus.iconpackfiller.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 简单 HTTP 客户端。基于 HttpURLConnection，支持协程取消、超时、退避重试。
 * 不打印 apiKey；debug 日志仅记录状态码与响应体前 N 字符。
 */
class SimpleHttpClient(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 300_000,
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 1_000,
    private val debugLog: ((String) -> Unit)? = null,
) {

    /** 请求鉴权方式：Bearer 走 `Authorization` 头，NONE 表示由 [extraHeaders] 自行携带。 */
    enum class AuthMode { BEARER, NONE }

    suspend fun postJson(
        url: String,
        body: String,
        apiKey: String,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        authMode: AuthMode = AuthMode.BEARER,
    ): String = request(
        method = "POST",
        url = url,
        body = body.toByteArray(Charsets.UTF_8),
        contentType = "application/json",
        accept = accept,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        authMode = authMode,
    )

    suspend fun postBytes(
        url: String,
        body: ByteArray,
        contentType: String,
        apiKey: String,
        accept: String = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        authMode: AuthMode = AuthMode.BEARER,
    ): String = request(
        method = "POST",
        url = url,
        body = body,
        contentType = contentType,
        accept = accept,
        apiKey = apiKey,
        extraHeaders = extraHeaders,
        authMode = authMode,
    )

    /**
     * 轻量 GET（连接测试用）。不重试，超时较短。
     * 取消时仍会主动断开底层连接。
     */
    suspend fun getText(
        url: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authMode: AuthMode = AuthMode.BEARER,
        timeoutMs: Int = 15_000,
    ): String = try {
        withConnection(url, timeoutMs, timeoutMs) { connection ->
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (authMode == AuthMode.BEARER && apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            for ((k, v) in extraHeaders) connection.setRequestProperty(k, v)

            val code = connection.responseCode
            val text = streamOf(connection, code).bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw ImageProviderException("HTTP $code: ${text.take(200)}", httpStatus = code)
            }
            text
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw networkFailure("连接测试失败", e)
    }

    /**
     * 下载 AI 响应中给出的图片 URL。
     *
     * 该 URL 不属于可信的 provider 请求端点，故不接受 API Key 或额外鉴权头；
     * 预签名 URL 自身携带下载授权。这样即使服务端返回任意第三方 URL，也不会泄露密钥。
     */
    suspend fun getBytes(url: String): ByteArray = retrying("图片下载") {
        withConnection(url) { connection ->
            connection.requestMethod = "GET"
            val code = connection.responseCode
            val bytes = streamOf(connection, code).use { it.readBytes() }
            if (code !in 200..299) {
                throw ImageProviderException(
                    "下载失败 HTTP $code: ${String(bytes).take(200)}",
                    httpStatus = code,
                    retryable = code in 500..599 || code == 429,
                )
            }
            bytes
        }
    }

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray,
        contentType: String,
        accept: String,
        apiKey: String,
        extraHeaders: Map<String, String>,
        authMode: AuthMode,
    ): String = retrying("HTTP 请求") {
        executeOnce(method, url, body, contentType, accept, apiKey, extraHeaders, authMode)
    }

    private suspend fun executeOnce(
        method: String,
        url: String,
        body: ByteArray,
        contentType: String,
        accept: String,
        apiKey: String,
        extraHeaders: Map<String, String>,
        authMode: AuthMode,
    ): String = withConnection(url) { connection ->
        connection.requestMethod = method
        connection.doOutput = true
        connection.setRequestProperty("Accept", accept)
        if (authMode == AuthMode.BEARER && apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("Content-Length", body.size.toString())
        for ((k, v) in extraHeaders) connection.setRequestProperty(k, v)

        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val text = streamOf(connection, code).bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            debugLog?.invoke("HTTP $code: ${text.take(200)}")
            throw ImageProviderException(
                "AI HTTP $code: ${text.take(300)}",
                httpStatus = code,
                retryable = code in 500..599 || code == 429,
            )
        }
        text
    }

    private suspend fun <T> retrying(operation: String, block: suspend () -> T): T {
        var lastError: ImageProviderException? = null
        val attempts = maxRetries.coerceAtLeast(0) + 1
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                delay(retryBaseDelayMs * (1L shl (attempt - 1)))
            }
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageProviderException) {
                lastError = e
                debugLog?.invoke("$operation attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (!e.retryable) throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                val failure = networkFailure(operation, e)
                lastError = failure
                debugLog?.invoke("$operation attempt ${attempt + 1}/$attempts failed: ${failure.message}")
            }
        }
        throw lastError ?: ImageProviderException("${operation}失败")
    }

    /**
     * 取消协程时 `HttpURLConnection` 不会自动中断阻塞 read；在 Job 完成时显式
     * disconnect 可让网络线程立即返回，再由 finally 释放连接。
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> withConnection(
        url: String,
        connectTimeout: Int = connectTimeoutMs,
        readTimeout: Int = readTimeoutMs,
        block: (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        val connection = open(url, connectTimeout, readTimeout)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) {
            connection.disconnect()
        }
        try {
            currentCoroutineContext().ensureActive()
            block(connection)
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun open(urlText: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection {
        val url = URL(urlText)
        val protocol = url.protocol.lowercase(Locale.US)
        if (protocol != "http" && protocol != "https") {
            throw ImageProviderException("URL 只支持 HTTP/HTTPS: $urlText")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
        }
    }

    private fun networkFailure(operation: String, cause: IOException): ImageProviderException =
        ImageProviderException(
            "${operation}网络错误：${cause.message ?: cause::class.simpleName}",
            cause,
            retryable = true,
        )

    private fun streamOf(connection: HttpURLConnection, code: Int): InputStream =
        if (code in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
}
