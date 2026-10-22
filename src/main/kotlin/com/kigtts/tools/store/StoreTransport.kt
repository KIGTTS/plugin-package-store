package com.kigtts.tools.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

interface StoreTransport {
    suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit = {})
    fun cancel() {}
}
class HttpsStoreTransport(private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val beforeRequest: suspend () -> Unit = {}) : StoreTransport {
    private val active = AtomicReference<HttpsURLConnection?>()
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    override fun cancel() { cancelled.set(true); active.getAndSet(null)?.disconnect() }
    override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        beforeRequest()
        check(busy.compareAndSet(false, true)) { "已有下载正在进行" }
        cancelled.set(false)
        try {
            var target = URL(CatalogCodec.https(url)); var redirects = 0
            while (true) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw kotlinx.coroutines.CancellationException("下载已取消")
            // A fresh HTTPS connection carries no source credentials, cookies or authorization headers.
            val connection = connect(target)
            active.set(connection)
            try {
            connection.connectTimeout = 20_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, application/octet-stream, application/zip")
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                check(redirects++ < 5) { "下载地址跳转过多" }
                val location = connection.getHeaderField("Location")?.takeIf(String::isNotBlank) ?: error("下载跳转缺少地址")
                target = URL(CatalogCodec.https(URL(target, location).toString()))
                continue
            }
            check(status == 200) { "下载地址暂时不可用（$status）" }
            require(connection.contentLengthLong <= maximumBytes) { "下载文件超过大小限制" }
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw kotlinx.coroutines.CancellationException("下载已取消")
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= maximumBytes) { "下载文件超过大小限制" }
                    output.write(buffer, 0, count); progress(bytes)
                }
            } }
            break
            } finally { active.compareAndSet(connection, null); connection.disconnect() }
            }
        } finally { busy.set(false) }
    }
}
