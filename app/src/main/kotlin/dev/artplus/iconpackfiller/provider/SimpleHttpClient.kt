package dev.artplus.iconpackfiller.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
     */
    suspend fun getText(
        url: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authMode: AuthMode = AuthMode.BEARER,
        timeoutMs: Int = 15_000,
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (authMode == AuthMode.BEARER && apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw ImageProviderException(
                    "HTTP $code: ${text.take(200)}",
                    httpStatus = code,
                )
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getBytes(url: String, apiKey: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url)
        connection.requestMethod = "GET"
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        try {
            val stream = streamOf(connection)
            val bytes = stream.use { it.readBytes() }
            if (connection.responseCode !in 200..299) {
                throw ImageProviderException(
                    "下载失败 HTTP ${connection.responseCode}: ${String(bytes).take(200)}",
                    httpStatus = connection.responseCode,
                    retryable = connection.responseCode in 500..599 || connection.responseCode == 429,
                )
            }
            bytes
        } finally {
            connection.disconnect()
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
    ): String {
        var lastError: ImageProviderException? = null
        val attempts = maxRetries + 1
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                delay(retryBaseDelayMs * (1L shl (attempt - 1)))
            }
            try {
                return executeOnce(method, url, body, contentType, accept, apiKey, extraHeaders, authMode)
            } catch (e: ImageProviderException) {
                lastError = e
                debugLog?.invoke("HTTP attempt ${attempt + 1}/$attempts failed: ${e.message}")
                if (!e.retryable) throw e
            }
        }
        throw lastError ?: ImageProviderException("请求失败")
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
    ): String = withContext(Dispatchers.IO) {
        val connection = open(url)
        connection.requestMethod = method
        connection.doOutput = true
        connection.setRequestProperty("Accept", accept)
        if (authMode == AuthMode.BEARER && apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("Content-Length", body.size.toString())
        for ((k, v) in extraHeaders) connection.setRequestProperty(k, v)

        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = streamOf(connection)
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                debugLog?.invoke("HTTP $code: ${text.take(200)}")
                throw ImageProviderException(
                    "AI HTTP $code: ${text.take(300)}",
                    httpStatus = code,
                    retryable = code in 500..599 || code == 429,
                )
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun open(urlText: String): HttpURLConnection {
        val url = URL(urlText)
        val protocol = url.protocol.lowercase(Locale.US)
        if (protocol != "http" && protocol != "https") {
            throw ImageProviderException("URL 只支持 HTTP/HTTPS: $urlText")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
    }

    private fun streamOf(connection: HttpURLConnection): InputStream {
        val code = connection.responseCode
        return if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
    }
}