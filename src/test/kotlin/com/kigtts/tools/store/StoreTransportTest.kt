package com.kigtts.tools.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class StoreTransportTest {
    @get:Rule val temp = TemporaryFolder()
    private class Response(url: URL, private val status: Int, private val location: String? = null) : HttpsURLConnection(url) {
        var disconnected = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = if (name.equals("Location", true)) location else null
        override fun getContentLengthLong() = 4L
        override fun getInputStream() = "test".byteInputStream()
    }
    @Test fun officialCdnRedirectDownloadsActualBytesWithoutForwardingCredentials() = runBlocking {
        val requests = mutableListOf<Response>(); var grants = 0
        val transport = HttpsStoreTransport(beforeRequest = { grants++ }, connect = { url ->
            Response(url, if (requests.isEmpty()) 302 else 200, "https://cdn-lfs-cn-1.modelscope.cn/package").also(requests::add)
        })
        val file = temp.newFile()
        transport.fetch("https://modelscope.cn/package", file, 4) {}
        assertEquals("test", file.readText()); assertEquals(1, grants); assertEquals(2, requests.size)
        assertEquals("cdn-lfs-cn-1.modelscope.cn", requests.last().url.host)
        assertTrue(requests.all { it.disconnected && !it.instanceFollowRedirects })
        assertTrue(requests.all { it.getRequestProperty("Authorization") == null && it.getRequestProperty("Cookie") == null })
    }
    @Test fun downgradeCredentialsAndRedirectLoopsNeverBecomeTrustedDownloads() = runBlocking {
        for (target in listOf("http://example.com/file", "https://user:secret@example.com/file", "https://example.com/loop")) {
            var requests = 0
            val file = java.io.File(temp.root, "file-${requests}")
            val transport = HttpsStoreTransport(connect = { url -> requests++; Response(url, 302, target) })
            assertTrue(runCatching { transport.fetch("https://example.com/start", file, 4) {} }.isFailure)
            assertTrue(requests <= 6); assertFalse(file.exists())
        }
    }
}
