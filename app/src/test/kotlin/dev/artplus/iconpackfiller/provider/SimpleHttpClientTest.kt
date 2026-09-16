package dev.artplus.iconpackfiller.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimpleHttpClientTest {

    @Test
    fun `remote image download never receives provider authorization`() = runBlocking {
        var authorization: String? = "not-requested"
        var googleApiKey: String? = "not-requested"
        withServer { server ->
            server.createContext("/image") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                googleApiKey = exchange.requestHeaders.getFirst("x-goog-api-key")
                exchange.reply(200, "image-bytes".toByteArray())
            }
            val bytes = SimpleHttpClient(maxRetries = 0).getBytes(server.url("/image"))
            assertEquals("image-bytes", bytes.toString(Charsets.UTF_8))
        }
        assertEquals(null, authorization)
        assertEquals(null, googleApiKey)
    }

    @Test
    fun `provider post keeps bearer authorization`() = runBlocking {
        var authorization: String? = null
        withServer { server ->
            server.createContext("/generate") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                exchange.reply(200, "{}".toByteArray())
            }
            val response = SimpleHttpClient(maxRetries = 0).postJson(
                url = server.url("/generate"),
                body = "{}",
                apiKey = "provider-secret",
            )
            assertEquals("{}", response)
        }
        assertEquals("Bearer provider-secret", authorization)
    }

    @Test
    fun `provider post retries rate limit and keeps bearer authorization`() = runBlocking {
        val requests = AtomicInteger()
        var authorization: String? = null
        withServer { server ->
            server.createContext("/rate-limited") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                if (requests.incrementAndGet() == 1) {
                    exchange.reply(429, "retry later".toByteArray())
                } else {
                    exchange.reply(200, "{}".toByteArray())
                }
            }
            val response = SimpleHttpClient(maxRetries = 1, retryBaseDelayMs = 1).postJson(
                url = server.url("/rate-limited"),
                body = "{}",
                apiKey = "provider-secret",
            )
            assertEquals("{}", response)
        }
        assertEquals(2, requests.get())
        assertEquals("Bearer provider-secret", authorization)
    }

    @Test
    fun `transient IO failure retries image download`() = runBlocking {
        val requests = AtomicInteger()
        withServer { server ->
            server.createContext("/flaky") { exchange ->
                if (requests.incrementAndGet() == 1) {
                    // 不写响应即关闭，HttpURLConnection 会得到 IOException。
                    exchange.close()
                } else {
                    exchange.reply(200, "ok".toByteArray())
                }
            }
            val bytes = SimpleHttpClient(maxRetries = 1, retryBaseDelayMs = 1)
                .getBytes(server.url("/flaky"))
            assertEquals("ok", bytes.toString(Charsets.UTF_8))
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun `client error does not retry image download`() = runBlocking {
        val requests = AtomicInteger()
        var error: ImageProviderException? = null
        withServer { server ->
            server.createContext("/bad") { exchange ->
                requests.incrementAndGet()
                exchange.reply(400, "bad request".toByteArray())
            }
            try {
                SimpleHttpClient(maxRetries = 2, retryBaseDelayMs = 1).getBytes(server.url("/bad"))
            } catch (e: ImageProviderException) {
                error = e
            }
        }
        assertNotNull(error)
        assertEquals(400, error.httpStatus)
        assertEquals(1, requests.get())
    }

    @Test
    fun `cancelling stalled download returns promptly`() = runBlocking {
        val started = CountDownLatch(1)
        withServer { server ->
            server.createContext("/stall") { exchange ->
                started.countDown()
                try {
                    Thread.sleep(5_000)
                    exchange.reply(200, "late".toByteArray())
                } catch (_: InterruptedException) {
                    exchange.close()
                }
            }
            val request = async(Dispatchers.Default) {
                SimpleHttpClient(readTimeoutMs = 10_000, maxRetries = 0).getBytes(server.url("/stall"))
            }
            assertTrue(started.await(2, TimeUnit.SECONDS), "服务端未收到请求")
            val startedAt = System.nanoTime()
            request.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMs < 1_500, "取消等待过久：${elapsedMs}ms")
        }
    }

    private suspend fun withServer(block: suspend (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.url(path: String): String = "http://127.0.0.1:${address.port}$path"

    private fun HttpExchange.reply(status: Int, body: ByteArray) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}
