package com.mochyy.mochymusic.utils

import com.mochyy.mochymusic.discord.SpinningArtwork
import com.music.innertube.NewPipeDownloaderImpl
import com.music.innertube.YouTube
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResourceEfficiencyAuditTest {

    @Test
    fun sharedClientAndBuildersShareDispatcherAndConnectionPool() {
        val base = AppProxy.sharedClient
        val proxied = AppProxy.newClientBuilder().build()
        val direct = AppProxy.newDirectClientBuilder().build()

        assertSame(base.dispatcher, proxied.dispatcher)
        assertSame(base.connectionPool, proxied.connectionPool)
        assertSame(base.dispatcher, direct.dispatcher)
        assertSame(AppProxy.connectionPool, base.connectionPool)
    }

    @Test
    fun appProxyAuthenticatorStopsOnMissingAuthOrRetryHeader() {
        val previousAuth = YouTube.proxyAuth
        try {
            val auth = AppProxy.sharedClient.proxyAuthenticator

            YouTube.proxyAuth = null
            val unauthedRequest = Request.Builder().url("https://www.youtube.com/").build()
            assertNull(auth.authenticate(null, response407(unauthedRequest)))

            YouTube.proxyAuth = "Basic dXNlcjpwYXNz"
            val firstAttempt = auth.authenticate(null, response407(unauthedRequest))
            assertNotNull(firstAttempt)
            assertEquals("Basic dXNlcjpwYXNz", firstAttempt!!.header("Proxy-Authorization"))

            val retriedOnce = response407(firstAttempt)
            assertNull(auth.authenticate(null, retriedOnce))
        } finally {
            YouTube.proxyAuth = previousAuth
        }
    }

    @Test
    fun newPipeDownloaderAuthenticatorPrevents407Loop() {
        val previousAuth = YouTube.proxyAuth
        try {
            val downloader = NewPipeDownloaderImpl(proxy = null)
            val field = NewPipeDownloaderImpl::class.java.getDeclaredField("client")
            field.isAccessible = true
            val client = field.get(downloader) as OkHttpClient
            val auth = client.proxyAuthenticator

            YouTube.proxyAuth = null
            val bare = Request.Builder().url("https://www.youtube.com/").build()
            assertNull(auth.authenticate(null, response407(bare)))

            YouTube.proxyAuth = "Basic YWJjOnh5eg=="
            val retried = auth.authenticate(null, response407(bare))
            assertNotNull(retried)
            assertEquals("Basic YWJjOnh5eg==", retried!!.header("Proxy-Authorization"))

            assertNull(auth.authenticate(null, response407(retried)))
        } finally {
            YouTube.proxyAuth = previousAuth
        }
    }

    @Test
    fun appProxyParseAddressAndLabelHandleIpv4DomainAndIpv6() {
        val ipv4 = AppProxy.parseAddress("http://user:pass@192.168.1.50:8080/")
        assertNotNull(ipv4)
        assertEquals("192.168.1.50:8080", AppProxy.addressLabel(ipv4!!))

        val ipv6 = AppProxy.parseAddress("[2001:db8::1]:3128")
        assertNotNull(ipv6)
        assertEquals("[2001:db8::1]:3128", AppProxy.addressLabel(ipv6!!))

        assertNull(AppProxy.parseAddress("invalid-no-port"))
        assertNull(AppProxy.parseAddress(":8080"))
        assertNull(AppProxy.parseAddress("example.com:70000"))
    }

    @Test
    fun spinningArtworkPaletteReusedAcrossWorkersProducesIdenticalIndices() {
        val samplePixels = IntArray(64) { i ->
            when {
                i % 11 == 0 -> 0x00112233
                else -> (0xFF shl 24) or ((i * 37 and 0xFF) shl 16) or ((i * 71 and 0xFF) shl 8) or (i * 19 and 0xFF)
            }
        }
        val primary = SpinningArtwork.Palette.of(samplePixels)
        val workerCopy = SpinningArtwork.Palette(primary.colors)

        for (pixel in samplePixels) {
            assertEquals(primary.indexOf(pixel), workerCopy.indexOf(pixel))
        }
    }

    private fun response407(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(407)
        .message("Proxy Authentication Required")
        .build()
}
